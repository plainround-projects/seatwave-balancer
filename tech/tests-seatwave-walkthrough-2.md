# The `seatwave` tests, part 2: the store, the server and the routes (line by line)

This is part 2 of 2. Part 1 (`tests-seatwave-walkthrough-1.md`) reads the helpers (`TestSettings`, `MutableClock`, `TestDatabase`, `ScriptedHoldStore`) and the tests `LifecycleTest`, `ServiceSettingsTest` and `SeatWaveCliTest`. This part reads `JdbcHoldStoreTest`, `SeatWaveServerTest` and `HoldRoutesTest`. Read part 1 first for the terms (test, assertion, fake, latch, race).

## What it is

Three test classes, 35 tests in all.

- `JdbcHoldStoreTest` (13 tests): runs the real SQL of `JdbcHoldStore` on H2. It checks the rules (one seat, one owner; expiry; replay; reused key) and races sixteen threads.
- `SeatWaveServerTest` (6 tests): starts a real HTTP server on a free port and talks to it with Java's own HTTP client. It checks the gate, the two questions (`/health`, `/ready`), the metrics and the drain.
- `HoldRoutesTest` (16 tests): calls the routes directly with a `Request` and reads the `Reply`. It checks every status and header without a network.

New terms.

- A **status code**, **header** and **body** are the parts of an HTTP reply; see `java-seatwave-walkthrough-2.md`.
- **Asynchronous** means "start it now, collect the result later". Java's `CompletableFuture` is a promise of a result that is not ready yet. `client.sendAsync(...)` sends a request and returns one.
- A **timeout** on a request makes the client give up after a time.
- A **fixed thread pool** runs a set number of threads. The tests use one to start sixteen racers at the same instant.

## Why this repository uses it

- The store is where the module's promise is kept: "one seat, one owner" even when instances race. The only way to trust a race rule is to run a race, so `JdbcHoldStoreTest` does, with sixteen threads on a real database engine.
- The server tests prove the drain as a client sees it: a request in flight finishes with `201`, a new request gets `503` with `Connection: close`, and the drain ends cleanly.
- The route tests keep every error status and header honest. They are fast because they need no network.
- Each rule that the balancer relies on is pinned by name here: "nothing was done" in the `503`, `Retry-After`, `Allow`, `private` for cached holds.

## Where it appears

| File | Tests | What it checks |
|---|---|---|
| `seatwave/JdbcHoldStoreTest.java` | 13 | The SQL rules and the races, on H2. |
| `seatwave/SeatWaveServerTest.java` | 6 | The HTTP server, readiness, metrics and the drain. |
| `seatwave/HoldRoutesTest.java` | 16 | Every route and every error answer. |

## How it works underneath

**Store tests.** `@BeforeEach` makes a fresh database and a store. Each test calls `store.hold(command)` and checks the `Result`. The clock is a `MutableClock`, so "300 seconds later" is one method call. The race helper starts sixteen threads that all wait at a latch, opens the latch, and collects the sixteen `Result`s.

**Server tests.** Each test starts a `SeatWaveServer` on loopback with port 0, over a `ScriptedHoldStore`. The test finds the real ports with `server.port()` and `server.adminPort()`, sends HTTP requests with `java.net.http.HttpClient`, and checks status, headers and body.

**Route tests.** `HoldRoutes` is a plain class: `route(Request)` returns a `Routed` with a `Reply`. The test builds a `Request` by hand and reads the reply. No server, no sockets.

## Walkthrough of this repository's use

### `seatwave/JdbcHoldStoreTest.java`

```java
package io.plainround.seatwave.balancer.seatwave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
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
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
```

The imports. `JdbcDataSource` is H2's data source. `Callable` is a task that returns a value (a `Runnable` that gives an answer). `Set` and `HashSet` are used to count distinct hold ids.

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

