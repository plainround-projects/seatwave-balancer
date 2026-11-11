# The `seatwave` tests, part 1: the helpers, the lifecycle, the settings and the command line (line by line)

This is part 1 of 2. This part reads the four helper classes (`TestSettings`, `MutableClock`, `TestDatabase`, `ScriptedHoldStore`) and the tests `LifecycleTest`, `ServiceSettingsTest` and `SeatWaveCliTest`. Part 2 (`tests-seatwave-walkthrough-2.md`) reads the helper `JdbcStandIns` and the tests `JdbcHoldStoreTest`, `SeatWaveServerTest` and `HoldRoutesTest`. The notes were split because the test package is about 1,500 lines and each file quotes every line.

## What it is

The tests of the `seatwave` package check one SeatWave instance without Docker and without PostgreSQL. They run in `./gradlew check`, they use a real HTTP server on a free loopback port, and they use H2 (an in-memory database, see `postgresql-and-h2.md`) with the project's own `db/schema.sql`.

Terms you need.

- A **test** is a small program that runs a piece of code and checks the result. A **test class** holds many tests. **JUnit** (version 6, see `junit.md`) finds the methods marked `@Test`, runs each one, and reports pass or fail.
- An **assertion** is a check inside a test: "this must be true". **AssertJ** (see `assertj.md`) writes assertions as sentences: `assertThat(value).isEqualTo(3)`. A failed assertion fails the test with a message.
- **Awaitility** (see `awaitility.md`) waits for a condition to become true, without a fixed sleep: `await().atMost(10 s).until(() -> something)`.
- A **fake** (or **test double**) is a simple stand-in for a real part, built for tests. `ScriptedHoldStore` stands in for the database.
- `@BeforeEach` and `@AfterEach` mark methods JUnit runs before and after every test. `@TempDir` gives a test a fresh empty folder that is deleted afterwards.
- A **race** is a bug that shows only when two threads do things in an unlucky order. A **latch** (`CountDownLatch`) is a gate that holds threads until a count reaches zero; tests use it to start many threads at the same instant.
- A **mutation check** means breaking the code on purpose to see that a test fails. A test that does not fail when the code is broken is not protecting anything.

## Why this repository uses it

- The module's central claim is a rule about timing: no request is admitted after draining starts, and an instance stops only when nothing is in flight. A rule about timing needs tests that create the timing on purpose: `ScriptedHoldStore` can hold a request open, and `LifecycleTest` starts eight threads at once.
- Everything the tests need must run on a laptop in seconds, so the database is H2, the clock is moved by hand, and the HTTP server uses port 0 (any free port).
- The tests are the specification that a reader can run. Their names are sentences, for example `admitsNothingAfterDrainingStartsEvenUnderARace`.

## Where it appears

| File | What it does |
|---|---|
| `seatwave/TestSettings.java` | Builds the settings of a server under test. |
| `seatwave/MutableClock.java` | A clock the test moves by hand. |
| `seatwave/TestDatabase.java` | A fresh H2 database from `db/schema.sql` for each test. |
| `seatwave/ScriptedHoldStore.java` | A fake store a test can steer. |
| `seatwave/LifecycleTest.java` | Eleven tests of the gate and the drain. |
| `seatwave/ServiceSettingsTest.java` | Eleven tests of the settings rules, the pool's lowest timeout and Docker's stop grace. |
| `seatwave/SeatWaveCliTest.java` | Three tests of the command line. |

## How it works underneath

Gradle's `test` task compiles the tests, starts a JVM, and asks JUnit to run every class. The task adds two system properties, `seatwave.schema` and `seatwave.haproxy`, holding the absolute paths of `db/schema.sql` and `haproxy/haproxy.cfg`, and it excludes tests tagged `stack`, which need the Compose stack. The working folder of the tests is the project folder, so relative paths such as `config/seatwave-container.properties` work. Each test method gets a new object of its test class, so fields do not leak between tests.

## Walkthrough of this repository's use

### `seatwave/TestSettings.java`

```java
package io.plainround.seatwave.balancer.seatwave;

import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;

/** Settings for a server under test: loopback, any free port, small pools. */
final class TestSettings {
```

A package-private helper (no `public`) that builds a `ServiceSettings`. The Javadoc: "loopback, any free port, small pools".

```java
  private static final int BACKLOG = 50;
  private static final int WORKERS = 8;
  private static final int QUEUE = 16;
  private static final int POOL = 2;

  private TestSettings() {}
```

Four named numbers: backlog 50, 8 worker threads, a queue of 16, a pool of 2. The constructor is private: the class only has a static method.

```java
  static ServiceSettings withDrainDeadline(Duration drainDeadline) {
    return new ServiceSettings(
        InetAddress.getLoopbackAddress(),
        0,
        0,
        BACKLOG,
        WORKERS,
        QUEUE,
        new ServiceSettings.DatabaseSettings(
            "jdbc:h2:mem:unused",
            "seatwave",
            Path.of("unused.password"),
            POOL,
            Duration.ofSeconds(1),
            Duration.ofSeconds(1)),
        drainDeadline);
  }
}
```

`withDrainDeadline` builds a full `ServiceSettings` by calling the record's constructor directly, not through a settings file.

- `InetAddress.getLoopbackAddress()` is `127.0.0.1`: the server listens only on this machine.
- The two `0` values are the request port and the admin port: ask the operating system for any free ports. Tests can run in parallel, or on a busy machine, without a clash.
- The database settings are placeholders (`jdbc:h2:mem:unused`, `unused.password`): the tests that use this method give the server a fake store, so no database is opened.
- The caller picks only the drain deadline, because that is the one number the drain tests vary.

### `seatwave/MutableClock.java`

```java
package io.plainround.seatwave.balancer.seatwave;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A clock the tests move by hand, so expiry is tested without waiting. Thread-safe. */
final class MutableClock extends Clock {
```

A `Clock` is Java's source of the current time (`clock.instant()`). The production code asks a clock and never calls `Instant.now()`, so a test can replace the clock. `MutableClock` extends `Clock`. The Javadoc says it is "a clock the tests move by hand, so expiry is tested without waiting" and that it is thread-safe.

```java
  private final AtomicReference<Instant> now;

  MutableClock(Instant start) {
    this.now = new AtomicReference<>(start);
  }

  void advance(Duration step) {
    now.updateAndGet(instant -> instant.plus(step));
  }
```

