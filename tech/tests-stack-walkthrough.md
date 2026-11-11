# The stack tests: `StackTest`

## What it is

A **test** is a small program that checks one claim about another program and fails loudly if the claim is false. **JUnit** is the Java library that finds tests, runs them and reports which passed. **AssertJ** is a library of readable checks, such as `assertThat(x).isEqualTo(3)`. (`junit.md` and `assertj.md` explain both from zero. This note explains the one test class that needs the running Docker stack.)

Most tests of this repository run inside the Java program alone: `./gradlew check` runs 152 of them without Docker. `StackTest` is different. It talks over the network to the real balancers, and through them to the real instances and the real PostgreSQL database. So it can only run when the stack is up. A **tag** (a label on a test) keeps it out of the normal run: the class carries `@Tag("stack")`, `./gradlew check` excludes that tag, and a separate Gradle task, `stackTest`, runs only that tag. It has six tests.

## Why this repository uses it

The module makes claims that only the real stack can prove: three stateless instances answer for each other's holds, the database's one-owner rule holds when two different instances race for one seat, the Idempotency-Key rule holds on PostgreSQL itself, a drain at the balancer stops new requests to one instance, and both balancers see all three instances fully up. The other tests prove the rules inside the program (for example `JdbcHoldStoreTest` runs the same schema on H2). `StackTest` proves them on PostgreSQL, through HAProxy. It is the module's "does it really work" check.

Version 1.1.0 rewrote the class after the review of 1.0.0 found that several of its tests did not prove what their names said: the "one seat, one owner" test could send both requests to the same instance, the read test could fail while slow start was still running, the first test counted `UP 1/3` as up and looked at one backend, and the ports were copied into the Java file. The walkthrough says, for each test, what changed.

## Where it appears

