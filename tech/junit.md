# JUnit Jupiter 6.1.3

## What it is

JUnit is the standard Java library for writing **automated tests**. A test is an ordinary method, marked with the annotation `@Test`, that runs some code and checks the result. If a check fails, JUnit reports the failing test by name and the build stops. **JUnit Jupiter** is the part of JUnit you write tests with. The **JUnit Platform** is the part that finds and runs them. This repository uses version **6.1.3** of both. JUnit 6 needs Java 17 or newer and keeps the Jupiter style of version 5.

An **annotation** is a label written with `@` in front of a method, class or parameter, such as `@Test`. It does nothing by itself. Tools read it.

## Why this repository uses it

The project's tests double as its requirements: every test method has a long name that states one behavior in plain words, and 152 of them run with `./gradlew check`. JUnit gives the structure. AssertJ (`assertj.md`) gives the readable checks and Awaitility (`awaitility.md`) the waiting. This module is about a balancer and about instances that share no memory, so many tests start a real SeatWave server on the loopback address, run the hold rule against a real SQL database in memory, or run the rolling deploy against a stand-in balancer; others read the HAProxy and Compose files and check the rules that the module states. JUnit only has to start the test and notice whether it returned or threw.

## Where it appears

| Path | What it does with JUnit |
| --- | --- |
| `gradle/libs.versions.toml` | `junit = "6.1.3"` and the entries `junit-bom`, `junit-jupiter` and `junit-platform-launcher`. |
| `build.gradle.kts` | `platform(libs.junit.bom)`, `libs.junit.jupiter` and `libs.junit.platform.launcher`, and `useJUnitPlatform` in two tasks (`test` excludes the tag `stack`, `stackTest` includes it). |
| `gradle.lockfile` | Locks the JUnit libraries at 6.1.3 (`dependency-locking.md`). |
| `src/test/java/**` | 27 Java files: 21 test classes with 158 `@Test` methods (152 run by `check`, 6 tagged `stack`), and 6 helper classes that hold no tests (`FakeBalancer`, `JdbcStandIns`, `MutableClock`, `ScriptedHoldStore`, `TestDatabase`, `TestSettings`). |

## How it works underneath

1. `./gradlew test` starts a new JVM and hands it the test classes, with the JUnit Platform launcher (`junit-platform-launcher`) as the entry point.
2. The Platform asks the **Jupiter engine** (`junit-jupiter-engine`) to **discover** tests: it scans the classes under `build/classes/java/test` for methods marked `@Test`. Because `useJUnitPlatform { excludeTags("stack") }` is set, a class or method tagged `stack` is left out of the plan.
3. For **each** test method Jupiter creates a **new instance** of the test class, runs the method, and throws the instance away. State cannot leak from one test to the next through an ordinary field.
4. A test **passes** if the method returns normally. It **fails** if it throws an `AssertionError`, which is what a wrong check throws. It is an **error** if it throws anything else.
5. Results go to `build/test-results/test/*.xml` (one file per class, 20 here, because the class tagged `stack` did not run), and Gradle prints one line per result because of `testLogging { events("passed", "failed", "skipped") }`.
6. The classes and methods are **package-private** (no `public`). JUnit 5 and 6 allow that, which keeps tests out of the public surface.

JUnit does not promise an order between tests, and no test here depends on one. By default JUnit runs the tests one after the other on one thread, and Gradle uses one test JVM, so two tests never overlap in time. (A single test may start threads of its own, as the race tests below do.)

## Walkthrough of this repository's use

### `@Test`: one behavior, one sentence

```java
  @Test
  void refusesEveryRequestWhileStarting() {
    Lifecycle lifecycle = new Lifecycle();

    assertThat(lifecycle.tryAdmit()).isEmpty();
    assertThat(lifecycle.state()).isEqualTo(LifecycleState.STARTING);
  }

  @Test
  void admitsAndCountsRequestsOnceReady() {
    Lifecycle lifecycle = new Lifecycle();
    lifecycle.markReady();

    Optional<Lifecycle.Admission> admission = lifecycle.tryAdmit();

    assertThat(admission).isPresent();
    assertThat(lifecycle.inFlight()).isEqualTo(1);
  }
```

`@Test` marks the method as a test. The name is a sentence about behavior, so a failure reads like a broken requirement: "refuses every request while starting". The body has three steps that most tests here follow: **arrange** (build the input), **act** (call the code), **assert** (check the outcome). In the first test the first statement arranges (`new Lifecycle()`), the second acts and asserts at once, and the third asserts. In the second test the arranging has two statements, the act is the call that returns the admission, and the two `assertThat` calls check the result.