Which code path does this exercise? The racers' `UPDATE`s queue behind one row lock. Those that wait find the seat held, change zero rows, and then re-read the key and replay. That is the branch in `holdInTransaction` (the re-read after a lost race). It is **not** the `catch` for the unique violation in `hold`. JaCoCo shows lines 98 to 101 and 105 to 109 of `JdbcHoldStore.java` as never run by any test, and I confirmed it with a scratch program that ran the same race (sixteen threads, one key, one seat) while counting `rollback()` calls: the count was 0.

A test for that branch is missing. I ran one by hand: sixteen threads, **one key and sixteen different seats**. Each racer takes its own seat, and then all try to insert a `holds` row with the same key. One insert passes, fifteen fail with SQLState `23505`, roll back (which frees their seat), re-read and replay. Result: sixteen granted holds, 15 `rollback()` calls, exactly one seat `HELD` and exactly one row in `holds`, on every one of four runs. So the code works on H2. A permanent test with those three assertions would pin it.

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

**`turnsADatabaseFailureIntoStoreUnavailable`.** On the unreachable store, `hold(...)` must throw `StoreUnavailableException` whose message contains `A-7`, and `find(...)` must throw the same type. `assertThatThrownBy(() -> ...)` runs the lambda and checks the exception. Both calls together take about 2.8 seconds on this machine.

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
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Result<GrantedHold, HoldRefusal>>> futures = new ArrayList<>();
    try (ExecutorService threads = Executors.newFixedThreadPool(RACERS)) {
      for (int racer = 0; racer < RACERS; racer++) {
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

`race(...)` is the helper.

1. A latch `go` of size 1 and an empty list of futures.
2. A fixed pool of 16 threads (closed by the `try` at the end).
3. For each racer, it builds the command first (`commands.forRacer(racer)`), then submits a `Callable` that waits at `go.await()` and then calls `store.hold(command)`.
4. When all are submitted, `go.countDown()` releases them together. This gives the best chance of a real overlap.
5. It collects each result with `future.get(30, SECONDS)`. A thread that threw would make `get` throw, and the test would fail with that error.

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

  private static JdbcDataSource unreachable() {
    // Port 1 on this machine: nothing listens there, so every connection is refused at once.
    JdbcDataSource down = new JdbcDataSource();
    down.setURL("jdbc:h2:tcp://127.0.0.1:1/nowhere");
    return down;
  }
}
```

`RacerCommand` is a one-method interface (`@FunctionalInterface`), so the tests can pass a lambda `racer -> command(...)`. `unreachable()` returns an H2 data source with the URL `jdbc:h2:tcp://127.0.0.1:1/nowhere`: connect to H2's network mode on port 1 of this machine. The comment says why it works: nothing listens on port 1, so every connection is refused. That is an assumption about the machine. If something did listen on port 1, the test would behave differently. A port the test itself opened and closed would be safer.

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
  private static final String BODY = "{\"eventId\":1,\"seat\":\"A-7\",\"customer\":\"fan-1\"}";

  private final ScriptedHoldStore store = new ScriptedHoldStore();
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  private SeatWaveServer server;
```

Imports and fields. `DEADLINE` and `TIMEOUT` are 10 seconds each: safety limits, not expected times. `BODY` is a valid hold request. Each test gets a new `ScriptedHoldStore`, a new `HttpClient` with a connect timeout, and a `server` field set by `start(...)`.

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
  void countsRequestsInItsMetrics() throws Exception {
    start(DEADLINE);
    server.markReady();
    hold("\"k-1\"");

    String metrics = admin("/metrics").body();

    assertThat(metrics)
        .contains("seatwave_requests_total{route=\"POST /v1/holds\",status=\"201\"} 1")
        .contains("seatwave_state{state=\"ready\"} 1")
        .contains("seatwave_request_seconds_count 1");
  }
```

**`countsRequestsInItsMetrics`.** After one hold, `GET /metrics` must contain `seatwave_requests_total{route="POST /v1/holds",status="201"} 1`, `seatwave_state{state="ready"} 1` and `seatwave_request_seconds_count 1`.

A theoretical race: the server writes the reply and only afterwards counts the request (`metrics.finished`). A client that reads `/metrics` fast enough, on the other thread, could see the counter before it moves. I ran a program that did 3,000 POST-then-metrics pairs, three times, and the counter was always already there (0 misses in 9,000 pairs), so the window is far too small to see in practice. It is still a window.

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
    assertThat(drained.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).isClean()).isTrue();
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
7. The drain result must be clean, and `notAdmittedCount()` must be exactly 1.

