# The stack tests: `StackTest`

## What it is

A **test** is a small program that checks one claim about another program and fails loudly if the claim is false. **JUnit** is the Java library that finds tests, runs them and reports which passed. **AssertJ** is a library of readable checks, such as `assertThat(x).isEqualTo(3)`. (`junit.md` and `assertj.md` explain both from zero. This note explains the one test class that needs the running Docker stack.)

Most tests of this repository run inside the Java program alone: `./gradlew check` runs 98 of them (the `PASSED` lines in my run) without Docker. `StackTest` is different. It talks over the network to the real balancers, and through them to the real instances and the real PostgreSQL database. So it can only run when the stack is up. A **tag** (a label on a test) keeps it out of the normal run: the class carries `@Tag("stack")`, `./gradlew check` excludes that tag, and a separate Gradle task, `stackTest`, runs only that tag.

## Why this repository uses it

The module makes claims that only the real stack can prove: three stateless instances answer for each other's holds, the database's one-owner rule holds across instances, a drain at the balancer stops new requests to one instance, and both balancers see all three instances up. The other tests prove the rules inside the program (for example `JdbcHoldStoreTest` runs the same schema on H2). `StackTest` proves them on PostgreSQL, through HAProxy. It is the module's "does it really work" check.

## Where it appears

