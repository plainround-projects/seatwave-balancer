# The `seatwave` tests, part 2: the store, the server and the routes (line by line)

This is part 2 of 2. Part 1 (`tests-seatwave-walkthrough-1.md`) reads the helpers (`TestSettings`, `MutableClock`, `TestDatabase`, `ScriptedHoldStore`) and the tests `LifecycleTest`, `ServiceSettingsTest` and `SeatWaveCliTest`. This part reads the helper `JdbcStandIns` and the tests `JdbcHoldStoreTest`, `SeatWaveServerTest` and `HoldRoutesTest`. Read part 1 first for the terms (test, assertion, fake, latch, race).

## What it is

Three test classes, 41 tests in all, and one helper class.

- `JdbcStandIns` (no tests): two stand-in data sources the store tests need, built with Java's **dynamic proxies**: one that never connects, and one that makes two racers meet at the `INSERT` of a hold.
- `JdbcHoldStoreTest` (15 tests): runs the real SQL of `JdbcHoldStore` on H2. It checks the rules (one seat, one owner; expiry; replay; reused key), races sixteen threads, and forces the duplicate-key race of two requests with one key.
- `SeatWaveServerTest` (7 tests): starts a real HTTP server on a free port and talks to it with Java's own HTTP client. It checks the gate, the two questions (`/health`, `/ready`), `HEAD`, the metrics and the drain.
- `HoldRoutesTest` (19 tests): calls the routes directly with a `Request` and reads the `Reply`. It checks every status and header without a network.

New terms.

- A **status code**, **header** and **body** are the parts of an HTTP reply; see `java-seatwave-walkthrough-2.md`.
- **Asynchronous** means "start it now, collect the result later". Java's `CompletableFuture` is a promise of a result that is not ready yet. `client.sendAsync(...)` sends a request and returns one.
- A **timeout** on a request makes the client give up after a time.
- A **fixed thread pool** runs a set number of threads. The tests use one to start sixteen racers at the same instant.
- A **data source** (`javax.sql.DataSource`) is the object a program asks for database connections. A **connection** (`java.sql.Connection`) is one open session with the database.
- A **barrier** (`CyclicBarrier`) is a meeting point: each thread that reaches it waits until a set number of threads are there, then all go on together.

## Why this repository uses it

- The store is where the module's promise is kept: "one seat, one owner" even when instances race. The only way to trust a race rule is to run a race, so `JdbcHoldStoreTest` does, with sixteen threads on a real database engine, and with a barrier that makes the rarest race happen every time.
- The server tests prove the drain as a client sees it: a request in flight finishes with `201`, a new request gets `503` with `Connection: close`, and the drain ends cleanly and promptly.
- The route tests keep every error status and header honest. They are fast because they need no network.
- Each rule that the balancer relies on is pinned by name here: "nothing was done" in the `503`, `Retry-After`, `Allow`, `private` for cached holds.

## Where it appears

| File | Tests | What it checks |
|---|---|---|
| `seatwave/JdbcStandIns.java` | 0 | Provides an unreachable data source and one that holds racers at the `INSERT`. |
| `seatwave/JdbcHoldStoreTest.java` | 15 | The SQL rules and the races, on H2. |
| `seatwave/SeatWaveServerTest.java` | 7 | The HTTP server, readiness, `HEAD`, metrics and the drain. |
| `seatwave/HoldRoutesTest.java` | 19 | Every route and every error answer. |

## How it works underneath

**Store tests.** `@BeforeEach` makes a fresh database and a store. Each test calls `store.hold(command)` and checks the `Result`. The clock is a `MutableClock`, so "300 seconds later" is one method call. The race helper starts a set of threads that all wait at a latch, opens the latch, and collects their `Result`s.

**Proxies.** A **dynamic proxy** is an object Java builds while the program runs. It claims to implement an interface (here `DataSource` or `Connection`), and every call on it goes to one function, the **invocation handler**, which receives the method and its arguments and decides what to do. `JdbcStandIns` uses a handler that passes most calls on to the real H2 object and adds one thing: a wait at the barrier before the `INSERT`, or a count of rollbacks. So the real SQL runs, and the test still controls the timing.

**Server tests.** Each test starts a `SeatWaveServer` on loopback with port 0, over a `ScriptedHoldStore`. The test finds the real ports with `server.port()` and `server.adminPort()`, sends HTTP requests with `java.net.http.HttpClient`, and checks status, headers and body.

**Route tests.** `HoldRoutes` is a plain class: `route(Request)` returns a `Routed` with a `Reply`. The test builds a `Request` by hand and reads the reply. No server, no sockets.

## Walkthrough of this repository's use

### `seatwave/JdbcStandIns.java`

New in 1.1.0.

```java
package io.plainround.seatwave.balancer.seatwave;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

/**
 * Data sources for the tests of {@link JdbcHoldStore} that a real database cannot give on demand:
 * one that never connects, and one that holds every racer at the INSERT of a hold until all have
 * reached it, so a race between two requests with one key happens every time, not by chance.
 */
final class JdbcStandIns {

  private static final long BARRIER_SECONDS = 10;

  private JdbcStandIns() {}
```

The imports come from `java.lang.reflect`, Java's tools for working with methods as objects: `Proxy` builds the stand-ins, `InvocationHandler` is the function that receives every call, `Method` is one method, `InvocationTargetException` wraps an exception thrown by a method called this way. The class comment says what the two stand-ins are for: "a real database cannot give on demand" a database that is down, or two racers that reach the `INSERT` together "every time, not by chance".

`BARRIER_SECONDS` is 10, the longest a racer waits at the barrier. The constructor is `private` and empty: nobody makes an object of this class; it only offers `static` methods.

```java
  /**
   * A data source whose every connection attempt fails, as a database that is down does.
   *
   * @return the data source
   */
  static DataSource unreachable() {
    return (DataSource)
        Proxy.newProxyInstance(
            JdbcStandIns.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("getConnection")) {
                throw new SQLException("the test database is down", "08001");
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }
```

`unreachable()` returns a `DataSource` that is a proxy. `Proxy.newProxyInstance` takes three things: a **class loader** (the part of Java that finds classes; the one that loaded this class is fine), the list of interfaces the proxy claims (only `DataSource`), and the handler, written as a lambda `(proxy, method, arguments) -> ...`.

- A call of `getConnection` throws `SQLException("the test database is down", "08001")`. The second argument is the **SQLState**, a five-character code of the SQL standard; class `08` means "connection exception", and `08001` is "the client could not establish the connection".
- Any other call throws `UnsupportedOperationException` with the method's name: the store only ever asks for a connection, so anything else would be a mistake worth seeing.

In version 1.0.0 the tests pointed H2 at port 1 of the machine and relied on nothing listening there. The proxy depends on nothing outside the test, and it fails at once: the two "database is down" tests no longer need seconds.

```java
  /**
   * Wraps a data source so that the INSERT of a hold waits until {@code racers} connections have
   * reached it, and counts rollbacks.
   *
   * @param real the database
   * @param racers how many connections must meet at the INSERT
   * @param rollbacks counts every rollback on every connection
   * @return the wrapped data source
   */
  static DataSource meetingAtTheInsert(DataSource real, int racers, AtomicInteger rollbacks) {
    CyclicBarrier barrier = new CyclicBarrier(racers);
    return (DataSource)
        Proxy.newProxyInstance(
            JdbcStandIns.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, arguments) -> {
              Object result = invoke(real, method, arguments);
              return method.getName().equals("getConnection")
                  ? wrapped((Connection) result, barrier, rollbacks)
                  : result;
            });
  }
```

