# AssertJ 3.27.7

## What it is

An **assertion** is a check inside a test: "this value must be that value". AssertJ is a library of assertions with a **fluent** style: you write `assertThat(actual)` and then chain method calls that read like a sentence, each returning the object so the next call can follow. When a check fails, AssertJ prints the expected and the actual value in a clear message.

## Why this repository uses it

Tests are the requirements here, so they should read like requirements. `assertThat(response.statusCode()).isEqualTo(201)` is easier to read than a hand-written `if` with a `throw`. In a test of a balancer and the instances behind it the message matters, because a failure often shows a status code, a list of server names, a refusal text or the exact order of calls, and that value is the clue. The README lists AssertJ in its table of libraries for this reason: readable assertions with clear failure messages.

## Where it appears

| Path | What it does with AssertJ |
| --- | --- |
| `gradle/libs.versions.toml` | `assertj = "3.27.7"` and `assertj-core`. |
| `build.gradle.kts` | `testImplementation(libs.assertj.core)`. |
| `gradle.lockfile` | Locks `assertj-core` and its dependency `byte-buddy` 1.18.3 (`dependency-locking.md`). |
| `src/test/java/**` | `assertThat` is imported in 21 files and `assertThatThrownBy` in 9 files. There are 301 `assertThat(` calls and 19 `assertThatThrownBy(` calls in all. |

## How it works underneath

1. `assertThat(x)` looks at the type of `x` and returns the matching **assertion object**: one for a `String`, one for a `List`, one for a `Duration`, one for a `boolean`, one for an `Optional`, one for any other object. Each has the methods that make sense for that type.
2. Each method (`isEqualTo`, `contains`, `hasSize`) checks the value. If the check holds, it returns the same assertion object, so another call can follow. If not, it throws an `AssertionError` with a message, and JUnit marks the test as failed (`junit.md`).
3. For exceptions, `assertThatThrownBy(() -> code)` runs the code in a try block, catches whatever is thrown and lets you check its type and message. The `() -> code` part is a **lambda**: a small unnamed function that is handed over and run later, inside AssertJ, so AssertJ can catch what it throws.
4. Because the checks are chained on one `assertThat`, several conditions on the same value come in one statement.
5. An assertion runs once, on the thread that calls it, at that moment. It does not wait. A test of a network program therefore waits in a different way: the call to the server blocks until the answer comes (`client.send(...)`), or Awaitility polls for a state (`awaitility.md`), and only then does the assertion look at it.

## Walkthrough of this repository's use

The counts in this section come from `grep -rho` over `src/test` and count each method name; a few names, such as `contains`, also match Java's own `String.contains`, so I give no total for them.

### `isEqualTo` for numbers, text, instants and records

```java
  @Test
  void grantsAFreeSeatForThreeHundredSeconds() throws Exception {
    GrantedHold granted = granted(store.hold(command("\"k-1\"", SHA_A, "A-7")));

    assertThat(granted.isReplay()).isFalse();
    assertThat(granted.hold().seat()).isEqualTo("A-7");
    assertThat(granted.hold().expiresAt()).isEqualTo(START.plusSeconds(300));
  }

  @Test
  void refusesASeatAnotherActiveHoldOwns() throws Exception {
    store.hold(command("\"k-1\"", SHA_A, "A-7"));

    Result<GrantedHold, HoldRefusal> second = store.hold(command("\"k-2\"", SHA_B, "A-7"));

    assertThat(second).isEqualTo(Result.error(new HoldRefusal.SeatTaken()));
  }
```

`isEqualTo` compares with `equals`. It is the most used method here (127 calls). `assertThat(granted.hold().seat()).isEqualTo("A-7")` compares two texts, character by character. `isFalse()` checks a `boolean`. `granted.hold().expiresAt()` is an `Instant` (a point in time), and `START.plusSeconds(300)` is another; `isEqualTo` compares the two points. Java **records** get a correct `equals` automatically, so two records with the same contents are equal. The last test relies on it: `assertThat(second).isEqualTo(Result.error(new HoldRefusal.SeatTaken()))` compares a `Result` that holds a refusal with a freshly made one (`result4j.md`).

### Lists: `containsExactly`, `isEmpty`

