# Awaitility 4.3.0 (waiting for a condition in a test)

## What it is

Some things a test checks happen **later**: a server has not yet noticed a request, a state has not yet changed. A test that checks too early fails for no good reason. A test that waits a fixed time with `Thread.sleep(2000)` is slow when the thing is quick and still fails when the machine is busy.

**Awaitility** is a small library that solves this. You say what must become true and for how long you are willing to wait. Awaitility checks the condition again and again, and returns the moment it is true. If the time runs out first, it throws an exception that says the condition was not fulfilled. The fixed `sleep` is replaced by a **poll**: ask, wait a short moment, ask again.

## Why this repository uses it

The module's central rule is that an instance that starts draining admits no new request, and finishes the ones in flight. To test it, a test must start a drain on another thread and then send a new request **after** the drain has begun, never before. The moment the drain begins is not known in advance. The test does not guess; it asks the server for its state until it says `DRAINING`, with a generous limit of 10 seconds that is never reached on a healthy run. The same kind of wait appears in the unit tests of the lifecycle, of the balancer watch, of the metrics, and in the stack test that waits for slow start to end.

## Where it appears

| Path | What it does with Awaitility |
| --- | --- |
| `gradle/libs.versions.toml` | `awaitility = "4.3.0"` and the library entry (`toml-version-catalog.md`). |
| `build.gradle.kts` | `testImplementation(libs.awaitility)` (`gradle.md`). |
| `gradle.lockfile` | Locks `org.awaitility:awaitility:4.3.0` and its dependency `org.hamcrest:hamcrest:2.1` on both test classpaths (`dependency-locking.md`). |
| `src/test/java/.../seatwave/LifecycleTest.java` | Two `await()` calls (lines 102 and 192): until the drain has begun, and until the racers have tried 8,000 times. |
| `src/test/java/.../seatwave/SeatWaveServerTest.java` | Two `await()` calls (lines 100 and 121): until the metrics count the request, and until the drain has begun. |
| `src/test/java/.../harness/BalancerWatchTest.java` | One `await()` call (line 32): until the watch has read the balancer once. |
| `src/test/java/.../stack/StackTest.java` | One `await()` call (line 87): until slow start is over on every instance (`tests-stack-walkthrough.md`). |