`meetingAtTheInsert(real, racers, rollbacks)` wraps the real H2 data source.

- `new CyclicBarrier(racers)` is a barrier for `racers` threads; the tests pass 2.
- The proxy's handler first calls the same method on the real data source (`invoke`, below). If the method was `getConnection`, it returns the real connection wrapped in a second proxy (`wrapped`); otherwise it returns the real result unchanged. `condition ? a : b` is Java's conditional operator.

```java
  private static Connection wrapped(
      Connection real, CyclicBarrier barrier, AtomicInteger rollbacks) {
    InvocationHandler handler =
        (proxy, method, arguments) -> {
          if (method.getName().equals("prepareStatement")
              && ((String) arguments[0]).startsWith("INSERT INTO holds")) {
            meet(barrier);
          }
          if (method.getName().equals("rollback")) {
            rollbacks.incrementAndGet();
          }
          return invoke(real, method, arguments);
        };
    return (Connection)
        Proxy.newProxyInstance(
            JdbcStandIns.class.getClassLoader(), new Class<?>[] {Connection.class}, handler);
  }
```

`wrapped(real, barrier, rollbacks)` builds the connection proxy. Its handler looks at each call:

- `prepareStatement` with SQL text that starts with `INSERT INTO holds`: the store is about to insert the hold row. The handler calls `meet(barrier)` and waits there until the other racer arrives too. `(String) arguments[0]` reads the first argument, the SQL text; the cast `(String)` tells Java its type.
- `rollback`: the transaction is being undone; the counter goes up.
- Every call, after that, goes on to the real connection.

So both racers have done everything up to the `INSERT` (read the key, taken their seat) before either of them inserts.

```java
  private static void meet(CyclicBarrier barrier) throws SQLException {
    try {
      barrier.await(BARRIER_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new SQLException("interrupted at the test barrier", interrupted);
    } catch (BrokenBarrierException | TimeoutException notMet) {
      throw new SQLException("the racers never met at the INSERT", notMet);
    }
  }
```

`meet(barrier)` waits at most 10 seconds. If the thread is interrupted, it sets the interrupt flag again (so the interruption is not lost) and throws an `SQLException`. If the barrier is **broken** (another waiting thread gave up) or the time runs out, it throws `SQLException("the racers never met at the INSERT", ...)`. Turning these into `SQLException` lets them travel the store's normal error path, so a test that cannot set up its race fails with that sentence instead of hanging.

```java
  private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
    try {
      return method.invoke(target, arguments);
    } catch (InvocationTargetException thrown) {
      // The real exception, such as the database's own SQLException, not the reflection wrapper.
      throw thrown.getCause();
    }
  }
}
```

`invoke(target, method, arguments)` calls the method on the real object with `method.invoke`. When that method throws, reflection wraps the exception in an `InvocationTargetException`. The `catch` unwraps it and throws the real one, as the comment says, so the store sees H2's own `SQLException` with its SQLState, for example `23505`. `throws Throwable` allows any exception to pass.

### `seatwave/JdbcHoldStoreTest.java`

```java
package io.plainround.seatwave.balancer.seatwave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
```

The imports. `JdbcDataSource` is H2's data source. `Callable` is a task that returns a value (a `Runnable` that gives an answer). `Set` and `HashSet` are used to count distinct hold ids. New in 1.1.0: `Connection`, `ResultSet`, `SQLException`, `Statement` and `DataSource` from the JDBC library, for the count of held seats; `AtomicInteger` for the count of rollbacks.

```java
class JdbcHoldStoreTest {

  private static final Instant START = Instant.parse("2026-11-11T09:00:00Z");
  private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(1);
  private static final String SHA_A = "a".repeat(64);
  private static final String SHA_B = "b".repeat(64);
  private static final int RACERS = 16;
  private static final long WAIT_SECONDS = 30;

  private JdbcDataSource dataSource;
  private MutableClock clock;
  private JdbcHoldStore store;
```

- `START` is 09:00 on 11 November 2026 (UTC). `CHECK_TIMEOUT` is one second, the minimum the store accepts.
- `SHA_A` and `SHA_B` are two fake fingerprints of 64 `a` or `b` characters: `"a".repeat(64)`. The store never recomputes them, it compares them, so any 64 hexadecimal-looking characters will do.
- `RACERS = 16` and `WAIT_SECONDS = 30` size the race helper.
- Three fields: the data source, the clock and the store.

```java
  @BeforeEach
  void freshDatabase() {
    dataSource = TestDatabase.create();
    clock = new MutableClock(START);
    store = new JdbcHoldStore(dataSource, clock, CHECK_TIMEOUT);
  }
```

`@BeforeEach freshDatabase()` runs before every test: a new H2 database from the schema, a clock at `START`, a store on both. So every test starts with 20,000 free seats and no holds.

```java
  @Test
  void grantsAFreeSeatForThreeHundredSeconds() throws Exception {
    GrantedHold granted = granted(store.hold(command("\"k-1\"", SHA_A, "A-7")));

    assertThat(granted.isReplay()).isFalse();
    assertThat(granted.hold().seat()).isEqualTo("A-7");
    assertThat(granted.hold().expiresAt()).isEqualTo(START.plusSeconds(300));
  }
```

**`grantsAFreeSeatForThreeHundredSeconds`.** Holds `A-7` with key `"k-1"`. The helper `granted(...)` unwraps the result and fails the test if it was a refusal. The hold must not be a replay, must be for `A-7`, and must expire at `START` plus 300 seconds.

```java
  @Test
  void refusesASeatAnotherActiveHoldOwns() throws Exception {
    store.hold(command("\"k-1\"", SHA_A, "A-7"));

    Result<GrantedHold, HoldRefusal> second = store.hold(command("\"k-2\"", SHA_B, "A-7"));

    assertThat(second).isEqualTo(Result.error(new HoldRefusal.SeatTaken()));
  }
```

**`refusesASeatAnotherActiveHoldOwns`.** Key `k-1` holds `A-7`. Key `k-2` asks for the same seat, with a different fingerprint. The second result must equal `Result.error(new HoldRefusal.SeatTaken())`. Equality works because `Result` and the refusal types are records, and records compare by their contents.

```java
  @Test
  void grantsTheSeatAgainOnceTheHoldHasExpired() throws Exception {
    store.hold(command("\"k-1\"", SHA_A, "A-7"));
    clock.advance(Duration.ofSeconds(300));

    GrantedHold again = granted(store.hold(command("\"k-2\"", SHA_B, "A-7")));

    assertThat(again.isReplay()).isFalse();
  }
```

**`grantsTheSeatAgainOnceTheHoldHasExpired`.** After the first hold, the clock moves by exactly 300 seconds. A second key then gets the seat. This pins the edge: at the exact moment of expiry the hold is over, matching `hold_expires_at <= now` in the SQL and `isBefore` in `Hold.isActiveAt`.