The current time is held in an `AtomicReference<Instant>`: a box whose content can be replaced safely by many threads. `advance(step)` moves it forward with `updateAndGet`, which applies a function (add the step) and stores the result in one atomic step. The tests then say `clock.advance(Duration.ofSeconds(300))` to make a hold expire instantly.

```java
  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    throw new UnsupportedOperationException("the tests use UTC only");
  }

  @Override
  public Instant instant() {
    return now.get();
  }
}
```

The three methods `Clock` requires.

- `getZone()` returns UTC. `instant()` returns the stored time.
- `withZone(...)` throws `UnsupportedOperationException("the tests use UTC only")`. A clock that quietly ignored a zone change could hide a time-zone mistake; a thrown error makes it loud.

### `seatwave/TestDatabase.java`

This file is also quoted in `postgresql-and-h2.md`, where the H2 options are explained.

```java
package io.plainround.seatwave.balancer.seatwave;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;

/**
 * A fresh in-memory H2 database in its PostgreSQL mode, made from the project's own db/schema.sql,
 * the file PostgreSQL runs in the stack. One per test, named at random, so tests never share rows.
 */
final class TestDatabase {

  private TestDatabase() {}
```

Imports and the class comment: "A fresh in-memory H2 database in its PostgreSQL mode, made from the project's own `db/schema.sql`, the file PostgreSQL runs in the stack. One per test, named at random, so tests never share rows." The private constructor shows that the class is only a static helper.

```java
  static JdbcDataSource create() {
    JdbcDataSource dataSource = new JdbcDataSource();
    // DB_CLOSE_DELAY=-1 keeps the database while no connection is open; the name keeps tests apart.
    dataSource.setURL(
        "jdbc:h2:mem:seatwave-"
            + UUID.randomUUID()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String sql : statements()) {
        statement.execute(sql);
      }
    } catch (SQLException | IOException failure) {
      throw new IllegalStateException("the test database could not be made", failure);
    }
    return dataSource;
  }
```

