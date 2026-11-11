# JUnit Jupiter 6.1.3

## What it is

JUnit is the standard Java library for writing **automated tests**. A test is an ordinary method, marked with the annotation `@Test`, that runs some code and checks the result. If a check fails, JUnit reports the failing test by name and the build stops. **JUnit Jupiter** is the part of JUnit you write tests with. The **JUnit Platform** is the part that finds and runs them. This repository uses version **6.1.3** of both. JUnit 6 needs Java 17 or newer and keeps the Jupiter style of version 5.

An **annotation** is a label written with `@` in front of a method, class or parameter, such as `@Test`. It does nothing by itself. Tools read it.

## Why this repository uses it

The project's tests double as its requirements: every test method has a long name that states one behavior in plain words, and 98 of them run with `./gradlew check`. JUnit gives the structure. AssertJ (`assertj.md`) gives the readable checks and Awaitility (`awaitility.md`) the waiting. This module is about a balancer and about instances that share no memory, so many tests start a real SeatWave server on the loopback address, run the hold rule against a real SQL database in memory, or run the rolling deploy against a stand-in balancer; others read the HAProxy and Compose files and check the rules that the module states. JUnit only has to start the test and notice whether it returned or threw.

## Where it appears

| Path | What it does with JUnit |
| --- | --- |
| `gradle/libs.versions.toml` | `junit = "6.1.3"` and the entries `junit-bom`, `junit-jupiter` and `junit-platform-launcher`. |
| `build.gradle.kts` | `platform(libs.junit.bom)`, `libs.junit.jupiter` and `libs.junit.platform.launcher`, and `useJUnitPlatform` in two tasks (`test` excludes the tag `stack`, `stackTest` includes it). |
| `gradle.lockfile` | Locks the JUnit libraries at 6.1.3 (`dependency-locking.md`). |
| `src/test/java/**` | 21 Java files: 16 test classes with 102 `@Test` methods (98 run by `check`, 4 tagged `stack`), and 5 helper classes that hold no tests (`FakeBalancer`, `MutableClock`, `ScriptedHoldStore`, `TestDatabase`, `TestSettings`). |

## How it works underneath

1. `./gradlew test` starts a new JVM and hands it the test classes, with the JUnit Platform launcher (`junit-platform-launcher`) as the entry point.
2. The Platform asks the **Jupiter engine** (`junit-jupiter-engine`) to **discover** tests: it scans the classes under `build/classes/java/test` for methods marked `@Test`. Because `useJUnitPlatform { excludeTags("stack") }` is set, a class or method tagged `stack` is left out of the plan.
3. For **each** test method Jupiter creates a **new instance** of the test class, runs the method, and throws the instance away. State cannot leak from one test to the next through an ordinary field.
4. A test **passes** if the method returns normally. It **fails** if it throws an `AssertionError`, which is what a wrong check throws. It is an **error** if it throws anything else.
5. Results go to `build/test-results/test/*.xml` (one file per class, 15 here, because the class tagged `stack` did not run), and Gradle prints one line per result because of `testLogging { events("passed", "failed", "skipped") }`.
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
    AtomicBoolean drainingStarted = new AtomicBoolean();
    AtomicLong admittedAfterDrain = new AtomicLong();
    CountDownLatch go = new CountDownLatch(1);
    List<Future<?>> racers = new ArrayList<>();
    try (ExecutorService threads = Executors.newFixedThreadPool(RACERS + 1)) {
      for (int racer = 0; racer < RACERS; racer++) {
        racers.add(
            threads.submit(() -> {
              go.await();
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
              }
              return null;
            }));
      }
      go.countDown();
      lifecycle.startDraining();
      drainingStarted.set(true);
      Lifecycle.DrainResult result = lifecycle.awaitDrained(LONG_WAIT);
      for (Future<?> racerDone : racers) {
        racerDone.get(LONG_WAIT.toSeconds(), TimeUnit.SECONDS);
      }

      assertThat(admittedAfterDrain.get()).isZero();
      assertThat(result.isClean()).isTrue();
    }
  }
```

This is the test of the module's central rule: no request is admitted after draining begins. JUnit starts it like any other test, and the test starts eight threads of its own (`RACERS`), holds them at a `CountDownLatch` called `go` until all are ready, and then lets them call `tryAdmit` in a loop while the main thread calls `startDraining`. A racer reads the flag `drainingStarted` **before** it asks, so an admission granted after the flag was set proves the gate let a request through too late. The test counts those and asserts the count is zero. `try (ExecutorService threads = ...)` closes the pool at the end of the block (`java.md`, try-with-resources). A race test like this passes by finding no bad event, so one passing run proves little. The README reports that the whole test task ran 40 times in a row beside eight busy loops with no failure, which is the stronger evidence.

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

With the field, the folder is made before each test of the class and every test of the class shares the same field name. I searched the test sources and found `@TempDir` 5 times: one parameter and four fields (in `SettingsFilesTest`, `HarnessCliTest`, `DeployCliTest` and `SeatWaveCliTest`).

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

`SeatWaveServerTest` stops the server it started (`drainAndStop(Duration.ZERO)` waits for nothing), releases the holds that its scripted store was holding, and closes the HTTP client. `HarnessCliTest` pairs both annotations around a stand-in HAProxy and a stand-in SeatWave (`startStandIns` and `stopStandIns`). Two classes use `@BeforeEach` and two use `@AfterEach`; `JdbcHoldStoreTest` has no `@AfterEach` because an in-memory database needs no clean-up.

### `@Tag`, `@BeforeAll` and `@AfterAll`: the tests that need the stack

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

  @BeforeAll
  static void connect() {
    client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  }

  @AfterAll
  static void disconnect() {
    client.close();
  }
```