```java
  @Test
  void stopsAtTheDeadlineWhenARequestNeverFinishes() throws Exception {
    start(Duration.ofMillis(200));
    server.markReady();
    store.blockHolds();
    holdAsync("\"stuck\"");
    assertThat(store.awaitHoldEntered()).isTrue();

    Lifecycle.DrainResult result = server.drainAndStop(Duration.ofMillis(200));

    assertThat(result.inFlightLeft()).isEqualTo(1);
    assertThat(server.state()).isEqualTo(LifecycleState.STOPPED);
  }
```

**`stopsAtTheDeadlineWhenARequestNeverFinishes`.** Deadline 200 ms. A held request is in the store and never released. `drainAndStop(200 ms)` must return after the deadline with `inFlightLeft()` equal to 1, and the state must be `STOPPED`. It takes about five seconds because the pool waits `WORKER_STOP_SECONDS` (5) for the blocked worker before it interrupts it.

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

The helpers. `start(deadline)` builds a server on loopback with port 0, the instance name `seatwave-test`, the scripted store and a `MutableClock` fixed at 09:00. `hold(key)` sends a request and waits for the reply as a `String`; `holdAsync(key)` returns the `CompletableFuture`.

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

**`refusesAnUnknownField`.** An extra field `x` gives `400` and a body containing `/x is not a field`. This is the only test that goes through `JsonCodec`'s refusal branches in this package, which is why JaCoCo shows 22 of the codec's 50 lines as never run. No test sends an empty body, a list, trailing text, a repeated field, a text for a number or a number too large.

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
    assertThat(onHold.header("Allow")).isEqualTo("GET");
  }