```java
  @Test
  void replaysTheSameHoldForARetriedKey() throws Exception {
    GrantedHold first = granted(store.hold(command("\"k-1\"", SHA_A, "A-7")));

    GrantedHold retry = granted(store.hold(command("\"k-1\"", SHA_A, "A-7")));

    assertThat(retry.isReplay()).isTrue();
    assertThat(retry.hold()).isEqualTo(first.hold());
  }
```

**`replaysTheSameHoldForARetriedKey`.** The same key and fingerprint twice. The second answer must say `isReplay()` and carry a `Hold` equal to the first, field by field. Equality includes `expiresAt`, so it also proves the millisecond truncation (a database that keeps microseconds would otherwise return a slightly different time).

```java
  @Test
  void refusesAKeyReusedForAnotherRequest() throws Exception {
    store.hold(command("\"k-1\"", SHA_A, "A-7"));

    Result<GrantedHold, HoldRefusal> reused = store.hold(command("\"k-1\"", SHA_B, "A-8"));

    assertThat(reused).isEqualTo(Result.error(new HoldRefusal.KeyReused()));
  }
```

**`refusesAKeyReusedForAnotherRequest`.** Key `k-1` holds `A-7`; the same key with a different fingerprint and another seat (`A-8`) must give `KeyReused`. Nothing is held for `A-8`: the replay check comes first.

```java
  @Test
  void reportsASeatTheDatabaseDoesNotHave() throws Exception {
    Result<GrantedHold, HoldRefusal> missing =
        store.hold(new HoldCommand("\"k-1\"", SHA_A, 2, "A-7", "fan-1"));

    assertThat(missing).isEqualTo(Result.error(new HoldRefusal.NoSuchSeat()));
  }
```

**`reportsASeatTheDatabaseDoesNotHave`.** A command for event 2 (the schema only has event 1) must give `NoSuchSeat`. The test builds the command with `new HoldCommand(...)` directly, since the helper always uses event 1.

```java
  @Test
  void findsAHoldByItsIdentifier() throws Exception {
    GrantedHold granted = granted(store.hold(command("\"k-1\"", SHA_A, "A-7")));

    assertThat(store.find(granted.hold().holdId())).contains(granted.hold());
    assertThat(store.find("00000000-0000-0000-0000-000000000000")).isEmpty();
  }
```

**`findsAHoldByItsIdentifier`.** `store.find(id)` returns the hold just made (`contains(...)` is AssertJ for an `Optional` that holds an equal value). An unknown all-zero id gives an empty `Optional`.

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

**`letsExactlyOneOfManyRacersHoldOneSeat`.** The race on one seat.

- The helper `race(...)` runs sixteen commands at once. Racer *n* uses key `race-n` and seat `B-1`, so the keys differ and the seat is the same.
- Exactly one outcome must be a `Result.Success`. `outcome instanceof Result.Success<?, ?>` is a type test.
- Every other outcome must equal `Result.error(new SeatTaken())`. `filteredOn(...)` keeps the errors and `allMatch(...)` checks each one.

This is the module's invariant in database form. It passes because the `UPDATE`'s row lock makes the losers wait and then change zero rows.

```java
  @Test
  void answersRacingRetriesOfOneKeyWithOneHold() throws Exception {
    List<Result<GrantedHold, HoldRefusal>> outcomes =
        race(racer -> command("\"same-key\"", SHA_A, "C-1"));

    Set<String> holdIds = new HashSet<>();
    for (Result<GrantedHold, HoldRefusal> outcome : outcomes) {
      holdIds.add(granted(outcome).hold().holdId());
    }
    assertThat(holdIds).hasSize(1);
  }
```

**`answersRacingRetriesOfOneKeyWithOneHold`.** Sixteen racers with the same key, the same fingerprint and the same seat `C-1`. Every outcome must be a granted hold (the helper `granted` fails on a refusal), and the set of distinct hold ids must have exactly one element: one hold, fifteen replays.

Which code path does this exercise? The racers' `UPDATE`s queue behind one row lock on the seat. Those that wait find the seat held, change zero rows, and then re-read the key and replay. That is the branch in `holdInTransaction` (the re-read after a lost race). It is **not** the `catch` for the unique violation in `hold`; the next two tests reach that one.

```java
  @Test
  void answersTheLoserOfAKeyRaceWithTheWinnersHoldAndReleasesItsSeat() throws Exception {
    // Two requests with one key take two different seats, so no row lock orders them; both reach
    // the INSERT, and the unique key refuses the second (SQLState 23505). The same fingerprint
    // makes the loser a retry of the winner, so it must answer the winner's hold.
    AtomicInteger rollbacks = new AtomicInteger();
    JdbcHoldStore racing =
        new JdbcHoldStore(
            JdbcStandIns.meetingAtTheInsert(dataSource, 2, rollbacks), clock, CHECK_TIMEOUT);

    List<Result<GrantedHold, HoldRefusal>> outcomes =
        race(racing, 2, racer -> command("\"one-key\"", SHA_A, racer == 0 ? "D-1" : "D-2"));

    GrantedHold first = granted(outcomes.get(0));
    GrantedHold second = granted(outcomes.get(1));
    assertThat(first.hold().holdId()).isEqualTo(second.hold().holdId());
    assertThat(List.of(first.isReplay(), second.isReplay())).containsExactlyInAnyOrder(true, false);
    assertThat(rollbacks.get()).isEqualTo(1);
    assertThat(heldSeats()).isEqualTo(1);
  }
```

**`answersTheLoserOfAKeyRaceWithTheWinnersHoldAndReleasesItsSeat`.** New in 1.1.0. The comment explains the setup. Two requests carry one key but ask for two different seats, `D-1` and `D-2`. Each takes its own seat, so no row lock makes one wait for the other, and both reach the `INSERT` of the hold row. The table `holds` has a **unique key** on the idempotency key: the database refuses a second row with the same key, with SQLState `23505` ("unique violation"). The loser's transaction is rolled back, which also frees its seat, and the store then reads the winner's row. Because both requests have the same fingerprint (`SHA_A`), the loser is a retry of the winner and must answer with the winner's hold.

- `JdbcStandIns.meetingAtTheInsert(dataSource, 2, rollbacks)` makes the two racers meet at the `INSERT`, so this race happens on every run. Without the barrier, one request would usually finish before the other started.
- `race(racing, 2, ...)` runs two racers on that store; racer 0 asks for `D-1`, racer 1 for `D-2` (`racer == 0 ? "D-1" : "D-2"`).
- Both outcomes must be granted, with the same hold id.
- `containsExactlyInAnyOrder(true, false)`: one of them is the original and one a replay, in either order.
- Exactly one rollback happened, and exactly one seat is `HELD` (`heldSeats()`, below): the loser's seat was given back.

In version 1.0.0 no test reached this branch. The mutation check makes the store treat every SQL error as fatal (`if (true)` in place of the SQLState test), and this test fails.