| Path | What it does |
|---|---|
| `src/test/java/io/plainround/seatwave/balancer/stack/StackTest.java` | The six tests and their helpers. |
| `build.gradle.kts` | `tasks.test` excludes the tag `stack`; the task `stackTest` includes only it, and is never up to date (the stack is outside Gradle's view). See `gradle.md`. |
| `scripts/stack.sh` | `test` brings the stack up, runs `./gradlew stackTest`, and always brings it down. See `shell-scripts-walkthrough.md`. |
| `src/main/java/.../haproxy/RuntimeApi.java`, `StatRow.java`, `RuntimeCommand.java` | The client for HAProxy's runtime API that the test uses to read server states and to drain a server. See `java-haproxy-client-walkthrough.md`. |
| `compose.yaml` | Publishes the ports the test connects to; the test reads them from this file. |
| `haproxy/haproxy.cfg` | `slowstart 10s`, the ramp the read test waits for. |

## How it works underneath

1. **A test class is an ordinary Java class** with methods marked `@Test`. JUnit creates the class, calls each test method once (in an order it chooses, which is stable but not the order written), and records whether the method returned or threw an error. A failed AssertJ check throws an `AssertionError`.
2. **Gradle starts JUnit.** `./gradlew stackTest` compiles the code, finds classes with the tag `stack`, runs them in a new Java process, and prints one line per test.
3. **The test is a client.** It does what a fan's phone would do: it opens HTTP connections to `127.0.0.1` on the published ports of the balancers and sends requests. It never calls SeatWave's Java code.
4. **The ports come from `compose.yaml`.** Before the first test, the class reads `compose.yaml` and finds, in the blocks of `lb1` and `lb2`, the host ports published for the container ports 8080 (the production door) and 9999 (the runtime API). The ports live in one place.
5. **The path of a request.** Test, then HAProxy (`lb1` or `lb2`), then one of the three SeatWave instances (chosen by round robin), then PostgreSQL. The answer comes back the same way with two headers: `X-Served-By` (written by the instance) and `X-Balancer` (written by HAProxy).
6. **The runtime API is the second path.** The test connects to HAProxy's runtime API, sends text commands such as `show stat` and `set server ... state drain`, and reads the text back. Draining instances on purpose is how one test forces the two racing requests onto two different instances.
7. **Shared state.** Holds last 300 seconds. `scripts/lab.sh` gives every held seat back before and after each lab run, and each test draws its seats from a section of its own (P, Q, R, S, T, 1,000 seats each), so a seat drawn at random is free.

## Walkthrough of this repository's use

### The imports

```java
package io.plainround.seatwave.balancer.stack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.plainround.seatwave.balancer.haproxy.RuntimeApi;
import io.plainround.seatwave.balancer.haproxy.RuntimeCommand.AdminState;
import io.plainround.seatwave.balancer.haproxy.StatRow;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
```

- `package io.plainround.seatwave.balancer.stack;` says which folder-like group of classes this file belongs to (the folder `stack` under `src/test/java/io/plainround/seatwave/balancer/`).
- A line `import x.y.Z;` makes the class `Z` usable by its short name. `import static org.assertj.core.api.Assertions.assertThat;` imports a **static** method, so the file can write `assertThat(...)` instead of `Assertions.assertThat(...)`. `await` comes from Awaitility (`awaitility.md`), which waits until a condition holds.
- The three imports from `io.plainround.seatwave.balancer.haproxy` are this repository's own runtime-API client: `RuntimeApi` (the connection), `RuntimeCommand.AdminState` (the enumeration `READY`, `DRAIN`, `MAINT`) and `StatRow` (one row of the statistics table).
- `java.io.IOException` is the exception of a failed read or write. `java.net.*` brings the address types (`InetAddress`, `InetSocketAddress`), `URI` (an address as an object), and the HTTP client classes `HttpClient`, `HttpRequest` and `HttpResponse`. `StandardCharsets`, `Files` and `Path` read `compose.yaml`. `java.time.Duration` is a length of time. `HashSet`, `Set` and `List` are collections; a set holds each value once. `UUID` makes random identifiers. `CompletableFuture` is a promise of a result that is not ready yet, used to send two requests at the same moment. `Matcher` and `Pattern` are the tools of regular expressions (text patterns).
- The last four imports are JUnit's: `@AfterAll`, `@BeforeAll`, `@Tag` and `@Test` are **annotations**, labels that JUnit reads.

### The class and its constants

```java
/**
 * The module's claims against the running stack (scripts/stack.sh test): three stateless instances
 * behind both balancers, the database's one-owner rule across instances, the Idempotency-Key rule
 * on PostgreSQL itself, and a drain at the balancer that stops new requests to one instance. Never
 * part of ./gradlew check. Every port is read from compose.yaml, the file that publishes it.
 */
@Tag("stack")
class StackTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  // haproxy.cfg's slowstart: an instance carries its full share only this long after it came up.
  private static final long SLOW_START_SECONDS = 10;
  private static final Duration SETTLED_WITHIN = Duration.ofSeconds(60);
  private static final int ROUNDS = 30;
  // Module 1's sections hold 1,000 seats each.
  private static final int SEATS_PER_SECTION = 1_000;
  private static final List<String> BACKENDS = List.of("seatwave", "seatwave_active_only");
  private static final List<String> INSTANCES = List.of("seatwave-1", "seatwave-2", "seatwave-3");
  private static final Pattern HOLD_ID = Pattern.compile("\"holdId\":\"([0-9a-f-]{36})\"");
  private static String lb1;
  private static String lb2;
  private static RuntimeApi lb1Api;
  private static RuntimeApi lb2Api;
  private static HttpClient client;
```

- The `/** ... */` block is a **Javadoc comment**, documentation attached to the class. It lists the claims: three stateless instances behind both balancers, the database's one-owner rule across instances, the Idempotency-Key rule on PostgreSQL itself, and a drain at the balancer that stops new requests to one instance; "Never part of ./gradlew check"; and "Every port is read from compose.yaml, the file that publishes it."
- `@Tag("stack")` labels the whole class with the tag `stack`. The tag is what `build.gradle.kts` uses to include or exclude it.
- `class StackTest {` declares the class. It has no `public`, because JUnit does not need it.
- `private static final Duration TIMEOUT = Duration.ofSeconds(10);` is a **constant**: `private` (only this class sees it), `static` (one copy for the class, not one per test), `final` (never reassigned). It is the longest any request waits, so a stuck request fails the test instead of hanging the build.
- `SLOW_START_SECONDS = 10` is `haproxy.cfg`'s `slowstart 10s`, as the comment says: an instance that has just come up gets a small share of requests, growing to its full share over 10 seconds. `SETTLED_WITHIN` (60 s) is the most the read test waits for that to be over.
- `ROUNDS = 30` is how many requests the loops send. 30 divides by 3 and by 2, and it is enough for round robin to cover every instance.
- The comment and `SEATS_PER_SECTION = 1_000` say that Module 1's sections hold 1,000 seats each. (`1_000` is the number 1000; the underscore is only for reading.)
- `BACKENDS` lists the two backends that run their own health checks. `INSTANCES` lists the three instances.
- `HOLD_ID = Pattern.compile("\"holdId\":\"([0-9a-f-]{36})\"")` builds a **regular expression**. In plain text it is `"holdId":"` followed by exactly 36 characters that are digits, the letters `a` to `f`, or dashes (the shape of a UUID, such as `580ec039-fe3e-4ae2-a95c-6dd0c0cab0e4`), followed by `"`. The parentheses mark **group 1**, the part to extract: the hold's identifier. In Java source, each `"` inside the string is written `\"`.
- `lb1` and `lb2` will hold the base addresses of the two balancers' production doors, and `lb1Api` and `lb2Api` their runtime-API clients. They are not `final` constants any more: they are filled in from `compose.yaml` by the next method. `client` is the shared HTTP client.

### Set-up and clean-up

```java
  @BeforeAll
  static void connect() throws IOException {
    String compose = Files.readString(Path.of("compose.yaml"), StandardCharsets.UTF_8);
    lb1 = "http://127.0.0.1:" + publishedPort(compose, "lb1", 8080);
    lb2 = "http://127.0.0.1:" + publishedPort(compose, "lb2", 8080);
    lb1Api = api(publishedPort(compose, "lb1", 9999));
    lb2Api = api(publishedPort(compose, "lb2", 9999));
    client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  }

  @AfterAll
  static void disconnect() {
    client.close();
  }
```

- `@BeforeAll` marks a method that JUnit runs once before the first test; it must be `static`.
- It reads `compose.yaml` as text. `publishedPort(compose, "lb1", 8080)` (a helper at the end) finds the host port that `lb1` publishes for its container port 8080; `"http://127.0.0.1:" + port` is the base address. The same for `lb2`, and for port 9999, the runtime API, which `api(port)` turns into a `RuntimeApi` client.
- `HttpClient.newBuilder().connectTimeout(TIMEOUT).build()` builds an HTTP client that gives up connecting after 10 seconds. All the tests share this one client.
- `@AfterAll` marks the method that runs once after the last test. `client.close()` releases the client's threads and connections.

In version 1.0.0 the four ports were written as numbers in this file and in `compose.yaml`, so a change to one would quietly break the other.

### Test 1: both balancers see all three instances fully up, in both checked backends

```java
  @Test
  void bothBalancersSeeThreeInstancesFullyUpInBothCheckedBackends() throws Exception {
    for (RuntimeApi api : List.of(lb1Api, lb2Api)) {
      for (String backend : BACKENDS) {
        assertThat(api.showStat().servers(backend))
            .as(backend)
            .hasSize(INSTANCES.size())
            .allMatch(StatRow::isFullyUp);
      }
    }
  }
```

- `@Test` marks a test method. `throws Exception` says the method may fail with any exception; if it does, the test fails.
- The two `for` loops visit each balancer (`List.of(lb1Api, lb2Api)`) and each backend with its own checks (`seatwave` and `seatwave_active_only`): four combinations.
- `api.showStat()` sends the command `show stat` to the balancer and parses the CSV reply into a table. `.servers(backend)` keeps only the rows of that backend and drops the `FRONTEND` and `BACKEND` summary rows.
- `.as(backend)` names the backend in a failure message. `.hasSize(INSTANCES.size())` requires 3 rows, and `.allMatch(StatRow::isFullyUp)` requires that every row is fully up. `StatRow::isFullyUp` is a **method reference**: a short way to say "call `isFullyUp` on each row". `isFullyUp()` is true only for the plain status `UP`, so a server at `UP 1/3` (up, but one check has just failed) does not pass.

In version 1.0.0 this test was named `bothBalancersSeeThreeInstancesUp`, looked only at the backend `seatwave`, and used `isUp`, which counts `UP 1/3`.

### Test 2: any instance can answer for any hold

```java
  @Test
  void readsAHoldThroughEveryInstanceWhicheverMadeIt() throws Exception {
    // Round robin gives every instance its share only once slow start is over everywhere.
    await().atMost(SETTLED_WITHIN).until(() -> isSettled(lb2Api));
    String holdId = freeHold();

    Set<String> readers = new HashSet<>();
    for (int round = 0; round < ROUNDS; round++) {
      HttpResponse<String> read = get(lb2 + "/v1/holds/" + holdId);
      assertThat(read.statusCode()).isEqualTo(200);
      readers.add(read.headers().firstValue("X-Served-By").orElseThrow());
    }

    assertThat(readers).containsExactlyInAnyOrderElementsOf(INSTANCES);
  }
```

- First it waits. The comment explains why: round robin gives every instance its share only once slow start is over everywhere. Right after a restart, an instance that has just turned `UP` gets a tiny share, and 30 reads might not reach it at all. `await().atMost(SETTLED_WITHIN).until(() -> isSettled(lb2Api))` asks `lb2` again and again, for up to 60 s, until every instance has been fully up for at least 10 s (the helper is below). In version 1.0.0 there was no wait, and a run started just after a restart could fail.
- `String holdId = freeHold();` makes a hold through `lb1` (the helper is below) and returns its identifier.
- `Set<String> readers = new HashSet<>();` makes an empty set of strings. `<String>` says what it holds (a **generic** type). `<>` lets Java work the type out.
- The `for` loop runs `ROUNDS` (30) times. Each time `get(lb2 + "/v1/holds/" + holdId)` sends `GET` for that hold to the **other** balancer, `lb2`. `+` joins texts. `assertThat(read.statusCode()).isEqualTo(200)` demands the status 200 every time.
- `read.headers().firstValue("X-Served-By").orElseThrow()` takes the value of the header `X-Served-By`, which names the instance that answered. `firstValue` returns an `Optional` (a value that may be missing); `orElseThrow()` fails the test if the header is missing. `readers.add(...)` records the name. A set keeps each name once.
- At the end `assertThat(readers).containsExactlyInAnyOrderElementsOf(INSTANCES)` demands that all three instances, and no other name, answered. This proves the claim of the module: the hold was made through one balancer and one instance, and every instance can read it, because the hold lives in the shared database and not in any instance's memory.

### Test 3: one seat, one owner, across two instances

```java
  @Test
  void letsOneOfTwoInstancesWinOneSeat() throws Exception {
    // lb1 may send only to seatwave-1, lb2 only to seatwave-2: the two requests race on two
    // different instances, and only the database can keep the seat to one owner.
    String seat = freeSeat("R");
    try {
      drainAllBut(lb1Api, "seatwave-1");
      drainAllBut(lb2Api, "seatwave-2");
      CompletableFuture<HttpResponse<String>> first =
          holdAsync(lb1, seat, "\"stack-" + UUID.randomUUID() + "\"");
      CompletableFuture<HttpResponse<String>> second =
          holdAsync(lb2, seat, "\"stack-" + UUID.randomUUID() + "\"");
      List<HttpResponse<String>> both = List.of(first.join(), second.join());

      assertThat(both)
          .extracting(response -> response.headers().firstValue("X-Served-By").orElseThrow())
          .containsExactly("seatwave-1", "seatwave-2");
      assertThat(both).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 409);
    } finally {
      readyEverywhere(lb1Api);
      readyEverywhere(lb2Api);
    }
  }
```

This is the module's central rule, and the comment says how the test makes it a real cross-instance race: `lb1` may send only to `seatwave-1`, and `lb2` only to `seatwave-2`.

- `String seat = freeSeat("R");` picks a random free seat in section R, such as `R-537`.
- `drainAllBut(lb1Api, "seatwave-1")` sets every instance except `seatwave-1` to `DRAIN` on `lb1`; `drainAllBut(lb2Api, "seatwave-2")` does the same on `lb2` for `seatwave-2`. Now each balancer has exactly one instance that takes new requests, and they are different.
- `holdAsync(lb1, seat, ...)` and `holdAsync(lb2, seat, ...)` send two hold requests for the same seat at the same moment, each with its own random `Idempotency-Key` (two customers, not a retry). The key is written with quotes inside it (`"stack-<uuid>"`), because SeatWave's header is a quoted string. `holdAsync` returns at once with a `CompletableFuture`; `join()` waits for each answer.
- The first check: the two answers came from `seatwave-1` and `seatwave-2`, in that order (`extracting` takes the header from each response). So the race really ran on two instances, and only the shared database could keep the seat to one owner.
- The second check: the two statuses are one 201 (created) and one 409 (conflict: the seat is taken), in either order.
- `try { ... } finally { ... }`: the code in `finally` runs whatever happens in `try`, even if an assertion fails. It sets every instance back to `READY` on both balancers.

In version 1.0.0 this test sent the two requests one after the other, let the balancers choose, and accepted a first answer of 409 from an old hold. On a fresh stack both balancers started at the same instance, so the test usually proved the rule inside one instance only.

### Test 4: two copies of one request make one hold, on PostgreSQL

```java
  @Test
  void answersTwoRacingCopiesOfOneRequestWithOneHoldOnPostgresql() throws Exception {
    // The same key and body through both balancers at once: one seat, so the second request waits
    // for the first one's row lock and then finds its hold (the re-read after a lost race).
    String seat = freeSeat("S");
    String key = "\"stack-" + UUID.randomUUID() + "\"";

    CompletableFuture<HttpResponse<String>> first = holdAsync(lb1, seat, key);
    CompletableFuture<HttpResponse<String>> second = holdAsync(lb2, seat, key);

    assertThat(List.of(first.join(), second.join()))
        .allMatch(response -> response.statusCode() == 201)
        .extracting(response -> holdIdOf(response.body()))
        .containsOnly(holdIdOf(first.join().body()));
  }
```

New in 1.1.0. The same key and the same body, through both balancers at the same moment: a client's retry racing its original. The comment explains what happens inside: both requests want one seat, so the second waits for the first one's **row lock** (the database lets only one transaction change a row at a time) and then finds the hold the first made, the "re-read after a lost race" path of `JdbcHoldStore`.

- `assertThat(List.of(first.join(), second.join()))` checks both answers: `allMatch(... == 201)`, both are 201; `extracting(response -> holdIdOf(response.body()))` takes the hold id out of each body; `containsOnly(holdIdOf(first.join().body()))` requires that every id equals the first one's. One hold, answered twice.

### Test 5: a key reused for another seat is refused, and that seat stays free

```java
  @Test
  void refusesARacingKeyReusedForAnotherSeatAndKeepsThatSeatFreeOnPostgresql() throws Exception {
    // One key, two seats: no row lock orders the two, so the loser meets the unique key (SQLState
    // 23505) or the winner's row, and is refused; its seat is rolled back and stays free.
    int number = seatNumber();
    String seatOne = "P-" + number;
    // The next seat of the section, so the two always differ.
    String seatTwo = "P-" + (number % SEATS_PER_SECTION + 1);
    String key = "\"stack-" + UUID.randomUUID() + "\"";

    CompletableFuture<HttpResponse<String>> first = holdAsync(lb1, seatOne, key);
    CompletableFuture<HttpResponse<String>> second = holdAsync(lb2, seatTwo, key);
    HttpResponse<String> one = first.join();
    HttpResponse<String> two = second.join();

    assertThat(List.of(one.statusCode(), two.statusCode())).containsExactlyInAnyOrder(201, 422);
    String loserSeat = one.statusCode() == 422 ? seatOne : seatTwo;
    assertThat(hold(lb1, loserSeat, "\"stack-" + UUID.randomUUID() + "\"").statusCode())
        .isEqualTo(201);
  }
```

New in 1.1.0. One key, two different seats, at the same moment. The comment explains it: no row lock orders the two requests, because they want different seats, so the loser either meets the database's **unique key** on the idempotency key (SQLState `23505`, "unique violation") or finds the winner's row; either way it is refused, and its transaction is rolled back, which frees its seat.

- `seatOne` is a random seat of section P. `seatTwo` is the next seat of the section, so the two always differ: `number % SEATS_PER_SECTION + 1` turns 537 into 538 and 1,000 into 1.
- The two requests go through `lb1` and `lb2` at once. `join()` waits for each.
- `containsExactlyInAnyOrder(201, 422)`: one is created, the other gets 422 (unprocessable: this key was already used for a different request).
- `loserSeat` is the seat of the request that got 422. A new hold for that seat, with a new key, must get 201: the loser's seat really was given back.

`JdbcHoldStoreTest` forces the same race on H2 with a barrier; this test runs it on PostgreSQL through the real stack, where it is left to timing which path the loser takes.

### Test 6: a drained instance gets nothing new

```java
  @Test
  void sendsNothingNewToADrainedInstance() throws Exception {
    lb1Api.setState("seatwave", "seatwave-3", AdminState.DRAIN);
    try {
      Set<String> servers = new HashSet<>();
      for (int round = 0; round < ROUNDS; round++) {
        servers.add(
            hold(lb1, freeSeat("T"), "\"stack-" + UUID.randomUUID() + "\"")
                .headers()
                .firstValue("X-Served-By")
                .orElseThrow());
      }
      assertThat(servers).containsExactlyInAnyOrder("seatwave-1", "seatwave-2");
    } finally {
      lb1Api.setState("seatwave", "seatwave-3", AdminState.READY);
    }
  }
```

- `lb1Api.setState("seatwave", "seatwave-3", AdminState.DRAIN)` sends the runtime API command `set server seatwave/seatwave-3 state drain` to `lb1`. From now on that balancer sends `seatwave-3` no new request. (The three tracking backends follow; `seatwave_active_only` does not, but the test sends its requests to the production door.)
- The `finally` block puts `seatwave-3` back to `READY`, so a failing test does not leave the lab drained.
- The loop sends 30 holds through `lb1`, each for a new random seat in section T (`freeSeat("T")`), and records which instance answered (`.headers().firstValue("X-Served-By").orElseThrow()`; the chained calls are written one per line).
- `assertThat(servers).containsExactlyInAnyOrder("seatwave-1", "seatwave-2")` demands that only the two undrained instances answered, and both did.

### The helpers `isSettled`, `drainAllBut` and `readyEverywhere`

```java
  private static boolean isSettled(RuntimeApi api) throws IOException {
    return api.showStat().servers("seatwave").stream()
        .allMatch(row -> row.isFullyUp() && row.secondsSinceChange() >= SLOW_START_SECONDS);
  }

  private static void drainAllBut(RuntimeApi api, String kept) throws IOException {
    for (String instance : INSTANCES) {
      if (!instance.equals(kept)) {
        api.setState("seatwave", instance, AdminState.DRAIN);
      }
    }
  }

  private static void readyEverywhere(RuntimeApi api) throws IOException {
    for (String instance : INSTANCES) {
      api.setState("seatwave", instance, AdminState.READY);
    }
  }
```

- `isSettled(api)` reads the balancer's table, takes the rows of the backend `seatwave`, and turns them into a **stream** (a pipeline over the items). `allMatch` is true when every row is fully up and its `secondsSinceChange()` (HAProxy's `lastchg` column: seconds since the server's status last changed) is at least 10. That means slow start is over for every instance.
- `drainAllBut(api, kept)` sets every instance except `kept` to `DRAIN` in the backend `seatwave`. `!instance.equals(kept)` means "is not the kept one".
- `readyEverywhere(api)` sets all three back to `READY`.

### The helpers `freeHold`, `freeSeat` and `holdIdOf`

```java
  private static String freeHold() throws Exception {
    // A seat of section Q that another test or a manual hold took is skipped: seats are tried until
    // one is free.
    for (int attempt = 0; attempt < ROUNDS; attempt++) {
      Matcher holdId =
          HOLD_ID.matcher(
              hold(lb1, "Q-" + seatNumber(), "\"stack-" + UUID.randomUUID() + "\"").body());
      if (holdId.find()) {
        return holdId.group(1);
      }
    }
    throw new AssertionError("no free seat in section Q within " + ROUNDS + " attempts");
  }

  private static String freeSeat(String section) {
    // scripts/lab.sh gives every seat back after each lab run, and each test here has a section of
    // its own (P to T), so a seat drawn at random is free.
    return section + "-" + seatNumber();
  }

  private static String holdIdOf(String body) {
    Matcher holdId = HOLD_ID.matcher(body);
    assertThat(holdId.find()).as("a hold id in %s", body).isTrue();
    return holdId.group(1);
  }
```

- `freeHold()`: up to 30 times, make a hold on a random seat of section Q through `lb1`; `HOLD_ID.matcher(body)` looks for the `"holdId":"..."` pattern in the reply's body. `holdId.find()` is true if the pattern occurs; `holdId.group(1)` is the part inside the parentheses, the identifier. A 409 reply has no `holdId`, so the loop tries another seat. If 30 seats in a row were taken, `throw new AssertionError(...)` fails the test with a clear message. Its comment says why a loop is still there: since 1.1.0 `scripts/lab.sh` gives the seats back after every run, so only another test or a hold made by hand can have taken a seat of section Q; the loop skips such a seat.
- `freeSeat(section)` returns the section and a random seat number, such as `T-42`. The comment gives the reason this is enough: the labs give every seat back, and each test has a section of its own, P to T.
- `holdIdOf(body)` extracts the hold id from a reply and fails, naming the body, if there is none (`as("a hold id in %s", body)`; `%s` is replaced by the body).

### The helpers `hold`, `holdAsync`, `holdRequest` and `get`

```java
  private static HttpResponse<String> hold(String balancer, String seat, String key)
      throws Exception {
    return client.send(holdRequest(balancer, seat, key), HttpResponse.BodyHandlers.ofString());
  }

  private static CompletableFuture<HttpResponse<String>> holdAsync(
      String balancer, String seat, String key) {
    return client.sendAsync(holdRequest(balancer, seat, key), HttpResponse.BodyHandlers.ofString());
  }

  private static HttpRequest holdRequest(String balancer, String seat, String key) {
    return HttpRequest.newBuilder(URI.create(balancer + "/v1/holds"))
        .timeout(TIMEOUT)
        .header("Content-Type", "application/json")
        .header("Idempotency-Key", key)
        .POST(
            HttpRequest.BodyPublishers.ofString(
                "{\"eventId\":1,\"seat\":\"" + seat + "\",\"customer\":\"stack-test\"}"))
        .build();
  }

  private static HttpResponse<String> get(String url) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).build(),
        HttpResponse.BodyHandlers.ofString());
  }
```

- `holdRequest(balancer, seat, key)` builds one `POST /v1/holds`. `HttpRequest.newBuilder(URI.create(balancer + "/v1/holds"))` starts a request to that address. `.timeout(TIMEOUT)` bounds it. The two `.header(...)` calls set `Content-Type: application/json` and `Idempotency-Key: <key>`. `.POST(HttpRequest.BodyPublishers.ofString(...))` sets the method and the body, which is JSON built from the seat: `{"eventId":1,"seat":"<seat>","customer":"stack-test"}`. `.build()` finishes the request.
- `hold(...)` sends it with `client.send(...)` and waits for the answer; the body arrives as text (`BodyHandlers.ofString()`). `holdAsync(...)` sends it with `client.sendAsync(...)` and returns at once with a `CompletableFuture`, so a test can start two requests before either answer arrives.
- `get(url)` sends a plain `GET`, which needs no headers and no body.

### The helpers `seatNumber`, `api` and `publishedPort`

```java
  private static int seatNumber() {
    // Any of a section's 1,000 seats.
    return 1 + Math.floorMod(UUID.randomUUID().hashCode(), SEATS_PER_SECTION);
  }

  private static RuntimeApi api(int port) {
    return new RuntimeApi(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), TIMEOUT);
  }

  private static int publishedPort(String compose, String service, int containerPort) {
    int start = compose.indexOf("\n  " + service + ":\n");
    int end = compose.indexOf("\n\n", start + 1);
    Matcher published =
        Pattern.compile("\"127\\.0\\.0\\.1:([0-9]+):" + containerPort + "\"")
            .matcher(compose.substring(start, end));
    assertThat(published.find()).as("%s publishes %d", service, containerPort).isTrue();
    return Integer.parseInt(published.group(1));
  }
}
```

- `seatNumber()`: `UUID.randomUUID().hashCode()` makes a random identifier and turns it into a whole number (which can be negative). `Math.floorMod(that, 1000)` is the remainder after dividing by 1,000, always between 0 and 999 (unlike `%`, it never gives a negative number). `1 + ...` shifts the range to 1 to 1,000.
- `api(port)` makes a `RuntimeApi` for `127.0.0.1` and that port. `getLoopbackAddress()` is the machine's own address, so no traffic leaves the computer.
- `publishedPort(compose, service, containerPort)` finds the service's block in `compose.yaml`, from the line `  <service>:` to the next blank line, and looks inside it for `"127.0.0.1:<host port>:<container port>"`. In the pattern, `\\.` is a literal dot and `([0-9]+)` captures the host port. `assertThat(published.find()).as(...).isTrue()` fails with a message such as `lb1 publishes 8080` if the line is missing. `Integer.parseInt` turns the captured text into a number. The closing brace ends the class.

### Weak spots I found while reading

These are findings for the author, not changes to the code.

- **A failure inside `finally` can hide the real one.** With no stack running, `letsOneOfTwoInstancesWinOneSeat` reports a `ConnectException` at line 194, inside `readyEverywhere`, not at line 106 where the first call failed: the exception thrown in `finally` replaces the one from `try`. The test still fails, but the report points at the clean-up.
- **Test 5 leaves to timing which path the loser takes.** Both paths end in 422, so the test passes either way, but it cannot say which of them PostgreSQL ran.
- **An old comment.** `freeHold`'s comment says a lab run may have held seats for 300 s, while `freeSeat`'s comment says the labs give every seat back.

## Try it yourself

Times differ on every run. Steps 2 and 4 need Docker and the stack; their output below is from the module's 1.1.0 runs, not from a run made for this note. Step 3 I ran for this note, in a copy, with no stack running.

1. Run the normal test task and count the tests; none is `StackTest`:

```
./gradlew --console=plain cleanTest test 2>&1 | grep -c PASSED
./gradlew --console=plain cleanTest test 2>&1 | grep -c StackTest
```

Output:

```
152
0
```

2. Start the stack and run only the stack tests:

```
scripts/stack.sh up --run --yes 2>&1 | tail -1
./gradlew stackTest 2>&1 | grep -E 'PASSED|FAILED|BUILD'
```

Output (the `stack is up` line from the module's `s-up.txt`, the test lines from its `s-stack-test.txt`):

```
stack is up after 4 s: 3 of 3 instances up on lb1 and lb2; 10.210.10.100 on keepalived-1
StackTest > letsOneOfTwoInstancesWinOneSeat() PASSED
StackTest > answersTwoRacingCopiesOfOneRequestWithOneHoldOnPostgresql() PASSED
StackTest > refusesARacingKeyReusedForAnotherSeatAndKeepsThatSeatFreeOnPostgresql() PASSED
StackTest > readsAHoldThroughEveryInstanceWhicheverMadeIt() PASSED
StackTest > bothBalancersSeeThreeInstancesFullyUpInBothCheckedBackends() PASSED
StackTest > sendsNothingNewToADrainedInstance() PASSED
BUILD SUCCESSFUL in 16s
```

3. Run them with no stack, to see what a failure looks like. The stack tests need the balancers:

```
./gradlew stackTest --console=plain 2>&1 | grep -E 'PASSED|FAILED|BUILD|Exception at'
```

Output:

```
> Task :stackTest FAILED
StackTest > letsOneOfTwoInstancesWinOneSeat() FAILED
    java.net.ConnectException at StackTest.java:194
StackTest > answersTwoRacingCopiesOfOneRequestWithOneHoldOnPostgresql() FAILED
    java.util.concurrent.CompletionException at CompletableFuture.java:323
        Caused by: java.net.ConnectException at Utils.java:1070
            Caused by: java.nio.channels.ClosedChannelException at SocketChannelImpl.java:204
StackTest > refusesARacingKeyReusedForAnotherSeatAndKeepsThatSeatFreeOnPostgresql() FAILED
    java.util.concurrent.CompletionException at CompletableFuture.java:323
        Caused by: java.net.ConnectException at Utils.java:1070
            Caused by: java.nio.channels.ClosedChannelException at SocketChannelImpl.java:204
StackTest > readsAHoldThroughEveryInstanceWhicheverMadeIt() FAILED
    java.net.ConnectException at StackTest.java:180
StackTest > bothBalancersSeeThreeInstancesFullyUpInBothCheckedBackends() FAILED
    java.net.ConnectException at StackTest.java:76
StackTest > sendsNothingNewToADrainedInstance() FAILED
    java.net.ConnectException at StackTest.java:163
BUILD FAILED in 1s
```

The line numbers point at the first call that needed the network in each test, except in `letsOneOfTwoInstancesWinOneSeat`, where it is the clean-up in `finally` (see "Weak spots"). The two racing tests show the error wrapped in a `CompletionException`, because their requests ran asynchronously.

4. Let the script do it all in one go (up, test, down on every path):

```
scripts/stack.sh test --run --yes 2>&1 | grep -E 'PASSED|FAILED|BUILD|stack is'
```

The six `PASSED` lines of step 2 appear between the `stack is up` line and a `stack is down` line.

## Mistakes people make

- **Running `stackTest` without a stack.** Every test fails with a connection error, as step 3 shows. `scripts/stack.sh test` starts the stack first.
- **Putting a slow, networked test in `check`.** It would make the normal build need Docker. The tag keeps it out.
- **Leaving the lab changed.** Tests 3 and 6 drain instances and put them back in a `finally` block, so a failure does not leave them drained.
- **A race test that may not race.** Two requests sent through two balancers can still reach one instance. Test 3 drains the others so each balancer has one instance, and checks `X-Served-By`.
- **Testing during slow start.** An instance that has just come up gets a small share. Test 2 waits until every instance has been up for 10 s.
- **Copying configuration into tests.** Ports written twice drift apart. The test reads them from `compose.yaml`.
- **"Up" is not "fully up".** `UP 1/3` is a server already failing checks. Test 1 uses `isFullyUp`.
- **Comparing against a header without a fallback.** `orElseThrow()` makes a missing header a clear failure instead of a `null`.

## Check yourself

1. How does the build keep `StackTest` out of `./gradlew check`?
   *The class has `@Tag("stack")`, `tasks.test` calls `excludeTags("stack")`, and the task `stackTest` calls `includeTags("stack")`.*
2. How does Test 3 make sure the two racing requests run on two different instances?
   *It drains every instance but `seatwave-1` on `lb1` and every instance but `seatwave-2` on `lb2`, sends the two requests at once, and checks that `X-Served-By` names `seatwave-1` and `seatwave-2`.*
3. Why does Test 2 wait with `isSettled` before it reads?
   *During slow start a newly up instance gets only a small share, so 30 reads might miss it; the test waits until every instance has been fully up for 10 s.*
4. Why does Test 5 use two different seats with one key?
   *With one seat, a row lock would order the requests; with two, both reach the insert of the hold, and the unique key on the idempotency key refuses the second, which must answer 422 and give its seat back.*
5. Where do the test's ports come from?
   *From `compose.yaml`: `publishedPort` finds the host port each balancer publishes for container ports 8080 and 9999.*

## Further reading

- JUnit 6 user guide (tests, `@BeforeAll`, `@AfterAll`, `@Tag`, tag filtering): https://docs.junit.org/current/user-guide/
- AssertJ core documentation (`assertThat`, `hasSize`, `allMatch`, `containsExactlyInAnyOrder`, `extracting`): https://assertj.github.io/doc/
- Awaitility usage: https://github.com/awaitility/awaitility/wiki/Usage
- `java.net.http.HttpClient` (JDK 25): https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpClient.html
- Regular expressions in Java, `java.util.regex.Pattern` (JDK 25): https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/regex/Pattern.html
- HAProxy 3.4 management guide, `show stat` (the `lastchg` column) and `set server ... state`: https://docs.haproxy.org/3.4/management.html
- HAProxy 3.4 configuration manual, `slowstart`: https://docs.haproxy.org/3.4/configuration.html
- PostgreSQL 18 documentation, explicit locking (row locks): https://www.postgresql.org/docs/18/explicit-locking.html
- Gradle: filtering tests by JUnit tag: https://docs.gradle.org/9.9.1/userguide/java_testing.html