- The class comment says what the tests check and ends with "Never part of ./gradlew check."
- `@Tag("stack")` on the class labels it. The build file uses the label in two places (`gradle.md`): `test` calls `excludeTags("stack")`, so `./gradlew check` never needs Docker, and `stackTest` calls `includeTags("stack")`, so only these four tests run against the running stack.
- The constants under the tag name the two balancers' published ports (`21000` and `21010`), the runtime API clients (`21005` and `21015`) and the pattern that finds a hold id in an answer; `tests-stack-walkthrough.md` explains how they are used.
- `@BeforeAll` marks a method that runs once before the first test of the class. `@AfterAll` marks one that runs once after the last. Both must be `static`, because no test instance exists yet when they run (JUnit creates a new instance for each test, as step 3 says). The class keeps one HTTP client in a `static` field, makes it once and closes it once. This is the only class that uses these two annotations.
- The four tests of this class were not run for these notes: they need the Compose stack, and I did not start Docker. The README reports that they passed on its own run (`tests-stack-walkthrough.md`).

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

   Expected: `98`. (`cleanTest` forces the tests to run again instead of being skipped as up to date.)

2. Run one class:

```
./gradlew --offline cleanTest test --tests '*LifecycleTest' --console=plain
```

   Expected: eleven lines ending in `PASSED`, then `BUILD SUCCESSFUL in 2s` and `7 actionable tasks: 3 executed, 4 up-to-date`.

3. Run the methods of one class that match a pattern and count them:

```
./gradlew --offline cleanTest test --tests '*HoldRoutesTest.*' --console=plain | grep -c PASSED
```

   Expected: `16`.

4. Count the annotations at the start of a line:

```
grep -rhoE "^ *@[A-Za-z]+" src/test | sed 's/^ *//' | sort | uniq -c
```

   Expected: `1 @AfterAll`, `2 @AfterEach`, `1 @BeforeAll`, `2 @BeforeEach`, `1 @FunctionalInterface`, `14 @Override`, `1 @Tag`, `4 @TempDir` and `102 @Test`. (The fifth `@TempDir` sits inside a method signature, so this pattern does not show it; `grep -rn "@TempDir" src/test | wc -l` finds all 5.)

5. Read the totals from the result files:

```
grep -o 'tests="[0-9]*"' build/test-results/test/*.xml
```

   Each of the 15 files has a `tests="..."` count after a full run. They add up to 98. The largest are `HoldRoutesTest` with 16, `JdbcHoldStoreTest` with 13 and `LifecycleTest` with 11.

## Mistakes people make

- **Vague names** such as `test1`. A good name is a sentence about behavior, as here.
- **Tests that depend on each other's order.** JUnit gives no order promise. Each test builds its own input and starts its own server where it needs one, on a port the operating system picks.
- **Asserting nothing.** A test that only calls code passes even if the code is wrong.
- **Forgetting the launcher with Gradle 9.** Without `junit-platform-launcher` on the test runtime classpath the tests can not start. The build declares it.
- **A filter that matches too much.** `--tests '*report*'` also matches a package name that contains `report`, so it runs every test in that package. Use the full class name when you want one class.
- **Running a test outside Gradle.** `System.getProperty("seatwave.schema")` is `null` unless the Gradle `test` task sets it.
- **Starting a server and never stopping it.** A server left running after a test holds its port. The tests close every server in an `@AfterEach` method or a `try` block that closes it automatically.
- **Assuming `check` ran every test.** Four tests are tagged `stack` and run only through `stackTest`.

## Check yourself

1. *What does `@Test` do?* Marks a method that JUnit runs as a test.
2. *Why does JUnit create a new instance of the class for every test?* So no test can see state left by another.
3. *What does `@TempDir` do?* JUnit makes a new empty folder for the test, passes it as the parameter or sets the field, and deletes it afterwards.
4. *Why must a `@BeforeAll` method be `static`?* It runs before any instance of the test class exists.
5. *How do the 102 `@Test` methods become 98 results in `check`?* The four methods of `StackTest` carry the tag `stack`, and `test` excludes it.

## Further reading

- JUnit 6 user guide (6.1.3): <https://docs.junit.org/6.1.3/overview.html>
- Writing tests and annotations: <https://docs.junit.org/6.1.3/writing-tests/annotations.html>
- Tagging and filtering tests: <https://docs.junit.org/6.1.3/writing-tests/tagging-and-filtering.html>
- Running JUnit on Gradle: <https://docs.gradle.org/9.9.1/userguide/java_testing.html>