```java
  @Test
  void collectsOneProblemPerMissingOrInvalidKey() {
    Properties properties = new Properties();
    properties.setProperty("count", "seven");
    properties.setProperty("range", "99");
    properties.setProperty("word", "Upper");
    properties.setProperty("list", "one, Two");
    SettingsReader reader = new SettingsReader(properties);

    reader.intBetween("count", 1, 10);
    reader.intBetween("range", 1, 10);
    reader.textMatching("word", WORD);
    reader.listMatching("list", WORD);
    reader.text("absent");
    reader.addProblem("a and b must differ");

    assertThat(reader.problems())
        .containsExactly(
            "count must be a whole number, but was seven",
            "range must be from 1 to 10, but was 99",
            "word must match [a-z]+, but was Upper",
            "list has the item 'Two', which must match [a-z]+",
            "absent is missing",
            "a and b must differ");
  }

  @Test
  void readsValidValues() {
    Properties properties = new Properties();
    properties.setProperty("waitMillis", "250");
    properties.setProperty("names", "alpha, beta");
    SettingsReader reader = new SettingsReader(properties);

    assertThat(reader.millisBetween("waitMillis", 1, 1_000)).isEqualTo(Duration.ofMillis(250));
    assertThat(reader.listMatching("names", WORD)).containsExactly("alpha", "beta");
    assertThat(reader.problems()).isEmpty();
  }
```

- `containsExactly(...)` on a list checks the items **and their order**: the reader must report its six problems in exactly this order, one per missing or invalid key. That order is part of the promise "one run lists every problem", so the test pins it.
- `isEmpty()` checks that a list has no item. `assertThat(reader.problems()).isEmpty()` is how the second test says "no problem was found".
- The number `1_000` is a Java number with an underscore for readability; the underscore changes nothing.

```java
  @Test
  void readsTheServersOfABackendByColumnName() throws IOException {
    StatTable table = StatTable.parse(capturedAnswer());

    assertThat(table.servers("seatwave"))
        .extracting(StatRow::server)
        .containsExactly("seatwave-1", "seatwave-2", "seatwave-3");
    assertThat(table.server("seatwave", "seatwave-2").orElseThrow().isUp()).isTrue();
    assertThat(table.server("seatwave", "seatwave-2").orElseThrow().checkStatus()).endsWith("L7OK");
  }

  @Test
  void findsTheBackendSummaryRow() throws IOException {
    StatTable table = StatTable.parse(capturedAnswer());

    assertThat(table.backendSummary("seatwave")).isPresent();
    assertThat(table.backendSummary("nothing")).isEmpty();
    assertThat(table.server("seatwave", "nothing")).isEmpty();
  }
```

`extracting(StatRow::server)` turns a list of rows into the list of one column of each (a **method reference** to the `server` accessor), so `containsExactly` can compare names. `endsWith("L7OK")` checks the end of a text. `isPresent()` and `isEmpty()` work on an `Optional` as well as on a list.

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

`hasSize(INSTANCES.size())` checks the number of items, and `allMatch(StatRow::isFullyUp)` checks that every item satisfies a condition. `.as(backend)` labels the check with the backend's name (more on labels below). `containsExactlyInAnyOrderElementsOf(INSTANCES)` checks the items without caring about order, against the items of another collection: the three instances must each have answered, in whatever order the balancer chose. These two tests need the running stack and were not run for these notes.

### Text: `contains`, `doesNotContain`, `containsPattern`

```java
  @Test
  void checksReadinessEveryTwoSecondsAndNeedsThreeFailures() throws IOException {
    for (String backend : List.of("seatwave", "seatwave_active_only")) {
      String block = backend(backend);

      assertThat(block).contains("http-check send meth GET uri /ready\n");
      assertThat(block)
          .containsPattern("default-server check port 8081 inter 2s fall 3 rise 2(\\s|$)");
    }
  }

  @Test
  void retriesOnlyWhatIsSafeToRepeat() throws IOException {
    String production = backend("seatwave");

    assertThat(production).contains("\n  retry-on conn-failure empty-response 503\n");
    assertThat(production).doesNotContain("response-timeout").doesNotContain("junk-response");
    assertThat(production)
        .contains("slowstart 10s")
        .contains("observe layer4 error-limit 3 on-error mark-down");
  }

  @Test
  void keepsTheActiveOnlyBackendFreeOfRetriesAndPassiveChecks() throws IOException {
    String simple = backend("seatwave_active_only");

    assertThat(simple).contains("\n  retries 0\n").contains("\n  retry-on none\n");
    assertThat(simple)
        .doesNotContain("redispatch")
        .doesNotContain("observe")
        .doesNotContain("slowstart");
  }
```