`create()` builds a `JdbcDataSource` (H2's `DataSource`) with a URL made of `jdbc:h2:mem:seatwave-`, a random UUID, and the options `MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1`. It opens a connection and runs each statement of the schema. The two-part `try (Connection ...; Statement ...)` closes both. A failure becomes an `IllegalStateException`, because a test cannot continue without its database.

```java
  private static String[] statements() throws IOException {
    String schema =
        Files.readString(Path.of(System.getProperty("seatwave.schema")), StandardCharsets.UTF_8);
    // Comment lines go first, then the file splits on the semicolons that end its statements.
    String withoutComments =
        schema
            .lines()
            .filter(line -> !line.startsWith("--"))
            .reduce("", (all, line) -> all + line + "\n");
    return Arrays.stream(withoutComments.split(";"))
        .map(String::strip)
        .filter(sql -> !sql.isEmpty())
        .toArray(String[]::new);
  }
}
```

`statements()` reads the file named by the system property `seatwave.schema`, removes the `--` comment lines, joins the lines back with line breaks (`reduce` combines the lines one by one), cuts at each `;`, strips spaces, and drops empty pieces. The result is an array of statements. `String[]::new` is a method reference that makes the array.

### `seatwave/ScriptedHoldStore.java`

```java
package io.plainround.seatwave.balancer.seatwave;

import com.github.sviperll.result4j.Result;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A hold store the tests steer: it can keep a hold request open until released (to have a request
 * in flight during a drain), be "down", and answer readiness as told. Thread-safe.
 */
final class ScriptedHoldStore implements HoldStore {
```

The Javadoc: a hold store the tests steer. It can keep a hold request open until released (to have a request in flight during a drain), be "down", and answer readiness as told. It implements `HoldStore`, so the server and the routes cannot tell it from the real one.

```java
  private static final long LONGEST_HOLD_SECONDS = 30;
  static final Instant EXPIRES = Instant.parse("2026-11-11T09:05:00Z");

  private final CountDownLatch release = new CountDownLatch(1);
  private final CountDownLatch entered = new CountDownLatch(1);
  private final AtomicBoolean isBlocking = new AtomicBoolean();
  private final AtomicBoolean isDown = new AtomicBoolean();
  private final AtomicBoolean isReachable = new AtomicBoolean(true);
```

- `LONGEST_HOLD_SECONDS = 30` is a safety limit: no test waits for ever.
- `EXPIRES` is a fixed instant, 09:05 on 11 November 2026. Every hold it makes expires then.
- `release` and `entered` are two `CountDownLatch`es of size 1. `entered` opens when a hold request reaches the store; `release` opens when the test lets blocked requests go.
- Three `AtomicBoolean`s are the switches: block holds, be down, be reachable (this one starts true).

```java
  void blockHolds() {
    isBlocking.set(true);
  }

  void releaseHolds() {
    release.countDown();
  }

  boolean awaitHoldEntered() throws InterruptedException {
    return entered.await(LONGEST_HOLD_SECONDS, TimeUnit.SECONDS);
  }

  void goDown() {
    isDown.set(true);
  }

  void setReachable(boolean reachable) {
    isReachable.set(reachable);
  }
```

The test-facing controls: `blockHolds()` turns on blocking; `releaseHolds()` opens the `release` gate; `awaitHoldEntered()` waits up to 30 seconds until a request is inside the store and returns whether it arrived; `goDown()` makes every call throw; `setReachable(boolean)` sets the answer of the readiness check.

```java
  @Override
  public Result<GrantedHold, HoldRefusal> hold(HoldCommand command)
      throws StoreUnavailableException {
    if (isDown.get()) {
      throw new StoreUnavailableException("the test store is down", new IllegalStateException());
    }
    entered.countDown();
    if (isBlocking.get()) {
      try {
        if (!release.await(LONGEST_HOLD_SECONDS, TimeUnit.SECONDS)) {
          throw new StoreUnavailableException("never released", new IllegalStateException());
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new StoreUnavailableException("interrupted", interrupted);
      }
    }
    if (command.seat().equals("T-1000")) {
      return Result.error(new HoldRefusal.SeatTaken());
    }
    if (command.seat().equals("T-999")) {
      return Result.error(new HoldRefusal.KeyReused());
    }
    if (command.seat().equals("T-998")) {
      return Result.error(new HoldRefusal.NoSuchSeat());
    }
    return Result.success(
        new GrantedHold(
            new Hold("11111111-2222-3333-4444-555555555555", 1, command.seat(), "fan-1", EXPIRES),
            false));
  }
```

`hold` follows a script.

1. If down: throw `StoreUnavailableException("the test store is down", ...)`.
2. Open `entered`, so a test that waits knows a request is in.
3. If blocking: wait on `release` for up to 30 seconds. On timeout throw `"never released"`. If the waiting thread is interrupted, set the interrupt flag again and throw `"interrupted"`.
4. Special seats choose the answer: `T-1000` gives `SeatTaken`, `T-999` gives `KeyReused`, `T-998` gives `NoSuchSeat`. The three trigger labels are valid seats at the very end of the arena. They are unlikely to be chosen by accident in a test that wants the normal answer, and no database is involved in these tests.
5. Anything else: a granted hold with the fixed id `11111111-2222-3333-4444-555555555555`, the customer `fan-1` and the expiry `EXPIRES`.

```java
  @Override
  public Optional<Hold> find(String holdId) throws StoreUnavailableException {
    if (isDown.get()) {
      throw new StoreUnavailableException("the test store is down", new IllegalStateException());
    }
    return holdId.startsWith("11111111")
        ? Optional.of(new Hold(holdId, 1, "A-7", "fan-1", EXPIRES))
        : Optional.empty();
  }

  @Override
  public boolean isReachable() {
    return isReachable.get();
  }
}
```

`find` throws when down. Otherwise it answers with a hold if the id starts with `11111111`, and with an empty `Optional` for every other id. `isReachable` returns the switch.

### `seatwave/LifecycleTest.java`

```java
package io.plainround.seatwave.balancer.seatwave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LifecycleTest {

  private static final Duration LONG_WAIT = Duration.ofSeconds(10);
  private static final Duration SHORT_DEADLINE = Duration.ofMillis(50);
  private static final int RACERS = 8;
  // Tries per racer after draining began, before the test lets the drain finish.
  private static final long ATTEMPTS = 1_000;
```

The imports show the tools: `assertThat`, `assertThatThrownBy` (for expected exceptions), `await` (Awaitility), executors and futures, atomics. `class LifecycleTest` has no `public`: JUnit does not need it. Constants: a long wait of 10 seconds (a safety limit, not an expected duration), a short deadline of 50 ms, 8 racers, and, new in 1.1.0, `ATTEMPTS`: 1,000 tries per racer after draining began, which the race test waits for before it lets the drain finish (the comment says it).

```java
  @Test
  void refusesEveryRequestWhileStarting() {
    Lifecycle lifecycle = new Lifecycle();

    assertThat(lifecycle.tryAdmit()).isEmpty();
    assertThat(lifecycle.state()).isEqualTo(LifecycleState.STARTING);
  }
```

**`refusesEveryRequestWhileStarting`.** A new lifecycle is in `STARTING`. `tryAdmit()` must return an empty `Optional` and the state must still be `STARTING`. `assertThat(optional).isEmpty()` is AssertJ for "the optional has no value".

```java
  @Test
  void admitsAndCountsRequestsOnceReady() {
    Lifecycle lifecycle = new Lifecycle();
    lifecycle.markReady();

    Optional<Lifecycle.Admission> admission = lifecycle.tryAdmit();

    assertThat(admission).isPresent();
    assertThat(lifecycle.inFlight()).isEqualTo(1);
  }
```

**`admitsAndCountsRequestsOnceReady`.** After `markReady()`, `tryAdmit()` returns a ticket and `inFlight()` is 1.

```java
  @Test
  void countsAnAdmissionClosedTwiceOnlyOnce() {
    Lifecycle lifecycle = new Lifecycle();
    lifecycle.markReady();
    Lifecycle.Admission first = lifecycle.tryAdmit().orElseThrow();
    lifecycle.tryAdmit().orElseThrow();

    first.close();
    first.close();

    assertThat(lifecycle.inFlight()).isEqualTo(1);
  }
```

**`countsAnAdmissionClosedTwiceOnlyOnce`.** Two admissions, so the count is 2. `first.close()` twice. The count must be 1, not 0: the second `close()` did nothing. This pins the `compareAndSet` in `Admission`. `lifecycle.tryAdmit().orElseThrow()` unwraps the `Optional` and fails the test with an exception if it is empty.

```java
  @Test
  void refusesNewRequestsOnceDrainingStarts() {
    Lifecycle lifecycle = new Lifecycle();
    lifecycle.markReady();

    lifecycle.startDraining();

    assertThat(lifecycle.tryAdmit()).isEmpty();
    assertThat(lifecycle.state()).isEqualTo(LifecycleState.DRAINING);
  }

  @Test
  void neverBecomesReadyAgainAfterDrainingStarted() {
    Lifecycle lifecycle = new Lifecycle();
    lifecycle.startDraining();

    boolean isReady = lifecycle.markReady();

    assertThat(isReady).isFalse();
    assertThat(lifecycle.tryAdmit()).isEmpty();
  }
```

Two tests of the gate.

- **`refusesNewRequestsOnceDrainingStarts`**: `markReady()`, `startDraining()`, then `tryAdmit()` is empty and the state is `DRAINING`.
- **`neverBecomesReadyAgainAfterDrainingStarted`**: `startDraining()` on a new lifecycle (the stop-signal-during-start case) and then `markReady()` must return `false` and `tryAdmit()` must stay empty. "Shutdown wins."

```java
  @Test
  void stopsAtOnceWhenNothingIsInFlight() throws InterruptedException {
    Lifecycle lifecycle = new Lifecycle();
    lifecycle.markReady();

    Lifecycle.DrainResult result = lifecycle.awaitDrained(LONG_WAIT);

    assertThat(result.isClean()).isTrue();
    assertThat(lifecycle.state()).isEqualTo(LifecycleState.STOPPED);
  }
```

**`stopsAtOnceWhenNothingIsInFlight`.** With nothing in flight, `awaitDrained(10 s)` returns a clean result at once and the state becomes `STOPPED`. `throws InterruptedException` is in the signature because `awaitDrained` declares it.

```java
  @Test
  void waitsUntilTheLastInFlightRequestFinishes() throws Exception {
    Lifecycle lifecycle = new Lifecycle();
    lifecycle.markReady();
    Lifecycle.Admission running = lifecycle.tryAdmit().orElseThrow();
    try (ExecutorService drainer = Executors.newSingleThreadExecutor()) {
      Future<Lifecycle.DrainResult> drained =
          drainer.submit(() -> lifecycle.awaitDrained(LONG_WAIT));
      // The drain must still be waiting: the request has not finished.
      await().atMost(LONG_WAIT).until(() -> lifecycle.state() == LifecycleState.DRAINING);
      assertThat(drained.isDone()).isFalse();

      running.close();

      // The last release wakes the drain: it ends at once, not when the 10 s deadline passes
      // (a lost wake-up would still end "clean", only 10 s late).
      Lifecycle.DrainResult result = drained.get(LONG_WAIT.toSeconds(), TimeUnit.SECONDS);
      assertThat(result.inFlightLeft()).isZero();
      assertThat(result.waited()).isLessThan(LONG_WAIT.dividedBy(2));
    }
  }
```

**`waitsUntilTheLastInFlightRequestFinishes`.** The first test with two threads.

1. One request is admitted and kept (`running`).
2. A single-thread executor runs `awaitDrained(10 s)` in the background and returns a `Future`, a promise of its result. `try (ExecutorService drainer = ...)` closes the executor at the end.
3. `await().atMost(10 s).until(() -> state == DRAINING)` waits, without a sleep, until the background thread has started draining. This is how the test knows the drain is waiting and not finished.
4. `drained.isDone()` must be false: the drain must still be waiting, because the request has not finished.
5. `running.close()` ends the request.
6. `drained.get(10, SECONDS)` collects the result; `inFlightLeft()` must be zero.
7. New in 1.1.0: `result.waited()`, how long the drain waited, must be less than half the long wait (`LONG_WAIT.dividedBy(2)`, 5 s).

Step 7 closes a gap. The drain sleeps on a **condition** (a place where a thread waits until another thread signals it). When the last request ends, `Lifecycle.release` calls `idle.signalAll()` to wake the drain. If that call were missing, the drain would sleep until its full 10 s deadline, wake up, see zero in flight and still return a clean result; the comment in the test calls this a **lost wake-up**. In version 1.0.0 the test passed with the call removed, only 10 seconds later. Now it fails: the mutation check removes `idle.signalAll()`, and this test (and `SeatWaveServerTest`'s drain test) fails. Half the wait is a wide margin: a woken drain ends within milliseconds.

```java
  @Test
  void stopsAtTheDeadlineWithTheRequestsStillInFlight() throws InterruptedException {
    Lifecycle lifecycle = new Lifecycle();
    lifecycle.markReady();
    lifecycle.tryAdmit().orElseThrow();

    Lifecycle.DrainResult result = lifecycle.awaitDrained(SHORT_DEADLINE);

    assertThat(result.inFlightLeft()).isEqualTo(1);
    assertThat(result.isClean()).isFalse();
    assertThat(lifecycle.state()).isEqualTo(LifecycleState.STOPPED);
  }
```

**`stopsAtTheDeadlineWithTheRequestsStillInFlight`.** One request is admitted and never closed. `awaitDrained(50 ms)` must return after the deadline with `inFlightLeft()` equal to 1, `isClean()` false, and the state `STOPPED`: the deadline wins over a stuck request.

```java
  @Test
  void rejectsANegativeDeadline() {
    Lifecycle lifecycle = new Lifecycle();

    assertThatThrownBy(() -> lifecycle.awaitDrained(Duration.ofMillis(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be negative");
  }
```

**`rejectsANegativeDeadline`.** `assertThatThrownBy(() -> ...)` runs the code in the brackets and checks the exception: an `IllegalArgumentException` whose message contains `cannot be negative`.

```java
  @Test
  void stopsWhenTheWaitingThreadIsInterrupted() {
    Lifecycle lifecycle = new Lifecycle();
    lifecycle.markReady();
    lifecycle.tryAdmit().orElseThrow();
    Thread.currentThread().interrupt();

    assertThatThrownBy(() -> lifecycle.awaitDrained(LONG_WAIT))
        .isInstanceOf(InterruptedException.class);
    assertThat(lifecycle.state()).isEqualTo(LifecycleState.STOPPED);
  }
```

**`stopsWhenTheWaitingThreadIsInterrupted`.** The test sets its own thread's interrupt flag with `Thread.currentThread().interrupt()` and then calls `awaitDrained`. The wait notices the flag at once and throws `InterruptedException`. The test checks that and that the state is `STOPPED` anyway ("a shutdown that was interrupted is still a shutdown"). The exception clears the flag, so the test thread is left clean.

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

**`admitsNothingAfterDrainingStartsEvenUnderARace`.** The module's central test, rebuilt in 1.1.0 so that the race really happens.

1. A ready lifecycle, and one admission `held` that the test keeps open. While it is open, the drain cannot finish, so the racers keep running after draining began. The comment says it.
2. A flag `drainingStarted`; two counters, `attemptsAfterDrain` (tries made after the flag was set) and `admittedAfterDrain` (violations); and a latch `racing` with a count of 8, one per racer.
3. Eight racer tasks start on a pool of eight threads. Each loops until the state is `STOPPED`. In each turn it **reads the flag first**, then calls `tryAdmit()`. If an admission is granted and the flag was already true, the gate let a request in after draining began, and the violation counter goes up. Each admission is closed at once so the in-flight count stays small. A turn that began after the flag was set also counts one attempt.
4. On its first turn each racer counts the latch down once (`isCounted` remembers that it did). The comment says why: the drain starts only when every racer is inside its loop.
5. The main thread waits for the latch (`racing.await(10 s)` must return true), then calls `startDraining()`, and only afterwards sets the flag. Because the flag is set after `startDraining()` returns, a racer that reads `true` read it after the state had changed, so any admission it then gets is a violation.
6. `await().atMost(10 s).until(() -> attemptsAfterDrain >= 8 x 1,000)`: the test waits until the racers together have tried at least 8,000 times while draining. Only then does it close `held`, so the drain can end.
7. `awaitDrained(10 s)` runs, and each racer's future is collected.
8. The violation counter must be zero, and the drain must be clean.

Why the order is right: if the code read the flag after asking, a request admitted just before draining began could be misjudged as a violation. Reading before asking is the strict direction.

Why it was rebuilt. In version 1.0.0 the drain could finish before the racer threads had even started; they then saw `STOPPED` and left having proven nothing. With the gate broken on purpose (admitting in any state but `STOPPED`), the old test failed only two runs in six. Now every racer is in its loop before the drain starts, and the drain cannot end before 8,000 tries were made against the draining gate. The mutation check applies that same broken gate (`if (state == LifecycleState.STARTING)` in place of `if (state != LifecycleState.READY)`) and runs this test alone, and it fails.

### `seatwave/ServiceSettingsTest.java`

```java
package io.plainround.seatwave.balancer.seatwave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import com.zaxxer.hikari.HikariDataSource;
import io.plainround.seatwave.balancer.config.SettingsReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ServiceSettingsTest {
```

The imports and the class. The tests use `Properties` objects built in memory, and two real files. New in 1.1.0: `HikariDataSource` (the connection pool, see `hikaricp.md`) for the pool test, `Files` and `StandardCharsets` to read `compose.yaml`, and `Pattern` and `Matcher` to find a line in it.

```java
  @Test
  void readsTheContainerSettingsShippedWithTheProject() throws IOException {
    Result<ServiceSettings, List<String>> read =
        ServiceSettings.from(
            new SettingsReader(
                SettingsReader.load(Path.of("config/seatwave-container.properties"))));

    ServiceSettings settings =
        switch (read) {
          case Result.Success<ServiceSettings, List<String>>(ServiceSettings checked) -> checked;
          case Result.Error<ServiceSettings, List<String>>(List<String> problems) ->
              throw new AssertionError("the shipped settings have problems: " + problems);
        };
    assertThat(settings.database().poolSize()).isEqualTo(16);
    assertThat(settings.drainDeadline()).isEqualTo(Duration.ofSeconds(20));
  }
```

**`readsTheContainerSettingsShippedWithTheProject`.** It reads the real file `config/seatwave-container.properties` through `SettingsReader.load`, passes it to `ServiceSettings.from`, and uses a `switch` over the `Result`: a success gives the settings, an error throws an `AssertionError` that lists the problems. Then it checks two values: the pool size is 16 and the drain deadline is 20 seconds. This test keeps the shipped file valid: if someone edits the file wrongly, the build fails here, not when the stack starts. Since 1.1.0 `config/` is an input of the test task, so the mutation check's `db.poolSize=0` makes this test fail even without `cleanTest`.

```java
  @Test
  void givesDockerStopMoreTimeThanTheWorstCaseShutdown() throws IOException {
    ServiceSettings shipped =
        switch (ServiceSettings.from(
            new SettingsReader(
                SettingsReader.load(Path.of("config/seatwave-container.properties"))))) {
          case Result.Success<ServiceSettings, List<String>>(ServiceSettings checked) -> checked;
          case Result.Error<ServiceSettings, List<String>>(List<String> problems) ->
              throw new AssertionError(problems.toString());
        };
    String compose = Files.readString(Path.of("compose.yaml"), StandardCharsets.UTF_8);
    Matcher grace = Pattern.compile("\n  stop_grace_period: ([0-9]+)s\n").matcher(compose);
    assertThat(grace.find()).isTrue();

    // The drain deadline, then the wait for the workers: docker must not SIGKILL inside either.
    Duration worstCaseStop = shipped.drainDeadline().plus(SeatWaveServer.WORKER_STOP_WAIT);

    assertThat(Duration.ofSeconds(Long.parseLong(grace.group(1)))).isGreaterThan(worstCaseStop);
  }
```

**`givesDockerStopMoreTimeThanTheWorstCaseShutdown`.** New in 1.1.0. `docker stop` sends the instance the signal SIGTERM (please stop), waits `stop_grace_period`, then sends SIGKILL (stop now, with no clean-up). The instance's longest clean stop is its drain deadline plus the time it then gives its worker threads to finish. If Docker's wait were shorter, a draining instance could be killed in the middle.

1. Read the shipped settings, as in the test above.
2. Read `compose.yaml` as text and find the line `  stop_grace_period: <n>s` with a pattern. `([0-9]+)` captures the number. `grace.find()` must be true: the line exists.
3. `worstCaseStop` is the drain deadline (20 s) plus `SeatWaveServer.WORKER_STOP_WAIT` (5 s): 25 s. The comment names both parts.
4. The grace period read from the file (30 s) must be greater than 25 s.

The test reads both numbers from the files the stack really uses, so a change to either is checked. The mutation check sets `stop_grace_period: 25s`, and this test fails (25 is not greater than 25).

```java
  @Test
  void acceptsAPasswordFileInALongFolderWithSpaces() {
    Properties properties = valid();
    String deep = "/tmp/a folder with spaces/" + "x".repeat(240) + "/db.password";
    properties.setProperty("db.passwordFile", deep);

    Result<ServiceSettings, List<String>> read =
        ServiceSettings.from(new SettingsReader(properties));

    assertThat(read).isInstanceOf(Result.Success.class);
  }
```

**`acceptsAPasswordFileInALongFolderWithSpaces`.** The password file path is `/tmp/a folder with spaces/` plus 240 letters `x` plus `/db.password`, which is over 250 characters and has spaces. The result must be a `Success`. It pins the fix of a Module 9 defect, where the path rule was too strict. It does not check that the path round-trips; it only checks acceptance.

```java
  @Test
  void reportsEveryMissingKeyAndBrokenRuleTogether() {
    Properties properties = valid();
    properties.remove("db.url");
    properties.setProperty("http.adminPort", properties.getProperty("http.port"));
    properties.setProperty("db.poolSize", "65");

    Result<ServiceSettings, List<String>> read =
        ServiceSettings.from(new SettingsReader(properties));

    assertThat(read)
        .isEqualTo(
            Result.error(
                List.of(
                    "db.url is missing",
                    "http.port and http.adminPort must differ (0 means any free port)",
                    "db.poolSize must not be larger than http.workerThreads: a connection no worker"
                        + " can use is held open at the database for nothing")));
  }
```

**`reportsEveryMissingKeyAndBrokenRuleTogether`.** Start from a valid set. Remove `db.url`, give the admin port the same number as the port, and set the pool size to 65 (more than the 64 workers). The result must equal an error with exactly three sentences, in this order: `db.url is missing`, the two-ports sentence, and the pool-size sentence. The test checks the whole list, so a changed word, a missing sentence or a changed order fails it.

```java
  @Test
  void reportsAMissingWorkerCountOnceWithoutTheRuleThatNeedsIt() {
    Properties properties = valid();
    properties.remove("http.workerThreads");

    Result<ServiceSettings, List<String>> read =
        ServiceSettings.from(new SettingsReader(properties));

    assertThat(read).isEqualTo(Result.error(List.of("http.workerThreads is missing")));
  }
```

**`reportsAMissingWorkerCountOnceWithoutTheRuleThatNeedsIt`.** New in 1.1.0. With `http.workerThreads` removed, the reader reports it missing, and the rule "pool not larger than workers" must not run on a value nobody wrote: the result is exactly the one line `http.workerThreads is missing`. The code runs the rule only when both numbers were read (`workersRead && poolRead`). The mutation check drops that condition, a second, misleading line appears, and this test fails.

```java
  @Test
  void refusesAConnectTimeoutTheConnectionPoolCannotUse() {
    // HikariCP throws for a connectionTimeout under 250 ms; it must be a settings problem instead.
    Properties properties = valid();
    properties.setProperty("db.connectTimeoutMillis", "249");

    Result<ServiceSettings, List<String>> read =
        ServiceSettings.from(new SettingsReader(properties));

    assertThat(read)
        .isEqualTo(
            Result.error(
                List.of("db.connectTimeoutMillis must be from 250 to 600000, but was 249")));
  }
```

**`refusesAConnectTimeoutTheConnectionPoolCannotUse`.** New in 1.1.0. HikariCP, the connection pool, refuses a connection timeout under 250 ms by throwing an exception, as the comment says. In version 1.0.0 the settings allowed 1 ms, so a value such as 249 passed the settings check and then crashed the start with a stack trace. Now the lowest allowed value is 250, and 249 gives exactly one problem: `db.connectTimeoutMillis must be from 250 to 600000, but was 249`.

```java
  @Test
  void opensThePoolWithTheShortestConnectTimeoutTheSettingsAllow() throws IOException {
    Properties properties = valid();
    properties.setProperty("db.connectTimeoutMillis", "250");
    ServiceSettings settings =
        switch (ServiceSettings.from(new SettingsReader(properties))) {
          case Result.Success<ServiceSettings, List<String>>(ServiceSettings checked) -> checked;
          case Result.Error<ServiceSettings, List<String>>(List<String> problems) ->
              throw new AssertionError(problems.toString());
        };
    Path password = Files.createTempFile("seatwave-password", ".txt");
    try {
      Files.writeString(password, "test-only", StandardCharsets.UTF_8);
      ServiceSettings.DatabaseSettings database =
          new ServiceSettings.DatabaseSettings(
              "jdbc:h2:mem:pool-" + System.nanoTime(),
              "sa",
              password,
              1,
              settings.database().connectTimeout(),
              settings.database().checkTimeout());

      try (HikariDataSource pool = DatabasePool.open(database, "seatwave-test")) {
        assertThat(pool.getConnectionTimeout()).isEqualTo(250);
      }
    } finally {
      Files.delete(password);
    }
  }
```

**`opensThePoolWithTheShortestConnectTimeoutTheSettingsAllow`.** The other half of the same rule: the lowest value the settings accept must be one HikariCP accepts.

1. Valid settings with `db.connectTimeoutMillis=250`, read through `ServiceSettings.from`.
2. `Files.createTempFile("seatwave-password", ".txt")` makes an empty file with a unique name in the system's temporary folder; the test writes `test-only` into it. The `try ... finally` deletes it whatever happens.
3. A `DatabaseSettings` is built by hand: an in-memory H2 URL with a unique name (`jdbc:h2:mem:pool-` and the time), the user `sa`, the password file, a pool of 1 connection, and the two timeouts from the settings.
4. `DatabasePool.open(database, "seatwave-test")` opens a real HikariCP pool; `try ( ... )` closes it. `pool.getConnectionTimeout()` must be 250.

If the settings' minimum were lower than HikariCP's, step 4 would throw for a value the settings had approved. The mutation check sets the minimum back to 1; then 249 passes the settings and the test above fails.

```java
  @Test
  void refusesAJdbcUrlForAnotherDatabase() {
    Properties properties = valid();
    properties.setProperty("db.url", "jdbc:mysql://10.0.0.1:3306/seatwave");

    Result<ServiceSettings, List<String>> read =
        ServiceSettings.from(new SettingsReader(properties));

    assertThat(read).isInstanceOf(Result.Error.class);
  }
```

**`refusesAJdbcUrlForAnotherDatabase`.** A MySQL URL must give an `Error`. It checks the kind of result, not the sentence.

```java
  @Test
  void refusesAHostNameAsTheBindAddress() {
    Properties properties = valid();
    properties.setProperty("http.bindAddress", "localhost");

    Result<ServiceSettings, List<String>> read =
        ServiceSettings.from(new SettingsReader(properties));

    assertThat(read)
        .isEqualTo(
            Result.error(
                List.of(
                    "http.bindAddress must match [0-9.]{7,15}|[0-9a-fA-F:]{2,39}, but was"
                        + " localhost")));
  }
```

**`refusesAHostNameAsTheBindAddress`.** Since 1.1.0 it really tests a host name: `localhost`. A host name would need a name lookup before the server could start, so only an IP address is allowed. The value fails the first, character-level rule, and the exact sentence is pinned: `http.bindAddress must match [0-9.]{7,15}|[0-9a-fA-F:]{2,39}, but was localhost`. The pattern accepts 7 to 15 digits and dots (an IPv4 address) or 2 to 39 hexadecimal digits and colons (an IPv6 address). In version 1.0.0 this test used `999.1.1.1`, so its name promised more than it did; that case moved to the next test.

```java
  @Test
  void refusesDottedNumbersThatAreNotAnIpAddress() {
    Properties properties = valid();
    properties.setProperty("http.bindAddress", "999.1.1.1");

    Result<ServiceSettings, List<String>> read =
        ServiceSettings.from(new SettingsReader(properties));

    assertThat(read)
        .isEqualTo(
            Result.error(List.of("http.bindAddress must be an IP address, but was 999.1.1.1")));
  }
```

**`refusesDottedNumbersThatAreNotAnIpAddress`.** `999.1.1.1` passes the character rule (digits and dots) and fails `InetAddress.ofLiteral`, because 999 is above 255. The expected result is the exact sentence `http.bindAddress must be an IP address, but was 999.1.1.1`.

```java
  @Test
  void cannotBeBuiltWithOnePortForBothListeners() {
    ServiceSettings good = TestSettings.withDrainDeadline(Duration.ZERO);

    assertThatThrownBy(() ->
            new ServiceSettings(
                good.bindAddress(), 8080, 8080, 1, 1, 1, good.database(), Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

**`cannotBeBuiltWithOnePortForBothListeners`.** Takes valid settings from `TestSettings`, then calls the constructor directly with 8080 for both ports. It expects `IllegalArgumentException`: the compact constructor's rule.

```java
  static Properties valid() {
    Properties properties = new Properties();
    properties.setProperty("http.bindAddress", "127.0.0.1");
    properties.setProperty("http.port", "21090");
    properties.setProperty("http.adminPort", "21091");
    properties.setProperty("http.backlog", "50");
    properties.setProperty("http.workerThreads", "64");
    properties.setProperty("http.queueCapacity", "100");
    properties.setProperty("db.url", "jdbc:postgresql://10.210.10.5:5432/seatwave");
    properties.setProperty("db.user", "seatwave");
    properties.setProperty("db.passwordFile", "build/secrets/db.password");
    properties.setProperty("db.poolSize", "16");
    properties.setProperty("db.connectTimeoutMillis", "1000");
    properties.setProperty("readiness.checkTimeoutMillis", "1000");
    properties.setProperty("drain.deadlineMillis", "20000");
    return properties;
  }
}
```

`valid()` returns a complete, correct `Properties` object with the thirteen keys. It is package-visible, and `SeatWaveCliTest` reuses it. The values: loopback, ports 21090 and 21091, 64 workers, a queue of 100, a PostgreSQL URL, pool 16, timeouts 1000, drain 20000.

A gap worth knowing: no test covers the minimum of `readiness.checkTimeoutMillis`.

### `seatwave/SeatWaveCliTest.java`

```java
package io.plainround.seatwave.balancer.seatwave;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SeatWaveCliTest {

  @TempDir Path folder;

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();
```

The imports and fields. `@TempDir Path folder` gives each test a new folder. Two `ByteArrayOutputStream`s collect what the program prints on standard output and standard error, so the tests can read it. A `ByteArrayOutputStream` is a stream that keeps its bytes in memory.

```java
  @Test
  void refusesAnInstanceNameThatIsNotAName() {
    Optional<SeatWaveCli.Running> running =
        start("config/seatwave-container.properties", "Seat Wave");

    assertThat(running).isEmpty();
    assertThat(text(err)).contains("usage: SeatWaveCli");
  }
```

**`refusesAnInstanceNameThatIsNotAName`.** `Seat Wave` (with a space and capitals) does not fit `[a-z][a-z0-9-]{0,31}`. `start` must return an empty `Optional` and print a line that contains `usage: SeatWaveCli`. In version 1.0.0 the usage line said `usage: serve`, the name of a command that does not exist; it now names the class a reader would run.

```java
  @Test
  void saysWhichPasswordFileIsMissing() throws IOException {
    Path settings = settings(folder.resolve("absent.password"));

    Optional<SeatWaveCli.Running> running = start(settings.toString(), "seatwave-1");

    assertThat(running).isEmpty();
    assertThat(text(err))
        .contains("the database password file", "absent.password", "does not exist");
  }
```

**`saysWhichPasswordFileIsMissing`.** The settings point at a password file that does not exist. `start` must return empty, and the error output must contain `the database password file`, `absent.password` and `does not exist`. `contains(a, b, c)` checks that all three pieces appear.

```java
  @Test
  void startsReadyAndStopsWithACleanDrain() throws IOException {
    Path password =
        Files.writeString(folder.resolve("db.password"), "lab-only\n", StandardCharsets.UTF_8);
    Path settings = settings(password);

    SeatWaveCli.Running running = start(settings.toString(), "seatwave-1").orElseThrow();
    SeatWaveCli.stop(running, new PrintStream(out, true, StandardCharsets.UTF_8));

    assertThat(text(out))
        .contains("seatwave-1 serving on 127.0.0.1:")
        .contains("seatwave-1 drained cleanly: 0 in flight left");
    assertThat(running.server().state()).isEqualTo(LifecycleState.STOPPED);
  }
```

**`startsReadyAndStopsWithACleanDrain`.** The one test of the whole start-up path.

1. It writes a password file (`lab-only`) in the temp folder and a settings file that points at it, at port 0 and an in-memory H2 URL (`jdbc:h2:mem:cli-test`).
2. `start` must succeed: `orElseThrow()` fails the test if it comes back empty.
3. `SeatWaveCli.stop(running, out)` drains and closes.
4. The output must contain `seatwave-1 serving on 127.0.0.1:` and `seatwave-1 drained cleanly: 0 in flight left`, and the server's state must be `STOPPED`.

It runs a real HikariCP pool over H2 and a real server. It does not send a request.

```java
  private Optional<SeatWaveCli.Running> start(String settings, String name) {
    return SeatWaveCli.start(
        new String[] {settings, name},
        new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8));
  }

  private Path settings(Path passwordFile) throws IOException {
    Properties properties = ServiceSettingsTest.valid();
    properties.setProperty("http.port", "0");
    properties.setProperty("http.adminPort", "0");
    properties.setProperty("db.url", "jdbc:h2:mem:cli-test");
    properties.setProperty("db.passwordFile", passwordFile.toString());
    properties.setProperty("drain.deadlineMillis", "1000");
    Path file = folder.resolve("seatwave.properties");
    try (FileOutputStream stream = new FileOutputStream(file.toFile())) {
      properties.store(stream, "test");
    }
    return file;
  }

  private static String text(ByteArrayOutputStream stream) {
    return stream.toString(StandardCharsets.UTF_8);
  }
}
```

The helpers. `start` calls `SeatWaveCli.start` with two arguments and two `PrintStream`s (UTF-8, auto-flushing) that write into the byte streams. `settings(passwordFile)` starts from `ServiceSettingsTest.valid()`, overrides the ports (0), the database URL, the password file and a 1 second drain, and writes the file with `Properties.store`. `text(stream)` decodes a byte stream as UTF-8.

What the CLI tests leave out: `main` itself (the part that exits with status 2 and registers the shutdown hook) is not run by any test.

## Try it yourself

All commands run in the project folder. Timings differ on every run.

1. Run the lifecycle tests. Eleven lines end in `PASSED`.

```bash
./gradlew --offline cleanTest test --tests '*LifecycleTest' 2>&1 | grep -E "PASSED|FAILED|BUILD"
```

2. Break the gate on purpose, in a copy, and watch tests fail. Copy the project, change one line, run the tests, and throw the copy away.

```bash
rm -rf build/gate-check && mkdir build/gate-check
rsync -a --exclude build --exclude .gradle ./ build/gate-check/
cd build/gate-check
sed -i.bak 's/if (state != LifecycleState.READY) {/if (state == LifecycleState.STOPPED) {/' src/main/java/io/plainround/seatwave/balancer/seatwave/Lifecycle.java
./gradlew --offline cleanTest test --tests '*LifecycleTest' 2>&1 | grep -E "FAILED|BUILD"
cd ../.. && rm -rf build/gate-check
```

In the run for these notes, the output was:

```text
> Task :test FAILED
LifecycleTest > refusesNewRequestsOnceDrainingStarts() FAILED
LifecycleTest > neverBecomesReadyAgainAfterDrainingStarted() FAILED
LifecycleTest > admitsNothingAfterDrainingStartsEvenUnderARace() FAILED
LifecycleTest > refusesEveryRequestWhileStarting() FAILED
BUILD FAILED in 2s
```

The change lets the gate admit while starting and while draining. Four tests fail, the race test among them: in version 1.0.0 the race test caught this change only two runs in six.

3. Remove the wake-up, in a copy, and watch the time check catch it.

```bash
rm -rf build/wake-check && mkdir build/wake-check
rsync -a --exclude build --exclude .gradle ./ build/wake-check/
cd build/wake-check
sed -i.bak '/idle.signalAll();/d' src/main/java/io/plainround/seatwave/balancer/seatwave/Lifecycle.java
./gradlew --offline cleanTest test --tests '*LifecycleTest' 2>&1 | grep -E "FAILED|BUILD"
cd ../.. && rm -rf build/wake-check
```

In the run for these notes:

```text
LifecycleTest > waitsUntilTheLastInFlightRequestFinishes() FAILED
> Task :test FAILED
BUILD FAILED in 11s
```

The build takes about 10 seconds longer: the drain without its wake-up sleeps to its 10 s deadline, and the new check on `waited()` turns that into a failure.

4. Run the settings and command-line tests.

```bash
./gradlew --offline cleanTest test --tests '*ServiceSettingsTest' --tests '*SeatWaveCliTest' 2>&1 | grep -E "PASSED|FAILED|BUILD"
```

Fourteen tests pass (eleven and three).

5. Look at how long tests take. Gradle writes one XML file per class; this prints the slowest tests of the last run.

```bash
./gradlew --offline cleanTest test > /dev/null 2>&1
python3 -c "
import glob,re
rows=[]
for x in glob.glob('build/test-results/test/*.xml'):
    for m in re.finditer(r'<testcase name=\"([^\"]+)\" classname=\"([^\"]+)\" time=\"([0-9.]+)\"',open(x).read()):
        rows.append((float(m[3]),m[2].split('.')[-1],m[1]))