```

**`answersMethodNotAllowedWithTheAllowedMethod`.** `GET /v1/holds` gives `405` with `Allow: POST`. `DELETE` on a hold path gives `Allow: GET`. The second request is built with the record constructor spread over many lines; its eight arguments are the method, the path, the query, the content type, the key, the body, the too-large flag and the correlation id.

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

What these tests leave out: the leading space in the `400` detail for whole-body problems (see `java-seatwave-walkthrough-2.md`); the `expired` state of a hold (the shared clock never moves, and JaCoCo marks `HoldView.of` as not fully covered); a replayed hold's log line; the missing `Content-Type`; and an upper-case UUID in the path.

## Try it yourself

All commands run in the project folder. Timings differ on every run.

1. Run the three classes. Thirty-five tests pass.

```bash
./gradlew --offline cleanTest test --tests '*JdbcHoldStoreTest' --tests '*SeatWaveServerTest' --tests '*HoldRoutesTest' 2>&1 | grep -cE "PASSED"
```

It prints `35`.

2. Show that the store's tests take their time from the two "database is down" tests and the deadline test. This lists the slowest tests of the last full run.

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

```text
(5.219, 'SeatWaveServerTest', 'stopsAtTheDeadlineWhenARequestNeverFinishes()')
(2.779, 'JdbcHoldStoreTest', 'turnsADatabaseFailureIntoStoreUnavailable()')
(1.393, 'JdbcHoldStoreTest', 'saysTheDatabaseIsUnreachableWhenNoConnectionCanBeHad()')
```

3. Break the race rule and watch the race test fail. Copy the project, remove the `WHERE` part that makes the `UPDATE` conditional, and run the store tests.

```bash
rm -rf build/rule-check && mkdir build/rule-check
rsync -a --exclude build --exclude .gradle ./ build/rule-check/
cd build/rule-check
sed -i.bak "s/AND (status = 'AVAILABLE' OR (status = 'HELD' AND hold_expires_at <= ?))/AND ? IS NOT NULL/" src/main/java/io/plainround/seatwave/balancer/seatwave/JdbcHoldStore.java
./gradlew --offline cleanTest test --tests '*JdbcHoldStoreTest' 2>&1 | grep -E "FAILED|BUILD"
cd ../.. && rm -rf build/rule-check
```

The `UPDATE` now takes any seat regardless of its status. Two tests fail in the run for these notes: `refusesASeatAnotherActiveHoldOwns` and `letsExactlyOneOfManyRacersHoldOneSeat`, the two that need a refusal.

4. Send a real request through the real server in a test, by running only the server tests, and read the log. The test output of Gradle hides the log lines; the pass or fail list is what you see.

```bash
./gradlew --offline cleanTest test --tests '*SeatWaveServerTest' 2>&1 | grep -E "PASSED|FAILED|BUILD"
```

Six `PASSED` lines and `BUILD SUCCESSFUL`.

## Mistakes people make

- **Racing on one key and one seat and believing every path was tested.** The test of racing retries takes the "wait and re-read" branch, not the duplicate-key branch. Choose the race inputs to reach the branch you want, and check with a coverage report.
- **A fixed sleep before checking.** The drain test waits with `await()` for the state `DRAINING` and with latches for the request to enter the store, not with `sleep`.
- **Leaving a request blocked.** `@AfterEach` releases the fake store first, or a stuck worker would hold the next test's port.
- **Assuming port 1 is closed.** `unreachable()` depends on it.
- **Reading metrics after the fact and assuming the counter is final.** The counter is moved after the reply is written; the window is tiny, but it is not zero.
- **Asserting only on the status.** The route tests also check `Retry-After`, `Allow`, `Location`, `Cache-Control` and `Connection`, because the balancer and the clients read those.
- **Testing against a convenient database and calling it proof.** These store tests run on H2. `StackTest` runs the rule on PostgreSQL.

## Check yourself

1. In `letsExactlyOneOfManyRacersHoldOneSeat`, what must each loser's outcome equal, and why can the test compare with `equals`?
   Answer: `Result.error(new HoldRefusal.SeatTaken())`. `Result` and the refusal types are records, which compare by content.
2. Why does the drain test wait for `awaitHoldEntered()` before it starts draining?
   Answer: to be sure a request is really inside the store, in flight, before the drain starts; otherwise the drain could finish before the request arrives and prove nothing.
3. Which branch of `JdbcHoldStore.hold` does no test run, and what input would run it?
   Answer: the `catch` for SQLState `23505`. Racing threads with one key and different seats run it.
4. What does `answersNotFoundForAnUnknownPathWhateverTheMethod` pin?
   Answer: a `POST` to an unknown path is `404`, not `405`.
5. Why does `stopsAtTheDeadlineWhenARequestNeverFinishes` take about five seconds?
   Answer: after the drain deadline, `drainAndStop` waits up to five seconds for the worker pool to end; the blocked worker only ends when it is interrupted after that wait.

## Further reading

- Java SE 25 API, `java.net.http.HttpClient`: https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpClient.html
- Java SE 25 API, `CompletableFuture`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/CompletableFuture.html
- Java SE 25 API, `java.util.concurrent.ExecutorService`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/ExecutorService.html
- H2 2.5 documentation, transaction isolation and locking: https://h2database.com/html/advanced.html
- PostgreSQL 18 documentation, transaction isolation: https://www.postgresql.org/docs/18/transaction-iso.html
- AssertJ core documentation: https://assertj.github.io/doc/
- JUnit 6 user guide: https://docs.junit.org/current/user-guide/