I searched `src` for `awaitility` and `await()`: only those four test files import it. Other lines match the word `await` but call different methods with that name: `CountDownLatch.await()` in `SeatWaveCli`, `BalancerWatch`, `LifecycleTest` and `JdbcHoldStoreTest`, `CyclicBarrier.await` in `JdbcStandIns`, and `Lifecycle.awaitDrained`. Other tests wait by blocking on a real reply with a timeout (`HttpClient`'s own timeout, `Future.get(timeout)`) and need no polling.

## How it works underneath

1. `await()` is a static method (imported with `import static org.awaitility.Awaitility.await;`). It returns a **condition factory** that holds the settings of this wait.
2. `.atMost(duration)` sets the longest wait. If you leave it out, the default is 10 seconds.
3. `.until(callable)` takes a function that returns `true` or `false`. A **lambda** (`() -> ...`) is a short anonymous function with no arguments. Awaitility calls it on a background thread, first after a short delay, then at a fixed interval, until it returns `true` or the time is up.
4. The defaults are a first check after 100 milliseconds and then one check every 100 milliseconds. I measured it with the 4.3.0 jar: a condition that was true at once returned after 118 ms; a condition that became true on its fourth call returned after 421 ms (checks at about 100, 200, 300 and 400 ms). Timings differ on every run.
5. If the time runs out, it throws `org.awaitility.core.ConditionTimeoutException`. I got the message `Condition with Lambda expression in A was not fulfilled within 350 milliseconds.` for a condition that was always false and a limit of 350 ms.
6. Awaitility depends on **Hamcrest** (a library of matchers), which is why `org.hamcrest:hamcrest:2.1` is in the lock file. This repository does not use Hamcrest itself.

## Walkthrough of this repository's use

### The imports and the constants

```java
import static org.awaitility.Awaitility.await;
```

An `import static` makes a static method usable by its short name, so the test writes `await()` instead of `Awaitility.await()`.

```java
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
```

One limit for the waits in the class: `Duration.ofSeconds(10)`, a `Duration` object of ten seconds. It is a ceiling, not an expected time. `LifecycleTest` has the same idea under the name `LONG_WAIT`.

### The test of the drain rule

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

- `start(DEADLINE)` starts a real SeatWave server on loopback with a drain deadline of ten seconds, and `server.markReady()` lets it admit requests. `store` is a scripted stand-in for the database (`tests-seatwave-walkthrough-1.md` and `tests-seatwave-walkthrough-2.md`); `store.blockHolds()` makes every hold wait inside the store until the test releases it.
- `CompletableFuture<HttpResponse<String>> inFlight = holdAsync("\"in-flight\"");` starts a hold request on another thread and returns at once with a **future**, a handle for a result that is not ready yet. `assertThat(store.awaitHoldEntered()).isTrue();` blocks until the request has really reached the store, so it is truly in flight.
- `CompletableFuture.supplyAsync(() -> server.drainAndStop(DEADLINE))` starts the drain on another thread, because `drainAndStop` waits for the in-flight request and would block the test.
- `await().atMost(TIMEOUT).until(() -> server.state() == LifecycleState.DRAINING);` is the wait. It asks the server for its state every 100 ms until it is `DRAINING`. Only then does the test send the new request. Without the wait, the new request could arrive before the drain began and be served, and the test would be wrong, not the server. The condition is cheap and has no side effect, which is what a polled condition should be.
- `store.releaseHolds()` lets the stuck hold finish. Then the assertions check the module's invariant: the new request got `503` with `Connection: close` and the words "nothing was done", the in-flight request still got `201`, the drain was clean and took less than half its 10 s deadline (`result.waited()`, so a drain that slept to its deadline instead of being woken fails), and exactly one request was counted as not admitted. `get(TIMEOUT.toSeconds(), TimeUnit.SECONDS)` waits for a future with its own limit, so a hung server fails the test instead of hanging the build.

### The same wait in the unit test of the lifecycle

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

`drainer.submit(() -> lifecycle.awaitDrained(LONG_WAIT))` starts the waiting drain on another thread. `await().atMost(LONG_WAIT).until(() -> lifecycle.state() == LifecycleState.DRAINING);` waits until it has begun. Then `assertThat(drained.isDone()).isFalse();` proves the drain is still waiting for the request that has not finished, and `running.close()` finishes it. Without the `await`, the first assertion would be a guess about thread timing.

### The other waits

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

The server counts a request only after it has written the answer, so a client that reads `/metrics` at once can, rarely, be a moment early. `await().atMost(TIMEOUT).until(() -> admin("/metrics").body().contains("seatwave_request_seconds_count 1"))` reads the metrics again every 100 ms until the count is there. The chained calls are written one per line. The condition sends an HTTP request each time; that is still cheap, and reading metrics changes nothing.

The race test of the lifecycle waits with `await().atMost(LONG_WAIT).until(() -> attemptsAfterDrain.get() >= RACERS * ATTEMPTS)`: until eight racers together have tried 8,000 times while the instance drains (`tests-seatwave-walkthrough-1.md`). `BalancerWatchTest` waits with `await().atMost(TIMEOUT).until(() -> readings.get() == 1)` until its watch thread has read the fake balancer once (`tests-tools-walkthrough-2.md`). `StackTest` waits up to 60 s for slow start to end with `await().atMost(SETTLED_WITHIN).until(() -> isSettled(lb2Api))`; its condition asks the real balancer each time (`tests-stack-walkthrough.md`).

## Try it yourself

All commands ran in a scratch copy on 11 November 2026.

1. Find the users:

```
grep -rn "await()" src/test
```

   Expected: lines 32 of `BalancerWatchTest.java`, 102 and 192 of `LifecycleTest.java`, 100 and 121 of `SeatWaveServerTest.java` and 87 of `StackTest.java` for Awaitility, plus line 221 of `JdbcHoldStoreTest.java`, which calls `CountDownLatch.await()` (`go.await()`): the names match, the methods do not.

2. Run just those two test classes:

```
./gradlew --offline cleanTest test --tests '*SeatWaveServerTest' --tests '*LifecycleTest' --console=plain
```

   Expected: one `PASSED` line per test (7 plus 11), including `SeatWaveServerTest > finishesTheRequestInFlightAndRefusesNewOnesWhileDraining() PASSED`, and `BUILD SUCCESSFUL`. None takes long: the slowest, `stopsAtTheDeadlineWhenARequestNeverFinishes`, took 0.323 s on my run, because it gives the server's workers only 100 ms to stop.

3. Measure the polling yourself. Save this as `A.java` in an empty folder:

```
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

public class A {
  public static void main(String[] args) {
    long t0 = System.nanoTime();
    await().atMost(Duration.ofSeconds(5)).until(() -> true);
    System.out.println("already true: " + (System.nanoTime() - t0) / 1_000_000 + " ms");

    AtomicInteger calls = new AtomicInteger();
    long t1 = System.nanoTime();
    await().atMost(Duration.ofSeconds(5)).until(() -> calls.incrementAndGet() >= 4);
    System.out.println(
        "true on call 4: " + calls.get() + " calls, " + (System.nanoTime() - t1) / 1_000_000 + " ms");

    try {
      await().atMost(Duration.ofMillis(350)).until(() -> false);
    } catch (Exception e) {
      System.out.println(e.getClass().getName());
      System.out.println(e.getMessage());
    }
  }
}
```

   Run it with the two jars on the class path:

```
CP=$(find ~/.gradle -name 'awaitility-4.3.0.jar'|head -1):$(find ~/.gradle -name 'hamcrest-2.1.jar'|head -1)
java -cp "$CP" A.java
```

   Expected, four lines: `already true: 118 ms`, `true on call 4: 4 calls, 421 ms`, `org.awaitility.core.ConditionTimeoutException` and `Condition with Lambda expression in A was not fulfilled within 350 milliseconds.` The two times differ on every run, but stay near 100 ms and 400 ms.

## Mistakes people make

- **Using `Thread.sleep` to wait for something.** It is either too short (a flaky test) or too long (a slow test). Poll for the real condition.
- **A wait with no upper limit.** A condition that never becomes true would hang the build. `atMost` (and the default of 10 seconds) prevents it.
- **Waiting on a condition that was true before the action.** The wait returns at once and proves nothing. Here the wait looks for `DRAINING`, which cannot be true until the drain has been started on the other thread.
- **Polling something expensive or with side effects.** The default interval is 100 ms. Reading the state of a lifecycle is cheap and changes nothing, so it is fine here.
- **Reading a `ConditionTimeoutException` as a bug in Awaitility.** It says the system did not reach the state in time; look at the system.
- **Confusing `await()` with `CountDownLatch.await()`.** The names match; the libraries do not.

## Check yourself

1. *What does `await().atMost(TIMEOUT).until(...)` do?* It calls the condition every 100 ms until it returns `true` or ten seconds pass.
2. *What happens when the time runs out?* It throws `ConditionTimeoutException`.
3. *Why does the test wait for `DRAINING` before sending the new request?* The request must arrive after the drain began; otherwise it would simply be served, and the test would check nothing.
4. *Why is waiting better than `Thread.sleep(2000)`?* It returns as soon as the condition is true, and it does not fail on a slow machine until the limit.
5. *Where does the Hamcrest jar in the lock file come from?* Awaitility needs it; no code of this repository imports it.

## Further reading

- Awaitility project and usage guide (4.x): <https://github.com/awaitility/awaitility/wiki/Usage>
- Awaitility on Maven Central at the pinned version: <https://central.sonatype.com/artifact/org.awaitility/awaitility/4.3.0>
- `java.util.concurrent.CompletableFuture` (JDK 25): <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/CompletableFuture.html>