for r in sorted(rows,reverse=True)[:3]: print(r)
"
```

On the run for these notes the three slowest were:

```text
(1.123, 'HarnessCliTest', 'runsTheLoadWatchesTheBalancerAndReports()')
(0.615, 'RollingDeployTest', 'waitsTheSettleTimeBeforeTheNextInstance()')
(0.4, 'JdbcHoldStoreTest', 'letsExactlyOneOfManyRacersHoldOneSeat()')
```

The slowest is the harness's integration test, which really sends load for 1.1 seconds. Second is the settle test of the deploy, which sleeps its 300 ms settle on purpose. No test of this part is among the slowest: the drain tests end as soon as the last request does.

## Mistakes people make

- **Sleeping to wait for a thread.** `Thread.sleep(100)` is too short on a slow machine and wastes time on a fast one. Here `await().until(...)` waits exactly as long as needed.
- **Real time in expiry tests.** A test that waits 300 seconds is not run. `MutableClock.advance` moves time instantly.
- **Sharing one database between tests.** Rows from one test would change the answers of another. `TestDatabase.create()` uses a random name for each.
- **A fixed port.** Another program, or a parallel test, may hold it. Port 0 takes any free one.
- **A race test that does not race.** A race test must make sure the threads are running when the event happens, and must keep the window open long enough. The latch and the held admission do both. Check that it fails when the code is broken, as in step 2.
- **Reading the flag after the action.** In the race test, order decides whether a pass means anything.
- **Asserting the result but not the time.** A lost wake-up still ends "clean". The drain test now checks that the drain ended well before its deadline.
- **Checking a rule on a value that was never read.** A missing key already has its own problem line; a rule that compares it adds a second, wrong one. The "once" tests guard against that.
- **Two limits for one value.** The settings allowed a connect timeout that the pool refuses. The two pool tests pin the settings' minimum to the pool's.

## Check yourself

1. Why does `MutableClock.withZone` throw?
   Answer: to make a time-zone change loud, so a test cannot quietly pass with a clock that ignores the zone.
2. In the race test, why is the flag read before `tryAdmit` and set after `startDraining` returns?
   Answer: so that a racer that sees `true` is sure draining had already started when it asked; any admission it then gets is a real violation, never a mistaken one.
3. Why does the race test keep one admission open until 8,000 attempts were made?
   Answer: an open admission stops the drain from finishing, so the racers are still trying while the instance drains; without it the drain could end before the race began.
4. How does `givesDockerStopMoreTimeThanTheWorstCaseShutdown` compute the worst case, and what does it compare it with?
   Answer: the drain deadline from the shipped settings plus `SeatWaveServer.WORKER_STOP_WAIT` (20 s + 5 s), against `stop_grace_period` read from `compose.yaml` (30 s).
5. Why must the lowest allowed `db.connectTimeoutMillis` be 250?
   Answer: HikariCP throws for a connection timeout under 250 ms, so a lower value would pass the settings check and then crash the start.

## Further reading

- JUnit 6 user guide (lifecycle, `@TempDir`, assertions): https://docs.junit.org/current/user-guide/
- AssertJ core documentation: https://assertj.github.io/doc/
- Awaitility usage: https://github.com/awaitility/awaitility/wiki/Usage
- Java SE 25 API, `java.util.concurrent.CountDownLatch`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/CountDownLatch.html
- Java SE 25 API, `java.util.concurrent.locks.Condition` (signals and lost wake-ups): https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/locks/Condition.html
- Java SE 25 API, `java.time.Clock`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/Clock.html
- HikariCP configuration (`connectionTimeout`, lowest accepted value 250 ms): https://github.com/brettwooldridge/HikariCP#gear-configuration-knobs-baby
- Docker Compose file reference, `stop_grace_period`: https://docs.docker.com/reference/compose-file/services/#stop_grace_period
- Gradle 9.9.1 user guide, testing in Java and the `test` task: https://docs.gradle.org/9.9.1/userguide/java_testing.html