```java
  @Test
  void refusesTheLoserOfAKeyRaceWithAnotherBodyAndHoldsOneSeat() throws Exception {
    AtomicInteger rollbacks = new AtomicInteger();
    JdbcHoldStore racing =
        new JdbcHoldStore(
            JdbcStandIns.meetingAtTheInsert(dataSource, 2, rollbacks), clock, CHECK_TIMEOUT);

    List<Result<GrantedHold, HoldRefusal>> outcomes =
        race(
            racing,
            2,
            racer ->
                command("\"one-key\"", racer == 0 ? SHA_A : SHA_B, racer == 0 ? "E-1" : "E-2"));

    assertThat(outcomes).filteredOn(outcome -> outcome instanceof Result.Success<?, ?>).hasSize(1);
    assertThat(outcomes).contains(Result.error(new HoldRefusal.KeyReused()));
    assertThat(rollbacks.get()).isEqualTo(1);
    assertThat(heldSeats()).isEqualTo(1);
  }
```

**`refusesTheLoserOfAKeyRaceWithAnotherBodyAndHoldsOneSeat`.** The same race, but the two requests have different fingerprints (`SHA_A` and `SHA_B`) and seats `E-1` and `E-2`: one key reused for two different requests. Exactly one outcome must be a success, and the other must be `KeyReused`. Again one rollback, and one seat held.

```java
  @Test
  void saysTheDatabaseIsReachableWhenItAnswers() {
    assertThat(store.isReachable()).isTrue();
  }

  @Test
  void saysTheDatabaseIsUnreachableWhenNoConnectionCanBeHad() {
    JdbcHoldStore broken = new JdbcHoldStore(unreachable(), clock, CHECK_TIMEOUT);

    assertThat(broken.isReachable()).isFalse();
  }
```

Two tests of the readiness check.

- **`saysTheDatabaseIsReachableWhenItAnswers`**: `isReachable()` is true on the working database.
- **`saysTheDatabaseIsUnreachableWhenNoConnectionCanBeHad`**: a store over `unreachable()` (below) must say false.

```java
  @Test
  void turnsADatabaseFailureIntoStoreUnavailable() {
    JdbcHoldStore broken = new JdbcHoldStore(unreachable(), clock, CHECK_TIMEOUT);

    assertThatThrownBy(() -> broken.hold(command("\"k-1\"", SHA_A, "A-7")))
        .isInstanceOf(StoreUnavailableException.class)
        .hasMessageContaining("A-7");
    assertThatThrownBy(() -> broken.find("00000000-0000-0000-0000-000000000000"))
        .isInstanceOf(StoreUnavailableException.class);
  }
```

**`turnsADatabaseFailureIntoStoreUnavailable`.** On the unreachable store, `hold(...)` must throw `StoreUnavailableException` whose message contains `A-7`, and `find(...)` must throw the same type. `assertThatThrownBy(() -> ...)` runs the lambda and checks the exception.

```java
  @Test
  void rejectsAReadinessTimeoutUnderOneSecond() {
    assertThatThrownBy(() -> new JdbcHoldStore(dataSource, clock, Duration.ofMillis(999)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least 1 s");
  }
```

**`rejectsAReadinessTimeoutUnderOneSecond`.** `new JdbcHoldStore(dataSource, clock, Duration.ofMillis(999))` must throw `IllegalArgumentException` containing `at least 1 s`.

```java
  private List<Result<GrantedHold, HoldRefusal>> race(RacerCommand commands) throws Exception {
    return race(store, RACERS, commands);
  }

  private static List<Result<GrantedHold, HoldRefusal>> race(
      JdbcHoldStore store, int racers, RacerCommand commands) throws Exception {
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Result<GrantedHold, HoldRefusal>>> futures = new ArrayList<>();
    try (ExecutorService threads = Executors.newFixedThreadPool(racers)) {
      for (int racer = 0; racer < racers; racer++) {
        HoldCommand command = commands.forRacer(racer);
        Callable<Result<GrantedHold, HoldRefusal>> attempt =
            () -> {
              go.await();
              return store.hold(command);
            };
        futures.add(threads.submit(attempt));
      }
      go.countDown();
      List<Result<GrantedHold, HoldRefusal>> outcomes = new ArrayList<>();
      for (Future<Result<GrantedHold, HoldRefusal>> future : futures) {
        outcomes.add(future.get(WAIT_SECONDS, TimeUnit.SECONDS));
      }
      return outcomes;
    }
  }
```

`race(...)` is the helper, now in two forms. The short one runs `RACERS` (16) racers on the test's own store. The long one takes the store and the number of racers, so the key-race tests can pass their barrier store and 2. It is `static`, because it uses only its parameters.

1. A latch `go` of size 1 and an empty list of futures.
2. A fixed pool of `racers` threads (closed by the `try` at the end).
3. For each racer, it builds the command first (`commands.forRacer(racer)`), then submits a `Callable` that waits at `go.await()` and then calls `store.hold(command)`.
4. When all are submitted, `go.countDown()` releases them together. This gives the best chance of a real overlap.
5. It collects each result with `future.get(30, SECONDS)`, in the order the racers were made, so `outcomes.get(0)` belongs to racer 0. A thread that threw would make `get` throw, and the test would fail with that error.

```java
  private static HoldCommand command(String key, String fingerprint, String seat) {
    return new HoldCommand(key, fingerprint, 1, seat, "fan-1");
  }

  private static GrantedHold granted(Result<GrantedHold, HoldRefusal> outcome) {
    return switch (outcome) {
      case Result.Success<GrantedHold, HoldRefusal>(GrantedHold granted) -> granted;
      case Result.Error<GrantedHold, HoldRefusal>(HoldRefusal refusal) ->
          throw new AssertionError("expected a granted hold, got " + refusal);
    };
  }
```

`command(...)` builds a `HoldCommand` for event 1 and customer `fan-1`. `granted(...)` unwraps a success, or throws `AssertionError("expected a granted hold, got ...")` with the refusal named.

```java
  /** Makes one racer's command. */
  @FunctionalInterface
  private interface RacerCommand {
    HoldCommand forRacer(int racer);
  }

  private long heldSeats() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery("SELECT COUNT(*) FROM seats WHERE status = 'HELD'")) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private static DataSource unreachable() {
    return JdbcStandIns.unreachable();
  }
}
```

`RacerCommand` is a one-method interface (`@FunctionalInterface`), so the tests can pass a lambda `racer -> command(...)`.

`heldSeats()` asks the database directly: `SELECT COUNT(*) FROM seats WHERE status = 'HELD'`, the number of seats in the state `HELD`. The `try ( ... )` opens a connection, a statement and the result rows, and closes all three at the end, in reverse order. `rows.next()` moves to the one row of the answer, and `getLong(1)` reads its first column (JDBC counts columns from 1).

`unreachable()` now returns the proxy of `JdbcStandIns.unreachable()`.

### `seatwave/SeatWaveServerTest.java`

```java
package io.plainround.seatwave.balancer.seatwave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SeatWaveServerTest {

  private static final Duration DEADLINE = Duration.ofSeconds(10);
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  // Short, so the deadline test ends in a fraction of a second instead of the server's 5 s.
  private static final Duration WORKER_STOP_WAIT = Duration.ofMillis(100);
  private static final String BODY = "{\"eventId\":1,\"seat\":\"A-7\",\"customer\":\"fan-1\"}";

  private final ScriptedHoldStore store = new ScriptedHoldStore();
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  private SeatWaveServer server;
```