- `contains(...)` on text checks that a piece occurs somewhere. `containsPattern(...)` is the same with a **regular expression** (a pattern language for text, `java.md`): it passes when the pattern matches some part of the text. `doesNotContain(...)` checks the opposite. These tests read the balancer's configuration as text and check the rules the module states: the readiness check every two seconds and three failures to mark down, retries only for what is safe to repeat, no retries on the active-only backend (`tests-config-walkthrough.md`).
- Several `contains` and `doesNotContain` calls chain on one text, as in the last test: `.doesNotContain("redispatch").doesNotContain("observe").doesNotContain("slowstart")`.
- The `\n` in `"http-check send meth GET uri /ready\n"` is a line break. It pins the end of the line, so a longer line that merely begins with the same words does not pass.

### Numbers, ranges and labels

```java
  @Test
  void publishesOnlyBalancerPortsOnLoopbackInsideTheModulesRange() throws IOException {
    String compose = Files.readString(Path.of("compose.yaml"), StandardCharsets.UTF_8);
    Matcher published = PUBLISHED.matcher(compose);
    int count = 0;
    while (published.find()) {
      count++;
      assertThat(published.group(1)).isEqualTo("127.0.0.1");
      assertThat(Integer.parseInt(published.group(2))).isBetween(FIRST_PORT, LAST_PORT);
      // The container side is a balancer port: requests 8080 to 8083, status 8404, runtime 9999.
      assertThat(published.group(3)).isIn("8080", "8081", "8082", "8083", "8404", "9999");
    }
    assertThat(count).isEqualTo(12);
    for (String service : List.of("postgres", "seatwave-1", "seatwave-2", "seatwave-3")) {
      assertThat(serviceBlock(compose, service)).doesNotContain("ports:");
    }
  }
```

`isBetween(FIRST_PORT, LAST_PORT)` checks a range, `isIn(...)` checks that a value is one of a list, and `isEqualTo(12)` pins the count of published ports. `isPositive()` means greater than zero and `isZero()` means zero. `isEqualTo` and `isBetween` are the two checks that make the rule "only balancer ports on loopback inside the module's range" a test and not a sentence.

```java
  private static String backend(String name) throws IOException {
    String config =
        Files.readString(Path.of(System.getProperty("seatwave.haproxy")), StandardCharsets.UTF_8);
    int start = config.indexOf("\nbackend " + name + "\n");
    assertThat(start).as("backend %s in haproxy.cfg", name).isNotNegative();
    int end = config.indexOf("\n\n", start + 1);
    return config.substring(start, end < 0 ? config.length() : end) + "\n";
  }

  private static String serviceBlock(String compose, String service) {
    int start = compose.indexOf("\n  " + service + ":\n");
    assertThat(start).as("service %s in compose.yaml", service).isNotNegative();
    int end = compose.indexOf("\n\n", start + 1);
    return compose.substring(start, end);
  }
}
```

`.as("backend %s in haproxy.cfg", name)` gives the check a **label**, with the text filled in like `String.format`. When it fails, the message starts with the label, so a failure in a loop or a helper says which backend or service was missing. `isNotNegative()` is true for zero and above: the helper returns `-1` when the block is not found. I found 7 `.as(` calls: these two, one in `HarnessCliTest`, one in `LoadRunTest` and three in `StackTest`.

### Exceptions: `assertThatThrownBy`

```java
  @Test
  void rejectsANegativeDeadline() {
    Lifecycle lifecycle = new Lifecycle();

    assertThatThrownBy(() -> lifecycle.awaitDrained(Duration.ofMillis(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be negative");
  }
```

`assertThatThrownBy(() -> lifecycle.awaitDrained(Duration.ofMillis(-1)))` runs the call, catches what it throws and `isInstanceOf(IllegalArgumentException.class)` checks the type. If nothing is thrown, the check fails: a test that expects a refusal cannot pass by accident. `hasMessageContaining(...)` checks a piece of the message (8 calls in all); `hasMessage(...)` checks all of it. `isInstanceOf` is the third most used check after `isEqualTo` and `contains` (28 calls).

### Results: comparing and sorting outcomes

```java
  @Test
  void letsExactlyOneOfManyRacersHoldOneSeat() throws Exception {
    List<Result<GrantedHold, HoldRefusal>> outcomes =
        race(racer -> command("\"race-" + racer + "\"", SHA_A, "B-1"));

    long winners =
        outcomes.stream().filter(outcome -> outcome instanceof Result.Success<?, ?>).count();
    assertThat(winners).isEqualTo(1);
    assertThat(outcomes)
        .filteredOn(outcome -> outcome instanceof Result.Error<?, ?>)
        .allMatch(outcome -> outcome.equals(Result.error(new HoldRefusal.SeatTaken())));
  }
```