| Path | What it does |
|---|---|
| `src/test/java/io/plainround/seatwave/balancer/stack/StackTest.java` | The four tests and their helpers. |
| `build.gradle.kts` | `tasks.test` excludes the tag `stack`; the task `stackTest` includes only it, and is never up to date (the stack is outside Gradle's view). See `gradle.md`. |
| `scripts/stack.sh` | `test` brings the stack up, runs `./gradlew stackTest`, and always brings it down. See `shell-scripts-walkthrough.md`. |
| `src/main/java/.../haproxy/RuntimeApi.java`, `StatRow.java`, `RuntimeCommand.java` | The client for HAProxy's runtime API that the test uses to read server states and to drain a server. See `java-haproxy-client-walkthrough.md`. |
| `compose.yaml` | Publishes the ports the test connects to: 21000, 21010, 21005, 21015. |
| `README.md` | Shows the four `PASSED` lines of a real run. |

## How it works underneath

1. **A test class is an ordinary Java class** with methods marked `@Test`. JUnit creates the class, calls each test method once (in an order it chooses, which is stable but not the order written), and records whether the method returned or threw an error. A failed AssertJ check throws an `AssertionError`.
2. **Gradle starts JUnit.** `./gradlew stackTest` compiles the code, finds classes with the tag `stack`, runs them in a new Java process, and prints one line per test.
3. **The test is a client.** It does what a fan's phone would do: it opens HTTP connections to `127.0.0.1` on the published ports of the balancers and sends requests. It never calls SeatWave's Java code.
4. **The path of a request.** Test, then HAProxy (`lb1` on 21000 or `lb2` on 21010), then one of the three SeatWave instances (chosen by round robin), then PostgreSQL. The answer comes back the same way with two headers: `X-Served-By` (written by the instance) and `X-Balancer` (written by HAProxy).
5. **The runtime API is the second path.** The test connects to HAProxy's admin port (21005 or 21015), sends text commands such as `show stat` and `set server ... state drain`, and reads the text back.
6. **Shared state.** Holds last 300 seconds and the labs also make holds, so the tests choose random seats from sections Q, R and S (1,000 seats each) and tolerate a seat that is already taken.

## Walkthrough of this repository's use

### The imports

`src/test/java/io/plainround/seatwave/balancer/stack/StackTest.java`, lines 1 to 23:

```java
package io.plainround.seatwave.balancer.stack;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.balancer.haproxy.RuntimeApi;
import io.plainround.seatwave.balancer.haproxy.RuntimeCommand.AdminState;
import io.plainround.seatwave.balancer.haproxy.StatRow;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
```
- `package io.plainround.seatwave.balancer.stack;` says which folder-like group of classes this file belongs to (the folder `stack` under `src/test/java/io/plainround/seatwave/balancer/`).
- A line `import x.y.Z;` makes the class `Z` usable by its short name. `import static org.assertj.core.api.Assertions.assertThat;` imports a **static** method, so the file can write `assertThat(...)` instead of `Assertions.assertThat(...)`.
- The three imports from `io.plainround.seatwave.balancer.haproxy` are this repository's own runtime-API client: `RuntimeApi` (the connection), `RuntimeCommand.AdminState` (the enumeration `READY`, `DRAIN`, `MAINT`) and `StatRow` (one row of the statistics table).
- `java.net.*` brings the address types (`InetAddress`, `InetSocketAddress`), `URI` (an address as an object), and the HTTP client classes `HttpClient`, `HttpRequest` and `HttpResponse`. `java.time.Duration` is a length of time. `HashSet` and `Set` are collections that hold each value once. `UUID` makes random identifiers. `Matcher` and `Pattern` are the tools of regular expressions (text patterns).
- The last five imports are JUnit's: `@AfterAll`, `@BeforeAll`, `@Tag` and `@Test` are **annotations**, labels that JUnit reads.

### The class and its constants

`src/test/java/io/plainround/seatwave/balancer/stack/StackTest.java`, lines 25 to 44:

```java
/**
 * The module's claims against the running stack (scripts/stack.sh test): three stateless instances
 * behind both balancers, the database's one-owner rule across instances, and a drain at the
 * balancer that stops new requests to one instance. Never part of ./gradlew check.
 */
@Tag("stack")
class StackTest {

  private static final String LB1 = "http://127.0.0.1:21000";
  private static final String LB2 = "http://127.0.0.1:21010";
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final int ROUNDS = 30;
  // Module 1's sections hold 1,000 seats each.
  private static final int SEATS_PER_SECTION = 1_000;
  private static final Pattern HOLD_ID = Pattern.compile("\"holdId\":\"([0-9a-f-]{36})\"");
  private static final RuntimeApi LB1_API =
      new RuntimeApi(new InetSocketAddress(InetAddress.getLoopbackAddress(), 21005), TIMEOUT);
  private static final RuntimeApi LB2_API =
      new RuntimeApi(new InetSocketAddress(InetAddress.getLoopbackAddress(), 21015), TIMEOUT);
  private static HttpClient client;
```
- The `/** ... */` block is a **Javadoc comment**, documentation attached to the class. It says: the tests prove the module's claims against the running stack (`scripts/stack.sh test`): three stateless instances behind both balancers, the database's one-owner rule across instances, and a drain at the balancer that stops new requests to one instance. "Never part of ./gradlew check."
- `@Tag("stack")` labels the whole class with the tag `stack`. The tag is what `build.gradle.kts` uses to include or exclude it.
- `class StackTest {` declares the class. It has no `public`, because JUnit does not need it.
- `private static final String LB1 = "http://127.0.0.1:21000";` is a **constant**: `private` (only this class sees it), `static` (one copy for the class, not one per test), `final` (never reassigned). `LB1` and `LB2` are the base addresses of the two balancers' PRODUCTION doors, as published on the Mac (see `docker-compose.md`).
- `TIMEOUT = Duration.ofSeconds(10)` is the longest any request waits. A stuck request fails the test instead of hanging the build.
- `ROUNDS = 30` is how many requests the tests send in a loop. 30 divides by 3 and by 2, and it is enough for round robin to cover every instance.
- The comment and `SEATS_PER_SECTION = 1_000` say that Module 1's sections hold 1,000 seats each. (`1_000` is the number 1000; the underscore is only for reading.)
- `HOLD_ID = Pattern.compile("\"holdId\":\"([0-9a-f-]{36})\"")` builds a **regular expression**. In plain text it is `"holdId":"` followed by exactly 36 characters that are digits, the letters `a` to `f`, or dashes (the shape of a UUID, such as `580ec039-fe3e-4ae2-a95c-6dd0c0cab0e4`), followed by `"`. The parentheses mark **group 1**, the part to extract: the hold's identifier. In Java source, each `"` inside the string is written `\"`.
- `LB1_API` and `LB2_API` are two `RuntimeApi` objects. `new RuntimeApi(address, timeout)` makes one. `new InetSocketAddress(InetAddress.getLoopbackAddress(), 21005)` is the address `127.0.0.1` port 21005, the published runtime API of `lb1` (21015 for `lb2`). `getLoopbackAddress()` is the machine's own address, so no traffic leaves the computer.
- `private static HttpClient client;` is declared but not yet created; the next method does it.

### Set-up and clean-up

`src/test/java/io/plainround/seatwave/balancer/stack/StackTest.java`, lines 46 to 54:

```java
  @BeforeAll
  static void connect() {
    client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  }

  @AfterAll
  static void disconnect() {
    client.close();
  }
```
- `@BeforeAll` marks a method that JUnit runs once before the first test; it must be `static`. `HttpClient.newBuilder().connectTimeout(TIMEOUT).build()` builds an HTTP client that gives up connecting after 10 seconds. All the tests share this one client.
- `@AfterAll` marks the method that runs once after the last test. `client.close()` releases the client's threads and connections.

### Test 1: both balancers see all three instances up

`src/test/java/io/plainround/seatwave/balancer/stack/StackTest.java`, lines 56 to 61:

```java
  @Test
  void bothBalancersSeeThreeInstancesUp() throws Exception {
    for (RuntimeApi api : new RuntimeApi[] {LB1_API, LB2_API}) {
      assertThat(api.showStat().servers("seatwave")).hasSize(3).allMatch(StatRow::isUp);
    }
  }
```
- `@Test` marks a test method. `throws Exception` says the method may fail with any exception; if it does, the test fails.
- `new RuntimeApi[] {LB1_API, LB2_API}` makes an array of the two clients, and `for (RuntimeApi api : ...)` repeats the body once for each.
- `api.showStat()` sends the command `show stat` to the balancer and parses the CSV reply into a table. `.servers("seatwave")` keeps only the rows of the backend `seatwave` and drops the `FRONTEND` and `BACKEND` summary rows.
- `assertThat(...).hasSize(3).allMatch(StatRow::isUp)` is two checks chained: the list has exactly 3 rows, and every row satisfies `StatRow::isUp`. `StatRow::isUp` is a **method reference**: a short way to say "call `isUp` on each row". `isUp()` is true when the status starts with `UP`, so `UP 1/3` (a server still up but failing checks) also counts as up.
- The test only looks at the backend `seatwave`. The other backend that runs checks, `seatwave_active_only`, is not asserted here, although `stack.sh up` waits for it.

### Test 2: any instance can answer for any hold

`src/test/java/io/plainround/seatwave/balancer/stack/StackTest.java`, lines 63 to 75:

```java
  @Test
  void readsAHoldThroughEveryInstanceWhicheverMadeIt() throws Exception {
    String holdId = freeHold();

    Set<String> readers = new HashSet<>();
    for (int round = 0; round < ROUNDS; round++) {
      HttpResponse<String> read = get(LB2 + "/v1/holds/" + holdId);
      assertThat(read.statusCode()).isEqualTo(200);
      readers.add(read.headers().firstValue("X-Served-By").orElseThrow());
    }

    assertThat(readers).containsExactlyInAnyOrder("seatwave-1", "seatwave-2", "seatwave-3");
  }
```
- `String holdId = freeHold();` makes a hold through `lb1` (the helper is below) and returns its identifier.
- `Set<String> readers = new HashSet<>();` makes an empty set of strings. `<String>` says what it holds (a **generic** type). `<>` lets Java work the type out.
- The `for` loop runs `ROUNDS` (30) times. Each time `get(LB2 + "/v1/holds/" + holdId)` sends `GET` for that hold to the **other** balancer, `lb2`. `+` joins texts. `assertThat(read.statusCode()).isEqualTo(200)` demands the status 200 every time.
- `read.headers().firstValue("X-Served-By").orElseThrow()` takes the value of the header `X-Served-By`, which names the instance that answered. `firstValue` returns an `Optional` (a value that may be missing); `orElseThrow()` fails the test if the header is missing. `readers.add(...)` records the name. A set keeps each name once.
- At the end `assertThat(readers).containsExactlyInAnyOrder("seatwave-1", "seatwave-2", "seatwave-3")` demands that all three instances, and no other name, answered. This proves the claim of the module: the hold was made by one instance (through the other balancer), and every instance can read it, because the hold lives in the shared database and not in any instance's memory.
- With round robin and nothing else sending requests, 30 reads give exactly 10 to each instance. The test is only as steady as that assumption: see "Weak spots" below.

### Test 3: one seat, one owner

`src/test/java/io/plainround/seatwave/balancer/stack/StackTest.java`, lines 77 to 88:

```java
  @Test
  void letsOneOfTwoInstancesWinOneSeat() throws Exception {
    String seat = "R-" + seatNumber();

    HttpResponse<String> first = hold(LB1, seat, "\"stack-" + UUID.randomUUID() + "\"");
    HttpResponse<String> second = hold(LB2, seat, "\"stack-" + UUID.randomUUID() + "\"");

    // The seat may already be held by a lab run; either way the second request must lose, and two
    // 201s for one seat would be the double booking the database's conditional UPDATE forbids.
    assertThat(first.statusCode()).isIn(201, 409);
    assertThat(second.statusCode()).isEqualTo(409);
  }
```
- `String seat = "R-" + seatNumber();` picks a seat in section R, such as `R-537`.
- `hold(LB1, seat, "\"stack-" + UUID.randomUUID() + "\"")` sends a hold request for that seat through `lb1`, with a new random `Idempotency-Key`. The key is written with quotes inside it (`"stack-<uuid>"`), because SeatWave's header is a quoted string. A second `hold(LB2, ...)` does the same through `lb2` with a different key. Two different keys mean two different customers' requests, not a retry.
- The comment above the checks gives the reasoning. The seat may already be held by an earlier lab run, so the first answer may be 201 (created) or 409 (conflict: seat taken), and `assertThat(first.statusCode()).isIn(201, 409)` accepts both. The second request must lose in both cases: `isEqualTo(409)`. Two 201s for one seat would be the double booking that the database's conditional `UPDATE` forbids.
- The two requests go to whichever instances the two balancers choose. The test does not assert that the instances differ.

### Test 4: a drained instance gets nothing new

`src/test/java/io/plainround/seatwave/balancer/stack/StackTest.java`, lines 90 to 106:

```java
  @Test
  void sendsNothingNewToADrainedInstance() throws Exception {
    LB1_API.setState("seatwave", "seatwave-3", AdminState.DRAIN);
    try {
      Set<String> servers = new HashSet<>();
      for (int round = 0; round < ROUNDS; round++) {
        servers.add(
            hold(LB1, "S-" + seatNumber(), "\"stack-" + UUID.randomUUID() + "\"")
                .headers()
                .firstValue("X-Served-By")
                .orElseThrow());
      }
      assertThat(servers).containsExactlyInAnyOrder("seatwave-1", "seatwave-2");
    } finally {
      LB1_API.setState("seatwave", "seatwave-3", AdminState.READY);
    }
  }
```
- `LB1_API.setState("seatwave", "seatwave-3", AdminState.DRAIN)` sends the runtime API command `set server seatwave/seatwave-3 state drain` to `lb1`. From now on that balancer sends `seatwave-3` no new request. (The three tracking backends follow; `seatwave_active_only` does not, but the test sends its requests to the PRODUCTION door.)
- `try { ... } finally { ... }`: the code in `finally` runs whatever happens in `try`, even if an assertion fails. It puts `seatwave-3` back to `READY`, so a failing test does not leave the lab drained.
- The loop sends 30 holds through `lb1`, each for a new random seat in section S, and records which instance answered (`.headers().firstValue("X-Served-By").orElseThrow()`; the chained calls are written one per line).
- `assertThat(servers).containsExactlyInAnyOrder("seatwave-1", "seatwave-2")` demands that only the two undrained instances answered, and both did.

### The helper `freeHold`

`src/test/java/io/plainround/seatwave/balancer/stack/StackTest.java`, lines 108 to 119:

```java
  private static String freeHold() throws Exception {
    // A lab run may have held many seats for 300 s, so seats are tried until one is free.
    for (int attempt = 0; attempt < ROUNDS; attempt++) {
      Matcher holdId =
          HOLD_ID.matcher(
              hold(LB1, "Q-" + seatNumber(), "\"stack-" + UUID.randomUUID() + "\"").body());
      if (holdId.find()) {
        return holdId.group(1);
      }
    }
    throw new AssertionError("no free seat in section Q within " + ROUNDS + " attempts");
  }
```
- The comment says why it loops: a lab run may have held many seats for 300 s, so seats are tried until one is free.
- Up to 30 times: make a hold on a random seat of section Q through `lb1`, then `HOLD_ID.matcher(body)` looks for the `"holdId":"..."` pattern in the reply's body. `holdId.find()` is true if the pattern occurs; `holdId.group(1)` is the part inside the parentheses, the identifier. A 409 reply has no `holdId`, so the loop tries another seat.
- If 30 seats in a row were taken, `throw new AssertionError(...)` fails the test with a clear message.

### The helpers `hold` and `get`

`src/test/java/io/plainround/seatwave/balancer/stack/StackTest.java`, lines 121 to 139:

```java
  private static HttpResponse<String> hold(String balancer, String seat, String key)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(balancer + "/v1/holds"))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", key)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"eventId\":1,\"seat\":\"" + seat + "\",\"customer\":\"stack-test\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> get(String url) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).build(),
        HttpResponse.BodyHandlers.ofString());
  }
```
- `hold(String balancer, String seat, String key)` sends one `POST /v1/holds`. `HttpRequest.newBuilder(URI.create(balancer + "/v1/holds"))` starts a request to that address. `.timeout(TIMEOUT)` bounds it. The two `.header(...)` calls set `Content-Type: application/json` and `Idempotency-Key: <key>`. `.POST(HttpRequest.BodyPublishers.ofString(...))` sets the method and the body, which is JSON built from the seat: `{"eventId":1,"seat":"<seat>","customer":"stack-test"}`. `.build()` finishes the request.
- `client.send(request, HttpResponse.BodyHandlers.ofString())` sends it and waits for the answer, and the body arrives as a text. The method returns the `HttpResponse<String>` so the caller can read the status, the headers and the body.
- `get(String url)` does the same for a plain `GET`, which needs no headers and no body.
- `static` on both: they belong to the class, and the tests call them directly.

### The helper `seatNumber`

`src/test/java/io/plainround/seatwave/balancer/stack/StackTest.java`, lines 141 to 145:

```java
  private static int seatNumber() {
    // A seat the labs are unlikely to hold: sections Q, R and S, any of their 1,000 seats.
    return 1 + Math.floorMod(UUID.randomUUID().hashCode(), SEATS_PER_SECTION);
  }
}
```
- The comment says what the number is for: a seat the labs are unlikely to hold, in sections Q, R and S, any of their 1,000 seats.
- `UUID.randomUUID().hashCode()` makes a random identifier and turns it into a whole number (which can be negative). `Math.floorMod(that, 1000)` is the remainder after dividing by 1,000, always between 0 and 999 (unlike `%`, it never gives a negative number). `1 + ...` shifts the range to 1 to 1,000.
- The closing brace ends the class.

### Weak spots I found while reading

These are findings for the author, not changes to the code.

- **The cross-instance claim of Test 3 is not guaranteed.** The two balancers choose their instances independently, and on a fresh stack each starts at the first instance. I restarted both balancers and sent one hold through each: both answers came from `seatwave-1` (`x-served-by: seatwave-1` twice). `scripts/stack.sh test` starts a fresh stack, and Test 3 is the first test JUnit ran in my runs, so in the usual path both requests may reach the same instance, and the test then proves the database rule only inside one instance. Adding an assertion on `X-Served-By`, or sending enough requests to cover all three, would prove the claim in the test's name.
- **Test 2 depends on slow start being over.** It expects 30 reads to hit all three instances. Right after an instance turns `UP`, `slowstart 10s` gives it a tiny share. I stopped `seatwave-3` until `lb2` showed it `DOWN`, started it, and as soon as `lb2` showed `UP` sent 30 reads with `curl`: `seatwave-1` 15, `seatwave-2` 15, `seatwave-3` 0. A test that started that early would fail. Gradle needs several seconds to start, and when I ran this test through Gradle at that moment it passed both times, so the risk shows only with a fast start after a restart.
- **`isUp` counts `UP 1/3`.** Test 1 would pass for a server that has just failed two checks.
- **Test 1 looks at one backend.** `seatwave_active_only` is not asserted.
- **The first answer of Test 3 may be a 409 from an old hold.** Then the cross-instance rule is not exercised at all in that run, and the test still passes. The comment says so.
- **The ports are copied.** 21000, 21010, 21005 and 21015 are written in the Java file and in `compose.yaml`. `InfrastructureRulesTest` checks `compose.yaml`'s ports against a range, not against this file.

## Try it yourself

I ran these on 11 November 2026 in a copy of the repository (Compose project `seatwave-balancer-notes`). Times differ on every run.

1. Run the normal test task and count the tests; none is `StackTest`:

```
./gradlew --console=plain clean check 2>&1 | grep -c PASSED
./gradlew --console=plain clean check 2>&1 | grep -c StackTest
```

Output:

```
98
0
```

2. Start the stack and run only the stack tests:

```
scripts/stack.sh up --run --yes 2>&1 | tail -1
./gradlew stackTest 2>&1 | grep -E 'PASSED|FAILED|BUILD'
```

Output:

```
stack is up after 5 s: 3 of 3 instances up on lb1 and lb2; 10.210.10.100 on keepalived-1
StackTest > letsOneOfTwoInstancesWinOneSeat() PASSED
StackTest > bothBalancersSeeThreeInstancesUp() PASSED
StackTest > readsAHoldThroughEveryInstanceWhicheverMadeIt() PASSED
StackTest > sendsNothingNewToADrainedInstance() PASSED
BUILD SUCCESSFUL in 2s
```

3. Stop the stack and run them again, to see what a failure looks like. The stack tests need the balancers:

```
scripts/stack.sh down --run --yes 2>&1 | tail -1
./gradlew stackTest 2>&1 | grep -E 'PASSED|FAILED|BUILD|Connect'
```

Output:

```
> Task :stackTest FAILED
StackTest > letsOneOfTwoInstancesWinOneSeat() FAILED
    java.net.ConnectException at StackTest.java:123
        Caused by: java.net.ConnectException at Utils.java:1070
StackTest > bothBalancersSeeThreeInstancesUp() FAILED
    java.net.ConnectException at StackTest.java:59
StackTest > readsAHoldThroughEveryInstanceWhicheverMadeIt() FAILED
    java.net.ConnectException at StackTest.java:123
        Caused by: java.net.ConnectException at Utils.java:1070
StackTest > sendsNothingNewToADrainedInstance() FAILED
    java.net.ConnectException at StackTest.java:92
BUILD FAILED in 2s
```

4. Let the script do it all in one go (up, test, down on every path):

```
scripts/stack.sh test --run --yes 2>&1 | grep -E 'PASSED|FAILED|BUILD|stack is'
```

Output:

```
stack is up after 5 s: 3 of 3 instances up on lb1 and lb2; 10.210.10.100 on keepalived-2
StackTest > letsOneOfTwoInstancesWinOneSeat() PASSED
StackTest > bothBalancersSeeThreeInstancesUp() PASSED
StackTest > readsAHoldThroughEveryInstanceWhicheverMadeIt() PASSED
StackTest > sendsNothingNewToADrainedInstance() PASSED
BUILD SUCCESSFUL in 2s
stack is down: no container, network or volume of seatwave-balancer-notes is left
```

5. Look at what the first two requests of a fresh stack get (the finding above). After `scripts/stack.sh up --run --yes`, send one hold through each balancer:

```
for p in 21000 21010; do curl -s -D - -o /dev/null -H 'Content-Type: application/json' -H "Idempotency-Key: \"fresh-$RANDOM\"" --data "{\"eventId\":1,\"seat\":\"R-$((RANDOM%1000+1))\",\"customer\":\"stack-test\"}" http://127.0.0.1:$p/v1/holds | grep -i '^x-served-by' | tr -d '\r'; done
```

Output:

```
x-served-by: seatwave-1
x-served-by: seatwave-1
```

## Mistakes people make

- **Running `stackTest` without a stack.** Every test fails with a connection error, and the reason is in the first lines of the report. `scripts/stack.sh test` starts the stack first.
- **Putting a slow, networked test in `check`.** It would make the normal build need Docker. The tag keeps it out.
- **Leaving the lab changed.** Test 4 drains an instance and puts it back in a `finally` block, so a failure does not leave it drained.
- **Assuming a test proves what its name says.** Read what each assertion actually checks. See "Weak spots".
- **Sharing data between runs.** Holds last 300 seconds, so tests that need a free seat pick random seats and try again.
- **Comparing against a header without a fallback.** `orElseThrow()` makes a missing header a clear failure instead of a `null`.

## Check yourself

1. How does the build keep `StackTest` out of `./gradlew check`?
   *The class has `@Tag("stack")`, `tasks.test` calls `excludeTags("stack")`, and the task `stackTest` calls `includeTags("stack")`.*
2. What does `containsExactlyInAnyOrder("seatwave-1", "seatwave-2", "seatwave-3")` demand?
   *The set has exactly these three names and no other, in any order.*
3. Why is the `finally` block in Test 4 important?
   *It puts the drained instance back to `READY` even if an assertion fails, so the next test and the labs find all three instances in service.*
4. Why does Test 3 accept 201 or 409 for the first answer?
   *The seat may already be held by an earlier lab run. In both cases the second request must be refused.*
5. What does `HOLD_ID` do?
   *It finds the 36-character hold identifier in a JSON reply by a regular expression, and its group 1 is the identifier itself.*

## Further reading

- JUnit 6 user guide (tests, `@BeforeAll`, `@AfterAll`, `@Tag`, tag filtering): https://docs.junit.org/current/user-guide/
- AssertJ core documentation (`assertThat`, `hasSize`, `allMatch`, `isIn`, `containsExactlyInAnyOrder`): https://assertj.github.io/doc/
- `java.net.http.HttpClient` (JDK 25): https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpClient.html
- Regular expressions in Java, `java.util.regex.Pattern` (JDK 25): https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/regex/Pattern.html
- Gradle: filtering tests by JUnit tag: https://docs.gradle.org/9.9.1/userguide/java_testing.html