```java
  @Test
  void rejectsANegativeDeadline() {
    Lifecycle lifecycle = new Lifecycle();

    assertThatThrownBy(() -> lifecycle.awaitDrained(Duration.ofMillis(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be negative");
  }
```

A test of a refusal: `assertThatThrownBy` runs the code and checks that it throws the right kind of exception with the right words in its message (`assertj.md`).

Many tests declare `throws Exception` or `throws InterruptedException`, as `stopsAtOnceWhenNothingIsInFlight` does. That is allowed: JUnit calls the method and treats any exception it throws as an error. It saves the test from catching checked exceptions that would be noise in a test.

### A test that races threads

```java
  @Test
  void admitsNothingAfterDrainingStartsEvenUnderARace() throws Exception {
    Lifecycle lifecycle = new Lifecycle();
    lifecycle.markReady();
    // Held open by the test, so the drain cannot end before the racers have tried many times.
    Lifecycle.Admission held = lifecycle.tryAdmit().orElseThrow();
    AtomicBoolean drainingStarted = new AtomicBoolean();
    AtomicLong attemptsAfterDrain = new AtomicLong();
    AtomicLong admittedAfterDrain = new AtomicLong();
    CountDownLatch racing = new CountDownLatch(RACERS);
    List<Future<?>> racers = new ArrayList<>();
    try (ExecutorService threads = Executors.newFixedThreadPool(RACERS)) {
      for (int racer = 0; racer < RACERS; racer++) {
        racers.add(
            threads.submit(() -> {
              boolean isCounted = false;
              while (lifecycle.state() != LifecycleState.STOPPED) {
                // Read the flag before asking: an admission granted after the flag was set means
                // the gate let a request through after draining began.
                boolean wasDraining = drainingStarted.get();
                Optional<Lifecycle.Admission> admission = lifecycle.tryAdmit();
                if (admission.isPresent()) {
                  if (wasDraining) {
                    admittedAfterDrain.incrementAndGet();
                  }
                  admission.get().close();
                }
                if (wasDraining) {
                  attemptsAfterDrain.incrementAndGet();
                }
                if (!isCounted) {
                  // This racer is in its loop: the drain starts only when every racer is.
                  isCounted = true;
                  racing.countDown();
                }
              }
              return null;
            }));
      }
      assertThat(racing.await(LONG_WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
      lifecycle.startDraining();
      drainingStarted.set(true);
      // The race is real: the racers keep asking while the instance drains.
      await().atMost(LONG_WAIT).until(() -> attemptsAfterDrain.get() >= RACERS * ATTEMPTS);
      held.close();
      Lifecycle.DrainResult result = lifecycle.awaitDrained(LONG_WAIT);
      for (Future<?> racerDone : racers) {
        racerDone.get(LONG_WAIT.toSeconds(), TimeUnit.SECONDS);
      }

      assertThat(admittedAfterDrain.get()).isZero();
      assertThat(result.isClean()).isTrue();
    }
  }
}
```

This is the test of the module's central rule: no request is admitted after draining begins. JUnit starts it like any other test, and the test starts eight threads of its own (`RACERS`) on a pool. Each racer calls `tryAdmit` in a loop and counts a `CountDownLatch` called `racing` down on its first turn; the main thread waits on that latch until all eight are running, then calls `startDraining`. A racer reads the flag `drainingStarted` **before** it asks, so an admission granted after the flag was set proves the gate let a request through too late. The test counts those and asserts the count is zero. One admission, `held`, stays open until the racers have made 8,000 tries against the draining gate (`await()` from Awaitility waits for that), so the drain cannot end before the race has really happened. `try (ExecutorService threads = ...)` closes the pool at the end of the block (`java.md`, try-with-resources). A race test like this passes by finding no bad event, so one passing run proves little. Two things make it stronger: the module's mutation check breaks the gate and this test fails, and the module ran the whole test task 40 times beside 8 busy loops on the 12 cores with 0 failures. `tests-seatwave-walkthrough-1.md` reads every line.

### `@TempDir`: a folder for one test

```java
  @Test
  void saysThatAMissingFileDoesNotExist(@TempDir Path folder) {
    Path missing = folder.resolve("absent.properties");

    assertThatThrownBy(() -> SettingsReader.load(missing))
        .isInstanceOf(IOException.class)
        .hasMessage("the settings file " + missing + " does not exist");
  }
```