Imports and fields. `DEADLINE` and `TIMEOUT` are 10 seconds each: safety limits, not expected times. `WORKER_STOP_WAIT` is new in 1.1.0: 100 ms instead of the server's 5 s, so the deadline test ends in a fraction of a second, as the comment says. `BODY` is a valid hold request. Each test gets a new `ScriptedHoldStore`, a new `HttpClient` with a connect timeout, and a `server` field set by `start(...)`.

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

`@AfterEach stop()` first calls `store.releaseHolds()`, so no request stays blocked inside the fake store, and then drains the server with a deadline of zero (do not wait), and closes the client. It runs even if a test failed, so ports and threads are not leaked into the next test.

```java
  @Test
  void refusesRequestsUntilMarkedReady() throws Exception {
    start(DEADLINE);

    HttpResponse<String> response = hold("\"k-1\"");

    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.body()).contains("starting");
    assertThat(admin("/ready").statusCode()).isEqualTo(503);
  }
```

**`refusesRequestsUntilMarkedReady`.** The server is started but `markReady()` is not called. A `POST` must give `503` with a body containing `starting` (the word comes from the state's name in the problem body), and `GET /ready` on the admin port must also give `503`.

```java
  @Test
  void servesAndNamesTheInstanceOnceReady() throws Exception {
    start(DEADLINE);
    server.markReady();

    HttpResponse<String> response = hold("\"k-1\"");

    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.headers().firstValue("X-Served-By")).contains("seatwave-test");
  }
```

**`servesAndNamesTheInstanceOnceReady`.** After `markReady()`, a `POST` gives `201`, and the header `X-Served-By` contains `seatwave-test`, the name given to the server. `response.headers().firstValue(...)` is an `Optional<String>`.

```java
  @Test
  void answersHealthAndReadinessAsTwoQuestions() throws Exception {
    start(DEADLINE);
    server.markReady();
    store.setReachable(false);

    assertThat(admin("/health").statusCode()).isEqualTo(200);
    assertThat(admin("/ready").statusCode()).isEqualTo(503);
    assertThat(admin("/ready").body()).contains("database-unreachable");
    store.setReachable(true);
    assertThat(admin("/ready").statusCode()).isEqualTo(200);
    assertThat(admin("/other").statusCode()).isEqualTo(404);
  }
```

**`answersHealthAndReadinessAsTwoQuestions`.** The server is ready and the store says it is unreachable.

- `/health` must still be `200` (the process is alive).
- `/ready` must be `503` and the body must contain `database-unreachable`.
- After `store.setReachable(true)`, `/ready` must be `200`.
- `GET /other` on the admin port must be `404`.

This is the test that holds the difference between liveness and readiness.

```java
  @Test
  void answersAHeadRequestLikeAGetWithoutABody() throws Exception {
    start(DEADLINE);
    server.markReady();

    HttpResponse<String> health =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.adminPort() + "/health"))
                .timeout(TIMEOUT)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(health.statusCode()).isEqualTo(200);
    assertThat(health.body()).isEmpty();
  }
```

**`answersAHeadRequestLikeAGetWithoutABody`.** New in 1.1.0. `HEAD` is the HTTP method that asks for exactly what `GET` would answer, without the body (RFC 9110). Health checkers and some clients use it. The test sends `HEAD /health` to the admin port: `.method("HEAD", HttpRequest.BodyPublishers.noBody())` sets a method that has no shortcut of its own on the builder, with no request body. The status must be `200` and the body empty. In version 1.0.0 the admin port recognized only `GET`, so `HEAD /health` got its not-found answer (`404`), and a hold path answered `HEAD` with `405`, "method not allowed".

```java
  @Test
  void countsRequestsInItsMetrics() throws Exception {
    start(DEADLINE);
    server.markReady();
    hold("\"k-1\"");

    // The server counts a request after it has written the answer, so the client can read the
    // metrics a moment before the count; wait for it instead of racing it.
    await()
        .atMost(TIMEOUT)
        .until(() -> admin("/metrics").body().contains("seatwave_request_seconds_count 1"));
    String metrics = admin("/metrics").body();

    assertThat(metrics)
        .contains("seatwave_requests_total{route=\"POST /v1/holds\",status=\"201\"} 1")
        .contains("seatwave_state{state=\"ready\"} 1")
        .contains("seatwave_request_seconds_count 1");
  }
```

**`countsRequestsInItsMetrics`.** After one hold, `GET /metrics` must contain `seatwave_requests_total{route="POST /v1/holds",status="201"} 1`, `seatwave_state{state="ready"} 1` and `seatwave_request_seconds_count 1`.

The `await()` before the read is new in 1.1.0, and the comment explains it. The server writes the reply first and counts the request afterwards, on its own thread. A client that asks for `/metrics` at once can, in a rare case, arrive between the two. The test now waits (at most 10 s) until the counter shows 1, then reads the metrics it checks. In version 1.0.0 this window was left open.

```java
  @Test
  void finishesTheRequestInFlightAndRefusesNewOnesWhileDraining() throws Exception {
    start(DEADLINE);
    server.markReady();
    store.blockHolds();
    CompletableFuture<HttpResponse<String>> inFlight = holdAsync("\"in-flight\"");
    assertThat(store.awaitHoldEntered()).isTrue();

    CompletableFuture<Lifecycle.DrainResult> drained =
        CompletableFuture.supplyAsync(() -> server.drainAndStop(DEADLINE));
    await().atMost(TIMEOUT).until(() -> server.state() == LifecycleState.DRAINING);
    HttpResponse<String> refused = hold("\"new\"");
    store.releaseHolds();

    assertThat(refused.statusCode()).isEqualTo(503);
    assertThat(refused.headers().firstValue("Connection")).contains("close");
    assertThat(refused.body()).contains("nothing was done");
    assertThat(inFlight.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).statusCode()).isEqualTo(201);
    Lifecycle.DrainResult result = drained.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    assertThat(result.isClean()).isTrue();
    // Woken by the last request's release, not by the 10 s deadline (a lost wake-up is also clean).
    assertThat(result.waited()).isLessThan(DEADLINE.dividedBy(2));
    assertThat(server.notAdmittedCount()).isEqualTo(1);
  }
```

**`finishesTheRequestInFlightAndRefusesNewOnesWhileDraining`.** The drain test as a client sees it.

1. Ready; `store.blockHolds()`; a hold is sent asynchronously (`holdAsync`), and `store.awaitHoldEntered()` waits until the request is inside the store: it is now in flight.
2. `drainAndStop(DEADLINE)` runs on another thread (`supplyAsync`), because it waits for the in-flight request.
3. `await()` waits until the server's state is `DRAINING`.
4. A new hold is sent. Then `store.releaseHolds()` lets the blocked request finish.
5. The new request must have status `503`, a `Connection: close` header and a body with `nothing was done`.
6. The old request must end with `201`: it was admitted before the drain and was allowed to finish.
7. The drain result must be clean, and, new in 1.1.0, `waited()` must be under half the 10 s deadline: the release of the last request woke the drain, as the comment says ("a lost wake-up is also clean"). The mutation check removes the wake-up, the drain sleeps to its deadline, and this test fails.
8. `notAdmittedCount()` must be exactly 1.

```java
  @Test
  void stopsAtTheDeadlineWhenARequestNeverFinishes() throws Exception {
    server =
        SeatWaveServer.start(
            TestSettings.withDrainDeadline(Duration.ofMillis(200)),
            "seatwave-test",
            store,
            new MutableClock(Instant.parse("2026-11-11T09:00:00Z")),
            WORKER_STOP_WAIT);
    server.markReady();
    store.blockHolds();
    holdAsync("\"stuck\"");
    assertThat(store.awaitHoldEntered()).isTrue();

    Lifecycle.DrainResult result = server.drainAndStop(Duration.ofMillis(200));

    assertThat(result.inFlightLeft()).isEqualTo(1);
    assertThat(result.waited()).isGreaterThanOrEqualTo(Duration.ofMillis(200));
    assertThat(server.state()).isEqualTo(LifecycleState.STOPPED);
  }
```

**`stopsAtTheDeadlineWhenARequestNeverFinishes`.** Deadline 200 ms. The server is started with the five-argument `SeatWaveServer.start`, which takes the worker stop wait, here 100 ms. A held request is in the store and never released. `drainAndStop(200 ms)` must return with `inFlightLeft()` equal to 1, `waited()` at least 200 ms (the drain really waited for its deadline), and the state must be `STOPPED`.

In version 1.0.0 this test took about five seconds, because after the deadline the server waits `WORKER_STOP_WAIT` (5 s with the shipped code) for the blocked worker before it interrupts it. With 100 ms it ends in about a third of a second.

```java
  private void start(Duration deadline) throws IOException {
    server =
        SeatWaveServer.start(
            TestSettings.withDrainDeadline(deadline),
            "seatwave-test",
            store,
            new MutableClock(Instant.parse("2026-11-11T09:00:00Z")));
  }

  private HttpResponse<String> hold(String key) throws Exception {
    return client.send(holdRequest(key), HttpResponse.BodyHandlers.ofString());
  }

  private CompletableFuture<HttpResponse<String>> holdAsync(String key) {
    return client.sendAsync(holdRequest(key), HttpResponse.BodyHandlers.ofString());
  }
```

The helpers. `start(deadline)` builds a server on loopback with port 0, the instance name `seatwave-test`, the scripted store and a `MutableClock` fixed at 09:00. It uses the four-argument `start`, so the server's own 5 s worker wait applies; only the deadline test needs a shorter one. `hold(key)` sends a request and waits for the reply as a `String`; `holdAsync(key)` returns the `CompletableFuture`.

```java
  private HttpRequest holdRequest(String key) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/holds"))
        .timeout(TIMEOUT)
        .header("Content-Type", "application/json")
        .header("Idempotency-Key", key)
        .POST(HttpRequest.BodyPublishers.ofString(BODY))
        .build();
  }

  private HttpResponse<String> admin(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.adminPort() + path))
            .timeout(TIMEOUT)
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
```

`holdRequest(key)` builds the `POST /v1/holds` with a 10 second timeout, `Content-Type: application/json`, the key header and the body. `admin(path)` sends a `GET` to the admin port.

### `seatwave/HoldRoutesTest.java`

```java
package io.plainround.seatwave.balancer.seatwave;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.balancer.json.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class HoldRoutesTest {

  private static final String KEY = "\"key-1\"";
  private static final String BODY = "{\"eventId\":1,\"seat\":\"A-7\",\"customer\":\"fan-1\"}";
  private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-11-11T09:00:00Z"));

  private final ScriptedHoldStore store = new ScriptedHoldStore();
  private final HoldRoutes routes = new HoldRoutes(store, new JsonCodec(), CLOCK);
```

The routes are made with the scripted store, a real `JsonCodec` and a clock fixed at 09:00 (static and shared; no test advances it). `KEY` is `"key-1"` with its quotes. `BODY` is a valid request.

```java
  @Test
  void createsAHoldWithItsLocation() {
    Reply reply = post("application/json; charset=utf-8", KEY, BODY);

    assertThat(reply.status()).isEqualTo(201);
    assertThat(reply.header("Location"))
        .isEqualTo("/v1/holds/11111111-2222-3333-4444-555555555555");
    assertThat(reply.bodyText()).contains("\"state\":\"held\"", "\"seat\":\"A-7\"");
  }
```

**`createsAHoldWithItsLocation`.** A `Content-Type` of `application/json; charset=utf-8` (with a parameter) must work. The reply is `201`, the `Location` header equals `/v1/holds/11111111-2222-3333-4444-555555555555` (the scripted store's id), and the body contains `"state":"held"` and `"seat":"A-7"`. The state is `held` because the clock (09:00) is before the scripted expiry (09:05).

```java
  @Test
  void refusesAMediaTypeThatOnlyStartsLikeJson() {
    assertThat(post("application/jsonx", KEY, BODY).status()).isEqualTo(415);
  }
```

**`refusesAMediaTypeThatOnlyStartsLikeJson`.** `application/jsonx` gives `415`. It pins the media-type comparison.

```java
  @Test
  void refusesAMissingIdempotencyKey() {
    Reply reply = post("application/json", "", BODY);

    assertThat(reply.status()).isEqualTo(400);
    assertThat(reply.bodyText()).contains("Idempotency-Key");
  }
```

**`refusesAMissingIdempotencyKey`.** An empty key gives `400` and a body that names `Idempotency-Key`. Only a missing key is tested; a key with a bad character is not.

```java
  @Test
  void refusesABodyOverTheLimit() {
    Request large =
        new Request("POST", "/v1/holds", "", "application/json", KEY, new byte[0], true, "c1");

    assertThat(routes.route(large).reply().status()).isEqualTo(413);
  }
```

**`refusesABodyOverTheLimit`.** A `Request` with `isBodyTooLarge` true gives `413`.

```java
  @Test
  void namesEveryInvalidFieldTogether() {
    Reply reply =
        post("application/json", KEY, "{\"eventId\":0,\"seat\":\"Z-1\",\"customer\":\"a b\"}");

    assertThat(reply.status()).isEqualTo(400);
    assertThat(reply.bodyText()).contains("/eventId", "/seat", "/customer");
  }
```

**`namesEveryInvalidFieldTogether`.** `eventId` 0, seat `Z-1`, customer `a b`: `400`, and the body must contain `/eventId`, `/seat` and `/customer`. All three problems in one answer.

```java
  @Test
  void refusesAnUnknownField() {
    Reply reply =
        post(
            "application/json", KEY, "{\"eventId\":1,\"seat\":\"A-7\",\"customer\":\"f\",\"x\":1}");

    assertThat(reply.status()).isEqualTo(400);
    assertThat(reply.bodyText()).contains("/x is not a field");
  }
```

**`refusesAnUnknownField`.** An extra field `x` gives `400` and a body containing `/x is not a field`. The codec's other refusals are tested on their own in `JsonCodecTest` (`tests-tools-walkthrough-2.md`).

```java
  @Test
  void startsAWholeBodyProblemWithTheReasonNotASpace() {
    Reply reply = post("application/json", KEY, "[1]");

    assertThat(reply.status()).isEqualTo(400);
    assertThat(reply.bodyText()).contains("\"detail\":\"the body must be a JSON object\"");
  }
```

**`startsAWholeBodyProblemWithTheReasonNotASpace`.** New in 1.1.0. The body `[1]` is valid JSON but not an object. The problem is about the whole body, so its pointer is empty. The routes build the `detail` of a `400` as "pointer, space, reason", and in version 1.0.0 an empty pointer left a leading space: `" the body must be a JSON object"`. The test pins the exact text `"detail":"the body must be a JSON object"`, quotes included, so a space after the opening quote fails it. The mutation check puts the old form back, and this test fails.

```java
  @Test
  void namesARepeatedField() {
    Reply reply = post("application/json", KEY, "{\"eventId\":1,\"eventId\":2}");

    assertThat(reply.bodyText()).contains("\"detail\":\"/eventId appears more than once\"");
  }
```

**`namesARepeatedField`.** New in 1.1.0. `{"eventId":1,"eventId":2}` names `eventId` twice. The detail must be `/eventId appears more than once`: the pointer, a space, and the reason.

```java
  @Test
  void readsAHoldWithHeadAsWithGet() {
    Reply reply =
        routes
            .route(
                new Request(
                    "HEAD",
                    "/v1/holds/11111111-2222-3333-4444-555555555555",
                    "",
                    "",
                    "",
                    new byte[0],
                    false,
                    "c1"))
            .reply();

    // The same answer as a GET of the same path: the server leaves only the body out.
    assertThat(reply.status())
        .isEqualTo(get("/v1/holds/11111111-2222-3333-4444-555555555555", "").status());
    assertThat(reply.status()).isNotEqualTo(405);
  }
```

**`readsAHoldWithHeadAsWithGet`.** New in 1.1.0. A `HEAD` request for a hold path is routed like a `GET`. The check asks that the `HEAD` reply has the same status as a `GET` of the same path (`get(path, "")`, the helper at the end of the class), and that it is not `405`, the answer for a method the path does not take. (The scripted store knows this id, so both are `200`.) The server, not the routes, leaves the body out when it writes a `HEAD` reply; `SeatWaveServerTest.answersAHeadRequestLikeAGetWithoutABody` checks that part.

```java
  @Test
  void answersSeatTakenWithConflict() {
    assertThat(post("application/json", KEY, body("T-1000")).status()).isEqualTo(409);
  }

  @Test
  void answersAReusedKeyWithUnprocessable() {
    assertThat(post("application/json", KEY, body("T-999")).status()).isEqualTo(422);
  }

  @Test
  void answersAMissingSeatWithNotFound() {
    assertThat(post("application/json", KEY, body("T-998")).status()).isEqualTo(404);
  }
```

Three tests map a store refusal to a status, using the trigger seats of `ScriptedHoldStore`.

- **`answersSeatTakenWithConflict`**: `T-1000` gives `409`.
- **`answersAReusedKeyWithUnprocessable`**: `T-999` gives `422`.
- **`answersAMissingSeatWithNotFound`**: `T-998` gives `404`.

```java
  @Test
  void failsSafeWithServiceUnavailableWhenTheStoreIsDown() {
    store.goDown();

    Reply hold = post("application/json", KEY, BODY);
    Reply read = get("/v1/holds/11111111-2222-3333-4444-555555555555", "");

    assertThat(hold.status()).isEqualTo(503);
    assertThat(hold.header("Retry-After")).isEqualTo("1");
    assertThat(read.status()).isEqualTo(503);
  }
```

**`failsSafeWithServiceUnavailableWhenTheStoreIsDown`.** `store.goDown()`. A hold gives `503` with `Retry-After: 1`. A read of a hold gives `503`. "Fail safe": no guess when the database is down.

```java
  @Test
  void readsAHoldAsPrivate() {
    Reply reply = get("/v1/holds/11111111-2222-3333-4444-555555555555", "");

    assertThat(reply.status()).isEqualTo(200);
    assertThat(reply.header("Cache-Control")).startsWith("private");
  }

  @Test
  void answersNotFoundForAnUnknownHold() {
    assertThat(get("/v1/holds/99999999-2222-3333-4444-555555555555", "").status()).isEqualTo(404);
  }
```

- **`readsAHoldAsPrivate`**: `GET` of the scripted hold gives `200` and a `Cache-Control` that starts with `private`.
- **`answersNotFoundForAnUnknownHold`**: an id the store does not know gives `404`.

```java
  @Test
  void refusesAQueryOnAHold() {
    assertThat(get("/v1/holds/11111111-2222-3333-4444-555555555555", "x=1").status())
        .isEqualTo(400);
  }

  @Test
  void answersNotFoundForAnUnknownPathWhateverTheMethod() {
    Request unknown =
        new Request("POST", "/v1/nothing", "", "application/json", KEY, new byte[0], false, "c1");

    assertThat(routes.route(unknown).reply().status()).isEqualTo(404);
  }
```

- **`refusesAQueryOnAHold`**: `?x=1` on a hold path gives `400`.
- **`answersNotFoundForAnUnknownPathWhateverTheMethod`**: `POST /v1/nothing` gives `404`, not `405`. An earlier project gave `405` here.

```java
  @Test
  void answersMethodNotAllowedWithTheAllowedMethod() {
    Reply onHolds =
        routes.route(new Request("GET", "/v1/holds", "", "", "", new byte[0], false, "c1")).reply();
    Reply onHold =
        routes
            .route(
                new Request(
                    "DELETE",
                    "/v1/holds/11111111-2222-3333-4444-555555555555",
                    "",
                    "",
                    "",
                    new byte[0],
                    false,
                    "c1"))
            .reply();

    assertThat(onHolds.status()).isEqualTo(405);
    assertThat(onHolds.header("Allow")).isEqualTo("POST");
    assertThat(onHold.header("Allow")).isEqualTo("GET, HEAD");
  }
```

**`answersMethodNotAllowedWithTheAllowedMethod`.** `GET /v1/holds` gives `405` with `Allow: POST`. `DELETE` on a hold path gives `Allow: GET, HEAD` since 1.1.0 (it said `GET` before `HEAD` was answered): the `Allow` header must list every method the path accepts. The second request is built with the record constructor spread over many lines; its eight arguments are the method, the path, the query, the content type, the key, the body, the too-large flag and the correlation id.

```java
  @Test
  void refusesWhileNotReadyAndSaysNothingWasDone() {
    Reply reply = routes.notAdmitted(LifecycleState.DRAINING, "c1");

    assertThat(reply.status()).isEqualTo(503);
    assertThat(reply.header("Connection")).isEqualTo("close");
    assertThat(reply.bodyText()).contains("draining; nothing was done");
  }
```

**`refusesWhileNotReadyAndSaysNothingWasDone`.** `routes.notAdmitted(LifecycleState.DRAINING, "c1")` gives `503`, `Connection: close` and a body containing `draining; nothing was done`. This is the sentence the balancer's retry rule relies on.

```java
  private Reply post(String contentType, String key, String body) {
    return routes
        .route(
            new Request(
                "POST",
                "/v1/holds",
                "",
                contentType,
                key,
                body.getBytes(StandardCharsets.UTF_8),
                false,
                "c1"))
        .reply();
  }

  private Reply get(String path, String query) {
    return routes.route(new Request("GET", path, query, "", "", new byte[0], false, "c1")).reply();
  }

  private static String body(String seat) {
    return "{\"eventId\":1,\"seat\":\"" + seat + "\",\"customer\":\"fan-1\"}";
  }
}
```

The helpers. `post(contentType, key, body)` builds a `Request` for `POST /v1/holds` (body as UTF-8 bytes, not too large, correlation id `c1`) and returns the reply. `get(path, query)` does the same for a `GET`. `body(seat)` builds a valid body for any seat.

What these tests leave out: the `expired` state of a hold (the shared clock never moves); a replayed hold's log line; the missing `Content-Type`; and an upper-case UUID in the path.

## Try it yourself

All commands run in the project folder. Timings differ on every run.

1. Run the three classes. Forty-one tests pass.

```bash
./gradlew --offline cleanTest test --tests '*JdbcHoldStoreTest' --tests '*SeatWaveServerTest' --tests '*HoldRoutesTest' 2>&1 | grep -cE "PASSED"
```

It prints `41`.

2. See how long the slowest tests of these classes take. This lists them from the last run.

```bash
python3 -c "
import glob,re
rows=[]
for x in glob.glob('build/test-results/test/*.xml'):
    for m in re.finditer(r'<testcase name=\"([^\"]+)\" classname=\"([^\"]+)\" time=\"([0-9.]+)\"',open(x).read()):
        rows.append((float(m[3]),m[2].split('.')[-1],m[1]))
for r in sorted(rows,reverse=True)[:3]: print(r)
"
```

On the run for these notes:

```text
(0.405, 'JdbcHoldStoreTest', 'letsExactlyOneOfManyRacersHoldOneSeat()')
(0.323, 'SeatWaveServerTest', 'stopsAtTheDeadlineWhenARequestNeverFinishes()')
(0.18, 'SeatWaveServerTest', 'finishesTheRequestInFlightAndRefusesNewOnesWhileDraining()')
```

The seat race starts sixteen threads; the deadline test waits its 200 ms deadline and its 100 ms worker wait; the drain test waits for a request in flight. In version 1.0.0 the top three took 5.219 s, 2.779 s and 1.393 s: the deadline test waited the full 5 s worker wait, and the two "database is down" tests waited for a refused connection to port 1. The proxy now fails at once.

3. Break the race rule and watch the race test fail. Copy the project, remove the `WHERE` part that makes the `UPDATE` conditional, and run the store tests.

```bash
rm -rf build/rule-check && mkdir build/rule-check
rsync -a --exclude build --exclude .gradle ./ build/rule-check/
cd build/rule-check
sed -i.bak "s/AND (status = 'AVAILABLE' OR (status = 'HELD' AND hold_expires_at <= ?))/AND ? IS NOT NULL/" src/main/java/io/plainround/seatwave/balancer/seatwave/JdbcHoldStore.java
./gradlew --offline cleanTest test --tests '*JdbcHoldStoreTest' 2>&1 | grep -E "FAILED|BUILD"
cd ../.. && rm -rf build/rule-check
```

In the run for these notes:

```text
JdbcHoldStoreTest > letsExactlyOneOfManyRacersHoldOneSeat() FAILED
JdbcHoldStoreTest > refusesASeatAnotherActiveHoldOwns() FAILED
> Task :test FAILED
BUILD FAILED in 4s
```

The `UPDATE` now takes any seat regardless of its status, so the tests that need a refusal fail.

4. Break the duplicate-key branch the same way, in a copy: replace `if (!UNIQUE_VIOLATION.equals(failure.getSQLState())) {` with `if (true) {` in `JdbcHoldStore.java` and run `--tests '*JdbcHoldStoreTest'`.

In the run for these notes the two key-race tests fail, and no other:

```text
JdbcHoldStoreTest > refusesTheLoserOfAKeyRaceWithAnotherBodyAndHoldsOneSeat() FAILED
JdbcHoldStoreTest > answersTheLoserOfAKeyRaceWithTheWinnersHoldAndReleasesItsSeat() FAILED
> Task :test FAILED
BUILD FAILED in 2s
```

In version 1.0.0 no test failed with this change. Undo it.

## Mistakes people make

- **Racing on one key and one seat and believing every path was tested.** That race takes the "wait and re-read" branch, not the duplicate-key branch. Choose the race inputs to reach the branch you want (here: one key, two seats), and force the timing with a barrier when chance would rarely give it.
- **A fixed sleep before checking.** The drain test waits with `await()` for the state `DRAINING` and with latches for the request to enter the store, not with `sleep`.
- **Leaving a request blocked.** `@AfterEach` releases the fake store first, or a stuck worker would hold the next test's port.
- **Depending on the machine for a failure.** Version 1.0.0 assumed nothing listened on port 1. The proxy data source fails by itself.
- **Reading metrics before they are written.** The counter moves after the reply; `countsRequestsInItsMetrics` now waits for it.
- **Asserting only on the status.** The route tests also check `Retry-After`, `Allow`, `Location`, `Cache-Control` and `Connection`, because the balancer and the clients read those.
- **Asserting the result of a drain but not its time.** A lost wake-up ends "clean", only late. The drain tests check `waited()`.
- **Testing against a convenient database and calling it proof.** These store tests run on H2. `StackTest` runs the one-owner rule and the key race on PostgreSQL.

## Check yourself

1. In `letsExactlyOneOfManyRacersHoldOneSeat`, what must each loser's outcome equal, and why can the test compare with `equals`?
   Answer: `Result.error(new HoldRefusal.SeatTaken())`. `Result` and the refusal types are records, which compare by content.
2. Why does the drain test wait for `awaitHoldEntered()` before it starts draining?
   Answer: to be sure a request is really inside the store, in flight, before the drain starts; otherwise the drain could finish before the request arrives and prove nothing.
3. Why do the key-race tests give the two racers different seats?
   Answer: with one seat, the row lock on the seat makes one racer wait and the duplicate-key branch is never reached; with two seats both reach the `INSERT`, and the unique key refuses the second with `23505`.
4. What does `JdbcStandIns.meetingAtTheInsert` add to a real data source?
   Answer: a wait at a barrier before the `INSERT INTO holds`, until both racers are there, and a count of rollbacks; every call still goes to the real H2 connection.
5. Why does `stopsAtTheDeadlineWhenARequestNeverFinishes` now take a fraction of a second instead of about five?
   Answer: it starts the server with a worker stop wait of 100 ms instead of the shipped 5 s.

## Further reading

- Java SE 25 API, `java.net.http.HttpClient`: https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpClient.html
- Java SE 25 API, `CompletableFuture`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/CompletableFuture.html
- Java SE 25 API, `java.util.concurrent.CyclicBarrier`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/CyclicBarrier.html
- Java SE 25 API, `java.lang.reflect.Proxy` (dynamic proxies): https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/reflect/Proxy.html
- Java SE 25 API, `java.sql.SQLException` (SQLState): https://docs.oracle.com/en/java/javase/25/docs/api/java.sql/java/sql/SQLException.html
- H2 2.5 documentation, transaction isolation and locking: https://h2database.com/html/advanced.html
- PostgreSQL 18 documentation, error codes (23505 `unique_violation`): https://www.postgresql.org/docs/18/errcodes-appendix.html
- RFC 9110, HTTP semantics, `HEAD` and `Allow`: https://www.rfc-editor.org/rfc/rfc9110#name-head
- AssertJ core documentation: https://assertj.github.io/doc/
- JUnit 6 user guide: https://docs.junit.org/current/user-guide/