`filteredOn(outcome -> outcome instanceof Result.Error<?, ?>)` keeps the items that fit a condition written as a lambda, and `allMatch(...)` checks that every remaining item satisfies another. Together they say "every loser of the race got the same `SeatTaken` refusal". `RollingDeployTest` uses a different check on a list of calls, `containsSubsequence`, which passes when the given items occur in that order, with other items allowed between them:

```java
  @Test
  void restartsEachInstanceInOrderOnEveryBalancer() throws InterruptedException {
    Result<Integer, String> result = deploy(List.of(world.balancer("lb1"), world.balancer("lb2")));

    assertThat(result).isEqualTo(Result.success(2));
    assertThat(calls)
        .containsSubsequence(
            "lb1 seatwave/seatwave-1 DRAIN",
            "lb2 seatwave_active_only/seatwave-1 DRAIN",
            "stop seatwave-1",
            "start seatwave-1",
            "lb1 seatwave/seatwave-1 READY",
            "lb2 seatwave_active_only/seatwave-1 READY",
            "lb1 seatwave/seatwave-2 DRAIN",
            "stop seatwave-2");
  }
```

The test shows the rolling deploy's order: drain on both balancers, stop, start, ready, and only then the next instance.

## Try it yourself

All commands ran in a scratch copy on 11 November 2026.

1. Count the assertions:

```
grep -rho "assertThat(" src/test | wc -l
grep -rho "assertThatThrownBy(" src/test | wc -l
```

   Expected: `301` and `19`.

2. Count the files that import each:

```
grep -rl "assertThat;" src/test | wc -l
grep -rl "assertThatThrownBy;" src/test | wc -l
```

   Expected: `21` and `9`.

3. Run one test class that is full of assertions:

```
./gradlew --offline cleanTest test --tests '*SettingsReaderTest' --console=plain
```

   Expected: six `PASSED` lines and `BUILD SUCCESSFUL`.

4. See an AssertJ failure message. In a **scratch copy**, change `"count must be a whole number, but was seven"` in `SettingsReaderTest.java` to `"count must be a number"`, then run the command of step 3. Expected: `SettingsReaderTest > collectsOneProblemPerMissingOrInvalidKey() FAILED` on the console, and the full message in `build/test-results/test/TEST-io.plainround.seatwave.balancer.config.SettingsReaderTest.xml`. It begins `Expecting actual:` and lists the six actual problems, then `to contain exactly (and in same order):` and the six expected ones, then `but some elements were not found:` followed by `["count must be a number"]` and `and others were not expected:` followed by `["count must be a whole number, but was seven"]`. Put the file back afterwards.

5. Count how many assertions have a label:

```
grep -rn "\.as(" src/test | wc -l
```

   Expected: `7`.

## Mistakes people make

- **A test without an assertion.** It passes even if the code is wrong.
- **Comparing the wrong things.** `isEqualTo` on two different types compares nothing useful; check the type of what `assertThat` receives.
- **A loop without a label.** If a loop of 50 checks fails, you want to know which turn. `as(...)` solves it.
- **Forgetting that `containsExactly` checks order.** Use `containsExactlyInAnyOrder` when order does not matter.
- **Asserting before the thing has happened.** An assertion does not wait. Block on the answer, or poll with Awaitility.
- **Catching an exception by hand** in a `try` block and forgetting `fail()` for the case where nothing is thrown. `assertThatThrownBy` handles both.
- **Testing a configuration file only by the presence of a word.** `contains("slowstart 10s")` passes if the word sits in a comment. These tests pin whole lines and the end of a line (`\n`) to make that mistake harder.

## Check yourself

1. *What does `assertThat(x)` return?* An assertion object for the type of `x`, with the checks that fit it.
2. *What does a failed assertion throw?* An `AssertionError`, which JUnit reports as a failed test.
3. *Why does `assertThatThrownBy` take a lambda?* So AssertJ can run the code itself and catch what it throws.
4. *What does `as(...)` do?* It adds a label to the failure message.
5. *Why is `containsExactly` used for the problems of the settings reader?* The order of the problems is part of what the reader promises.

## Further reading

- AssertJ Core documentation (3.27.x): <https://assertj.github.io/doc/>
- AssertJ Javadoc for `Assertions`: <https://javadoc.io/doc/org.assertj/assertj-core/3.27.7/org/assertj/core/api/Assertions.html>