`@TempDir` on a method parameter asks JUnit to create a new empty temporary folder, pass it in as `folder`, and delete it, with everything inside, after the test. This test asks for a file that does not exist inside that folder and checks the message of the `IOException`. `SettingsFilesTest` uses the other form, a field:

```java
  @TempDir Path folder;
```

With the field, the folder is made before each test of the class and every test of the class shares the same field name. I searched the test sources and found `@TempDir` 7 times: two parameters (both in `SettingsReaderTest`) and five fields (in `SettingsFilesTest`, `HarnessCliTest`, `DeployCliTest`, `DockerComposeTest` and `SeatWaveCliTest`).

### `@BeforeEach` and `@AfterEach`: a fresh fixture for every test

```java
  private JdbcDataSource dataSource;
  private MutableClock clock;
  private JdbcHoldStore store;

  @BeforeEach
  void freshDatabase() {
    dataSource = TestDatabase.create();
    clock = new MutableClock(START);
    store = new JdbcHoldStore(dataSource, clock, CHECK_TIMEOUT);
  }
```

`@BeforeEach` marks a method that runs before every test of the class, on the new instance. `JdbcHoldStoreTest` uses it to make a new in-memory database, a clock whose time the test can move, and a hold store on both, so every test starts from empty tables (`postgresql-and-h2.md`). `@AfterEach` marks one that runs after every test, even after a failed one:

```java
  @AfterEach
  void stop() {
    store.releaseHolds();
    if (server != null) {
      server.drainAndStop(Duration.ZERO);
    }
    client.close();
  }
```

`SeatWaveServerTest` stops the server it started (`drainAndStop(Duration.ZERO)` waits for nothing), releases the holds that its scripted store was holding, and closes the HTTP client. `HarnessCliTest` pairs both annotations around a stand-in HAProxy and a stand-in SeatWave (`startStandIns` and `stopStandIns`), and `LoadRunTest` around a stand-in SeatWave and its HTTP client (`startStandIn` and `stopStandIn`). Three classes use `@BeforeEach` (`JdbcHoldStoreTest`, `HarnessCliTest`, `LoadRunTest`) and three use `@AfterEach` (`SeatWaveServerTest`, `HarnessCliTest`, `LoadRunTest`); `JdbcHoldStoreTest` has no `@AfterEach` because an in-memory database needs no clean-up.

### `@Tag`, `@BeforeAll` and `@AfterAll`: the tests that need the stack

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

- The class comment says what the tests check and ends with "Every port is read from compose.yaml, the file that publishes it."
- `@Tag("stack")` on the class labels it. The build file uses the label in two places (`gradle.md`): `test` calls `excludeTags("stack")`, so `./gradlew check` never needs Docker, and `stackTest` calls `includeTags("stack")`, so only these six tests run against the running stack.
- The constants under the tag are timeouts, the slow-start time, the number of rounds, the seats per section, the backends and instances, and the pattern that finds a hold id in an answer; `tests-stack-walkthrough.md` explains how they are used.
- `@BeforeAll` marks a method that runs once before the first test of the class. `@AfterAll` marks one that runs once after the last. Both must be `static`, because no test instance exists yet when they run (JUnit creates a new instance for each test, as step 3 says). `connect()` reads the balancers' published ports from `compose.yaml` once, fills the `static` fields `lb1`, `lb2`, `lb1Api` and `lb2Api`, and makes one HTTP client; `disconnect()` closes it once. This is the only class that uses these two annotations.
- The six tests of this class were not run for these notes: they need the Compose stack, and I did not start Docker. They passed on the module's own 1.1.0 run (`tests-stack-walkthrough.md` shows the lines).

### What this repository does not use

There is no `@ParameterizedTest`, `@ValueSource`, `@EnumSource`, `@Nested`, `@DisplayName`, `@Timeout`, `@Disabled`, `@DisabledOnOs` or `@ExtendWith`. I searched the test sources for each and found none. Where several inputs need one check, a test lists the checks one after another. Each test builds what it needs with ordinary helper methods. That keeps each test readable from top to bottom. A helper class holds no `@Test`, so JUnit ignores it.

There is also no `@Timeout`. A test that talks to a server never relies on JUnit to cut it off. Each wait carries its own bound instead: an `HttpClient` request timeout, a `Duration` limit in `await().atMost(LONG_WAIT)`, a timeout on `Future.get`, or a timeout inside the call. A bound inside the call gives a clear error, where an outside timeout would only say that the test took too long.

### Static imports and the assertion style

All tests start with lines such as `import static org.assertj.core.api.Assertions.assertThat;`. A **static import** lets you write `assertThat(...)` instead of `Assertions.assertThat(...)`. The checks come from AssertJ, not from JUnit's own `Assertions` class (`assertj.md`).

### Where tests run from

Gradle runs the tests with the project folder as the working directory. That is why `ServiceSettingsTest` and `SeatWaveCliTest` can read `config/seatwave-container.properties` by that relative path (`java-properties.md`). The tests that need the database schema read the file name from the system property `seatwave.schema`, and the tests that check the HAProxy file read `seatwave.haproxy`; only the Gradle `test` task sets them (`gradle.md`).

### The build wiring

`testImplementation(libs.junit.jupiter)` makes the annotations and the engine available. `testRuntimeOnly(libs.junit.platform.launcher)` adds the launcher. `useJUnitPlatform` tells the `test` task to use the Platform. The BOM `platform(libs.junit.bom)` fixes every JUnit library at 6.1.3 (`gradle.md`).

## Try it yourself

All ran in a scratch copy on 11 November 2026. Timings differ on every run.

1. Run all tests and count the results:

```
./gradlew --offline cleanTest test --console=plain | grep -c PASSED
```

   Expected: `152`. (`cleanTest` forces the tests to run again instead of being skipped as up to date.)

2. Run one class:

```
./gradlew --offline cleanTest test --tests '*LifecycleTest' --console=plain
```

   Expected: eleven lines ending in `PASSED`, then `BUILD SUCCESSFUL in 1s` and `7 actionable tasks: 3 executed, 4 up-to-date`.

3. Run the methods of one class that match a pattern and count them:

```
./gradlew --offline cleanTest test --tests '*HoldRoutesTest.*' --console=plain | grep -c PASSED
```

   Expected: `19`.

4. Count the annotations at the start of a line:

```
grep -rhoE "^ *@[A-Za-z]+" src/test | sed 's/^ *//' | sort | uniq -c
```

   Expected: `1 @AfterAll`, `3 @AfterEach`, `1 @BeforeAll`, `3 @BeforeEach`, `1 @FunctionalInterface`, `26 @Override`, `1 @Tag`, `5 @TempDir` and `158 @Test`. (The two other `@TempDir`s sit inside method signatures, so this pattern does not show them; `grep -rn "@TempDir" src/test | wc -l` finds all 7.)

5. Read the totals from the result files:

```
grep -o 'tests="[0-9]*"' build/test-results/test/*.xml
```

   Each of the 20 files has a `tests="..."` count after a full run. They add up to 152. The largest are `HoldRoutesTest` with 19, `JdbcHoldStoreTest` with 15 and `RunReportTest` with 14.

## Mistakes people make

- **Vague names** such as `test1`. A good name is a sentence about behavior, as here.
- **Tests that depend on each other's order.** JUnit gives no order promise. Each test builds its own input and starts its own server where it needs one, on a port the operating system picks.
- **Asserting nothing.** A test that only calls code passes even if the code is wrong.
- **Forgetting the launcher with Gradle 9.** Without `junit-platform-launcher` on the test runtime classpath the tests can not start. The build declares it.
- **A filter that matches too much.** `--tests '*report*'` also matches a package name that contains `report`, so it runs every test in that package. Use the full class name when you want one class.
- **Running a test outside Gradle.** `System.getProperty("seatwave.schema")` is `null` unless the Gradle `test` task sets it.
- **Starting a server and never stopping it.** A server left running after a test holds its port. The tests close every server in an `@AfterEach` method or a `try` block that closes it automatically.
- **Assuming `check` ran every test.** Six tests are tagged `stack` and run only through `stackTest`.

## Check yourself

1. *What does `@Test` do?* Marks a method that JUnit runs as a test.
2. *Why does JUnit create a new instance of the class for every test?* So no test can see state left by another.
3. *What does `@TempDir` do?* JUnit makes a new empty folder for the test, passes it as the parameter or sets the field, and deletes it afterwards.
4. *Why must a `@BeforeAll` method be `static`?* It runs before any instance of the test class exists.
5. *How do the 158 `@Test` methods become 152 results in `check`?* The six methods of `StackTest` carry the tag `stack`, and `test` excludes it.

## Further reading

- JUnit 6 user guide (6.1.3): <https://docs.junit.org/6.1.3/overview.html>
- Writing tests and annotations: <https://docs.junit.org/6.1.3/writing-tests/annotations.html>
- Tagging and filtering tests: <https://docs.junit.org/6.1.3/writing-tests/tagging-and-filtering.html>
- Running JUnit on Gradle: <https://docs.gradle.org/9.9.1/userguide/java_testing.html>
