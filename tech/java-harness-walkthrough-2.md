# The `harness` package, part 2: sending the load, building the report, starting a run

This is part 2 of 2. Part 1 (`java-harness-walkthrough-1.md`) read `Outcome`, `StateChange`, `HarnessSettings` and `BalancerWatch`. Part 2 reads the three files that do the work: `LoadRun` (starts the requests on time), `RunReport` (turns outcomes into the numbers of the module) and `HarnessCli` (the command that ties everything together).

## What it is

`LoadRun` is an **open-loop load generator**. It starts requests at fixed moments, for example 3,000 per second, one every 333,333.3 nanoseconds on average, and it does not slow down when the server does. Each request runs on its own **virtual thread**, a very light thread of the Java runtime that costs a few kilobytes, so thousands can wait for answers at the same time. Every request becomes one `Outcome`.

`RunReport` takes all the outcomes and the balancers' status changes and computes the report: how many requests were answered and failed, which instance and which balancer answered, latency percentiles, how long the balancer took to remove a killed instance, the longest stretch without an answer, and a timeline.

`HarnessCli` is the entry point. It reads the settings, starts the watches and the load, waits for both, and prints the report.

## Why this repository uses it

The module's labs are about what happens to requests while something breaks. Every number in the README's captured output comes from this code: `sent 90000, answered 82347, failed 7653`, `taken out by lb1 at +20.483 s: 10871.8 ms after its last answer`, `longest gap between two answers: 107.7 ms` (the active-checks kill). If the tool counted wrongly, every conclusion would be wrong, so the arithmetic is worth reading line by line. The review of version 1.0.0 found several places where the tool did not do what a reader would expect; version 1.1.0 fixed them, and the text below says what each line does now and what it did before.

## Where it appears

| File | What it does |
|---|---|
| `harness/LoadRun.java` | Starts the requests on time, sends them with the JDK's HTTP client, records one `Outcome` each. |
| `harness/RunReport.java` | Computes and prints the report. |
| `harness/HarnessCli.java` | Reads the settings, starts the watches and the load, prints the report. |
| `json/JsonCodec.java` | Reads a hold's JSON reply into a record (not part of this package). |
| `config/kill-production.properties` and the other harness settings | Choose rate, duration and victim. |
| `scripts/lab.sh` | Runs `HarnessCli` in the `harness` container and does the disruption at a fixed time. |

## How it works underneath

**An open loop in a few lines of arithmetic.** The run plans `rate * (warm-up + duration)` requests. Request number `k` is due at `first + k * 1,000,000,000 / rate` nanoseconds, computed from `k` each time so that no rounding adds up. The main thread walks through the numbers, **parks** (sleeps) until each due time, and starts the request on a new virtual thread. It does not wait for the answer.

**Latency counts from the due time.** If the main thread is late, or the target is slow to accept a connection, the request's latency is still measured from the moment it was due. A stall anywhere therefore shows up as latency instead of vanishing.

**The in-flight limit.** A `Semaphore` is a counter of permits. The main thread takes a permit for each request and the request gives it back when it ends. When no permit is left, the request is not started and is recorded as "not sent". A server that never answers can then use up the permits, but it cannot make the harness start 100,000 threads.

**What a request is.** Half the requests (the `load.holdPercent` setting) are `POST /v1/holds`, which asks for a hold on a seat, with a unique `Idempotency-Key` header. The others are `GET /v1/holds/{id}`, which reads a hold made earlier in the run. That read has to work on any of the three instances, because the holds live in the shared database. Which kind a request is depends only on its number, so the share is exact; the report's `mix:` line prints what really ran.

**The seats.** The arena has 20 sections (A to T) of 1,000 seats: 20,000 seats. The n-th hold request asks for seat number `(seed + n x 7,919) mod 20,000`. Because the prime 7,919 shares no factor with 20,000, 20,000 hold requests in a row ask for 20,000 different seats. A hold request on a seat that is already held gets status 409 ("the seat is held or sold").

**Percentiles.** The p50 of a list of latencies is the value that half of them do not exceed (the median). The p99 is the value that 99 in 100 do not exceed. The report computes both by the **nearest rank** rule: sort the list, take the value at position `ceil(q * n)`. For 10 values, p50 is the 5th smallest; for 100 values, p99 is the 99th smallest.

**Java words used in this part.**

| Word | Meaning |
|---|---|
| virtual thread | A thread managed by the Java runtime. Blocking in one is cheap, so one per request is fine. |
| `ExecutorService` | An object that runs tasks on threads. `newVirtualThreadPerTaskExecutor` starts a virtual thread per task. |
| `Semaphore` | A counter of permits that limits how many things happen at once. |
| `AtomicLong`, `AtomicReferenceArray` | Values that several threads can update safely without a lock. |
| `ConcurrentLinkedQueue` | A queue that several threads can add to safely. |
| `HttpClient` | The JDK's client for HTTP requests. One client is shared by all the requests. |
| stream | A chain of steps (`filter`, `map`, `count`) over a collection. |
| `Optional<T>` | A box that holds a value or is empty. |
| `String.format` | Fills placeholders such as `%d`, `%.1f` and `%s` in a text. |

## Walkthrough of this repository's use

### `harness/LoadRun.java`

```java
package io.plainround.seatwave.balancer.harness;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.balancer.json.JsonCodec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;
```

The imports. Notice `java.net.http.HttpClient`, `HttpRequest` and `HttpResponse`: the JDK's own HTTP client, with no extra library. `LockSupport` is for parking a thread for a given number of nanoseconds. `Pattern` is a regular expression, used here to check that a hold id has the expected form.

```java
/**
 * Starts requests at a fixed rate whatever the answers do (an open loop), so a slow or dead target
 * shows up as failures and latency instead of quietly lowering the load (Module 1's load tool, and
 * the "coordinated omission" trap it avoids).
 *
 * <p>Each request runs on its own virtual thread with the JDK's HTTP client, shared by all of them.
 * The mix is decided by the request's index alone, so it is exactly the share the settings ask for:
 * {@code POST /v1/holds} with a unique Idempotency-Key, and {@code GET /v1/holds/{id}} of a hold
 * made earlier in the run, which any instance must be able to answer because holds live in the
 * shared database. A read that is due before the run has made any hold reads a hold id no run ever
 * makes and is answered 404; the report counts those reads apart.
 *
 * <p>The only retries in this harness are the JDK client's own, counted as what a fan's app would
 * do (the JDK's {@code MultiExchange}, checked in the 25.0.2 source): a request of any method whose
 * connection attempt was refused is tried once more ({@code jdk.httpclient.disableRetryConnect}
 * turns that off), and a {@code GET} or {@code HEAD} whose reused connection turns out closed
 * before any byte of the answer is sent once more on a new connection; a {@code POST} is not
 * (unless {@code jdk.httpclient.enableAllMethodRetry} is set, which this harness does not set). Not
 * thread-safe: one run per instance, started once.
 */
final class LoadRun {
```

The Javadoc has four claims worth knowing.

- It is an **open loop**, and the trap it avoids is called "coordinated omission": a tester that waits for the server before sending the next request leaves out exactly the requests that would have been delayed.
- Each request runs on its own virtual thread with one shared HTTP client.
- The mix is decided by the request's **index** (its number in the run) alone, so the share of holds is exactly what the settings ask for. A read that is due before the run has made any hold asks for a hold id that no run ever makes, and SeatWave answers it with 404 ("not found"). The report counts those reads on their own.
- The only retries are the JDK client's own, and they count as what a fan's app would do. The author checked them in the source of the JDK 25.0.2 class `MultiExchange`, the part of the client that decides whether to send a request again. There are two cases. A request of any method whose connection attempt was **refused** (nothing listened at the address) is tried once more; the system property `jdk.httpclient.disableRetryConnect` would turn that off. A `GET` or `HEAD` whose **reused** connection turns out closed before any byte of the answer arrives is sent once more on a new connection. A `POST` is not, unless the property `jdk.httpclient.enableAllMethodRetry` is set, and this harness does not set it. (A system property is a named setting given to the Java runtime, for example with `-Djdk.httpclient.disableRetryConnect=true`.) Version 1.0.0's Javadoc named only the second case.

"Not thread-safe: one run per instance, started once" means do not call `run()` twice on one object.

```java
  // Module 1's arena: 20 sections of 1,000 seats.
  private static final int SEATS = 20_000;
  private static final int SEATS_PER_SECTION = 1_000;
  private static final String SECTIONS = "ABCDEFGHIJKLMNOPQRST";
  // The n-th hold request of a run asks for seat (seed + n x 7,919) mod 20,000. 7,919 is a prime
  // that does not divide 20,000, so 20,000 hold requests in a row ask for every seat once.
  private static final long SEAT_STEP = 7_919;
  // Module 1's 200,000 fans.
  private static final int FANS = 200_000;
  private static final int PERCENT = 100;
  // Recent holds the reads choose from; a power of two so the index is a cheap remainder.
  private static final int RECENT_HOLDS = 1_024;
  private static final int CREATED = 201;
  private static final long NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);
  // A hold id in Module 7's form (a UUID); a reply with anything else is not remembered.
  private static final Pattern HOLD_ID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  // The nil UUID: a well-formed hold id that no run ever makes, read while no hold is known yet.
  private static final String UNKNOWN_HOLD = "00000000-0000-0000-0000-000000000000";

  private final HarnessSettings settings;
  private final HttpClient client;
  private final JsonCodec json = new JsonCodec();
  private final AtomicReferenceArray<String> recentHolds = new AtomicReferenceArray<>(RECENT_HOLDS);
  private final AtomicLong holdsMade = new AtomicLong();
  private final AtomicLong measuredHolds = new AtomicLong();
  private final AtomicLong measuredReads = new AtomicLong();
  private final AtomicLong measuredUnknownReads = new AtomicLong();
  private final AtomicLong unreadableReplies = new AtomicLong();
  private final long startNanos;
  private final String keyPrefix;
```

The constants.

- `SEATS` is 20,000, `SEATS_PER_SECTION` 1,000, and `SECTIONS` the letters A to T.
- `SEAT_STEP` is 7,919. The comment gives the rule: the n-th hold request of a run asks for seat `(seed + n x 7,919) mod 20,000`. 7,919 is a prime that does not divide 20,000, so it shares no factor with 20,000, and stepping by it visits every one of the 20,000 numbers once before it repeats. `seatFor` below computes n.
- `FANS` is 200,000 customer names. `PERCENT` is 100.
- `RECENT_HOLDS` is 1,024: the harness remembers the last 1,024 holds it made so that reads can choose from them. The comment says it is a power of two "so the index is a cheap remainder". The code uses `floorMod`, which works for any size, so that reason is now only a habit.
- `CREATED` is 201, the status for a created hold.
- `NANOS_PER_SECOND` is `TimeUnit.SECONDS.toNanos(1)`, 1,000,000,000.
- `HOLD_ID` is the form of a **UUID**, the hold id of Module 7: 8, 4, 4, 4 and 12 lowercase hexadecimal digits (0 to 9 and a to f) joined by `-`. A reply whose id has another form is not remembered.
- `UNKNOWN_HOLD` is the **nil UUID**, all zeros. It is a well-formed hold id that SeatWave never hands out, so a read of it is always answered 404. It is used while no hold is known yet.

The fields: the settings, the HTTP client, a `JsonCodec` for reading hold replies, an `AtomicReferenceArray<String>` of 1,024 slots for the recent hold ids, and an `AtomicLong` counting the holds made. Four more `AtomicLong` counters feed the report's `mix:` line: hold requests and reads due after time zero, reads of the unknown hold due after time zero, and 201 replies the harness could not read. Then the start time and the key prefix. An **atomic** value can be read and updated by many threads at once without corrupting it.

```java
  /**
   * Prepares a run.
   *
   * @param settings the run's settings
   * @param client the shared HTTP client, closed by the caller
   * @param startNanos the run's time zero, from {@link System#nanoTime}; the warm-up runs before
   *     it, so warm-up outcomes carry negative times
   * @param keyPrefix the start of every Idempotency-Key of this run, unique per run, so a second
   *     run against the same database never replays the first run's holds
   */
  LoadRun(HarnessSettings settings, HttpClient client, long startNanos, String keyPrefix) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.client = Objects.requireNonNull(client, "client");
    this.startNanos = startNanos;
    this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
  }
```

The constructor stores the pieces. The Javadoc explains two: `startNanos` is time zero, so warm-up outcomes carry negative times, and `keyPrefix` begins every Idempotency-Key and is unique per run, so a second run against the same database never replays the first run's holds. `HarnessCli` makes it from the label and the current time in milliseconds.

```java
  /**
   * Runs the load and waits for every request to finish or time out.
   *
   * @return one outcome per planned request, in no particular order
   * @throws InterruptedException if the run is interrupted
   * @throws IllegalStateException if a request ended without an outcome, which would make every
   *     number of the report wrong
   */
  List<Outcome> run() throws InterruptedException {
    Queue<Outcome> outcomes = new ConcurrentLinkedQueue<>();
    Semaphore slots = new Semaphore(settings.maxInFlight());
    long planned = settings.plannedRequests();
    long firstNanos = startNanos - settings.warmup().toNanos();
    try (ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor()) {
      for (long requestIndex = 0; requestIndex < planned; requestIndex++) {
        // From the index, never from a rounded interval: at 3,000 a second the interval is
        // 333,333.3 ns, and adding a rounded 333,333 ns would start request 15,000 (the first of
        // the measured part) 5 microseconds before time zero, among the warm-up.
        long dueNanos =
            firstNanos
                + Math.multiplyExact(requestIndex, NANOS_PER_SECOND) / settings.ratePerSecond();
        long waitNanos = dueNanos - System.nanoTime();
        if (waitNanos > 0) {
          LockSupport.parkNanos(waitNanos);
        }
        if (Thread.interrupted()) {
          throw new InterruptedException("the load run was interrupted");
        }
        long dueAt = dueNanos - startNanos;
        boolean isHold = isHold(requestIndex);
        if (dueAt >= 0) {
          (isHold ? measuredHolds : measuredReads).incrementAndGet();
        }
        if (!slots.tryAcquire()) {
          outcomes.add(Outcome.notSent(dueAt));
          continue;
        }
        long index = requestIndex;
        senders.submit(() -> {
          try {
            outcomes.add(sendSafely(index, dueAt, isHold));
          } finally {
            slots.release();
          }
        });
      }
    }
    if (outcomes.size() != planned) {
      throw new IllegalStateException(
          "the run planned " + planned + " requests but has " + outcomes.size() + " outcomes");
    }
    return new ArrayList<>(outcomes);
  }
```

`run` is the open loop.

- `outcomes` is a `ConcurrentLinkedQueue`, filled by many threads. `slots` is the `Semaphore` with `maxInFlight` permits.
- `planned` is the number of requests, and `firstNanos` is the start of the warm-up: time zero minus the warm-up.
- `try (ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor())`: a try-with-resources block. When the block ends, `close()` waits for every submitted task to finish. That is how `run` returns only after all requests are done or have timed out.
- For each request number `k`, `dueNanos` is the planned moment: `firstNanos + k * 1,000,000,000 / rate`. The product is computed first and divided once, so there is no rounding error that adds up. `Math.multiplyExact` throws if the product would not fit in a `long` instead of silently wrapping around. The comment explains why it is written this way. At 3,000 a second the gap between requests is 333,333.3 ns. Version 1.0.0 rounded the gap to 333,333 ns and added it up, so request 15,000, the first of the measured part, was due 5 microseconds before time zero and was dropped with the warm-up; every such run reported `sent 89999` instead of 90,000. Now request 15,000 is due at exactly `15,000 x 1,000,000,000 / 3,000 = 5,000,000,000` ns after the start of the warm-up, which is time zero.
- `waitNanos` is how long until then. If it is positive, `LockSupport.parkNanos(waitNanos)` sleeps that long. If the loop is behind, `waitNanos` is not positive and the loop goes straight on, which is how a late harness catches up.
- `Thread.interrupted()` checks (and clears) the interrupted flag. If set, the method throws `InterruptedException`, so a stop request ends the run.
- `dueAt` is the due time on the run's clock (negative during the warm-up). `isHold` is decided here, by `isHold(requestIndex)`, before anything is sent.
- `if (dueAt >= 0)` counts the request in the mix only if it belongs to the measured part. `(isHold ? measuredHolds : measuredReads).incrementAndGet()` picks one of the two counters with the `? :` operator (if `isHold` then the first, else the second) and adds one to it.
- `slots.tryAcquire()` takes a permit without waiting. If none is free the request is recorded with `Outcome.notSent(dueAt)` and the loop continues.
- Otherwise the request is submitted to the executor as a task: a lambda that runs `sendSafely(index, dueAt, isHold)`, adds the outcome to the queue, and in a `finally` gives the permit back. `index` is a copy in a new variable because a lambda may only use variables that do not change, and `requestIndex` changes on every turn of the loop.
- After the executor has closed, every task has ended. The check `outcomes.size() != planned` then makes sure each planned request left exactly one outcome. If not, `IllegalStateException` stops the run, as the `@throws` line says, because a missing outcome would make every number of the report wrong. With `sendSafely` below no request can end without one, so the check guards against a future mistake.
- At the end the queue is copied to a list. The order is not meaningful.

```java
  /**
   * Returns the mix of requests due after time zero, as it really ran; call after {@link #run}.
   *
   * @return the counts
   */
  Mix mix() {
    return new Mix(
        measuredHolds.get(),
        measuredReads.get(),
        measuredUnknownReads.get(),
        unreadableReplies.get());
  }

  private boolean isHold(long requestIndex) {
    return Math.floorMod(requestIndex, (long) PERCENT) < settings.holdPercent();
  }
```

`mix()` returns the four counters as one `Mix` record, to be called after `run`. `HarnessCli` passes it to the report.

`isHold` decides what a request is from its index alone. `Math.floorMod(requestIndex, 100) < settings.holdPercent()` picks, for a `holdPercent` of 50, the requests whose index modulo 100 is 0 to 49. `floorMod` is the remainder that is never negative. Because nothing else enters the decision, a "50% holds" run is 50% holds even when every hold is refused. Version 1.0.0 also turned a read into a hold whenever no hold was known yet, so a run in which every hold got 409 sent only `POST`s.

```java
  private Outcome sendSafely(long requestIndex, long dueNanos, boolean isHold) {
    try {
      return send(requestIndex, dueNanos, isHold);
    } catch (RuntimeException harnessFault) {
      // A fault of the harness itself, such as a request it cannot build, is still one outcome of
      // one planned request: it is counted and named in the report, never lost.
      return Outcome.failure(
          dueNanos,
          Math.max(dueNanos, System.nanoTime() - startNanos),
          "harness fault " + harnessFault.getClass().getSimpleName());
    }
  }
```

`sendSafely` wraps `send`. Any `RuntimeException` from it (an unexpected error in the code, such as a request that cannot be built) becomes a failed outcome with the text `harness fault` and the exception's short class name, for example `harness fault IllegalArgumentException`. The comment says why: it is still one outcome of one planned request, so it is counted and named in the report, never lost. The end time is `Math.max(dueNanos, ...)`, so it is never before the due time even if the fault happened before the request was due.

Version 1.0.0 had no such catch. An exception in the task was stored in the task's `Future` (the object `submit` returns), which nobody read, and the request vanished: a stand-in server that returned the hold id `a b` made half of a run's requests disappear without a word.

```java
  private Outcome send(long requestIndex, long dueNanos, boolean isHold) {
    HttpRequest request;
    if (isHold) {
      request = holdRequest(requestIndex);
    } else {
      Optional<String> holdToRead = recentHold(requestIndex);
      if (holdToRead.isEmpty() && dueNanos >= 0) {
        measuredUnknownReads.incrementAndGet();
      }
      request = readRequest(holdToRead.orElse(UNKNOWN_HOLD));
    }
    // Latency counts from when the request was due, not from when a busy client got round to it,
    // so a stall anywhere shows up in the numbers (the coordinated-omission rule of Module 1).
    long sentNanos = dueNanos;
    try {
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      long doneNanos = System.nanoTime() - startNanos;
      if (isHold && response.statusCode() == CREATED) {
        remember(response.body());
      }
      return Outcome.response(
          sentNanos,
          doneNanos,
          response.statusCode(),
          response.headers().firstValue("X-Served-By").orElse(""),
          response.headers().firstValue("X-Balancer").orElse(""));
    } catch (IOException failed) {
      return Outcome.failure(sentNanos, System.nanoTime() - startNanos, describe(failed));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return Outcome.failure(sentNanos, System.nanoTime() - startNanos, "interrupted");
    }
  }
```

`send` makes and sends one request.

- For a hold, `holdRequest(requestIndex)` builds the `POST`.
- For a read, `recentHold(requestIndex)` may give a hold id to read. If it is empty and the request belongs to the measured part, `measuredUnknownReads` counts it. `holdToRead.orElse(UNKNOWN_HOLD)` takes the id, or the nil UUID when there is none, and `readRequest` builds the `GET`.
- `sentNanos = dueNanos` is the rule from above: latency counts from the due time.
- `client.send(request, HttpResponse.BodyHandlers.ofString())` sends the request and waits for the whole answer as a `String`. This is a **blocking** call; on a virtual thread, waiting is cheap.
- `doneNanos` is the time the answer arrived, on the run's clock.
- If the request was a hold request and the status is 201, the body is passed to `remember` so that later reads can use the hold.
- `Outcome.response(...)` classifies the response, and takes the two headers `X-Served-By` and `X-Balancer`, or an empty string if a header is missing.
- `catch (IOException failed)` covers every network failure: a refused connection, a connect timeout, a request timeout, a reset, a closed connection. The outcome is a failure whose text comes from `describe`.
- `catch (InterruptedException ...)` sets the flag again and records the failure `interrupted`.

```java
  private Optional<String> recentHold(long requestIndex) {
    long made = holdsMade.get();
    if (made == 0) {
      return Optional.empty();
    }
    // One of the last holds made, chosen by the request's index: reads follow the writes closely,
    // as a fan's page reads the hold it just made.
    long back = Math.floorMod(requestIndex, Math.min(made, (long) RECENT_HOLDS));
    // Empty for a moment between counting a hold and storing it; the read then asks for the
    // unknown hold.
    return Optional.ofNullable(
        recentHolds.get((int) Math.floorMod(made - 1 - back, (long) RECENT_HOLDS)));
  }
```

`recentHold` picks the hold id for a read.

- `holdsMade.get()` is how many holds have been made. If none, there is nothing to read.
- `back` is how many holds back from the newest to read, chosen from the request number: `floorMod(requestIndex, min(made, 1024))`. So reads follow the writes closely, as a fan's page reads the hold it just made.
- The slot is `floorMod(made - 1 - back, 1024)`. The array is a ring: the newest hold overwrites the oldest after 1,024 holds.
- `Optional.ofNullable` gives an empty `Optional` if the slot has no id yet. The comment explains why that can happen: `remember` counts a hold before it stores the id, so for a moment the slot is empty. The read then asks for the unknown hold.

```java
  private HttpRequest holdRequest(long requestIndex) {
    String seat = seatFor(settings.seed(), requestIndex, settings.holdPercent());
    String body =
        String.format(
            Locale.ROOT,
            "{\"eventId\":1,\"seat\":\"%s\",\"customer\":\"fan-%d\"}",
            seat,
            Math.floorMod(requestIndex, (long) FANS));
    return HttpRequest.newBuilder(settings.target().resolve("/v1/holds"))
        .timeout(settings.requestTimeout())
        .header("Content-Type", "application/json")
        // One key per request: a retry by the balancer reuses it, a new request never does.
        .header("Idempotency-Key", "\"" + keyPrefix + "-" + requestIndex + "\"")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
  }
```

`holdRequest` builds the `POST`.

- `seatFor(...)` names the seat, explained next.
- The body is built with `String.format`. `%s` is the seat, `%d` the customer number (`fan-` plus the request number modulo 200,000). `Locale.ROOT` fixes the number format. The body looks like `{"eventId":1,"seat":"A-23","customer":"fan-0"}`.
- `HttpRequest.newBuilder(settings.target().resolve("/v1/holds"))` builds a request to the target address plus the path. `.timeout(...)` is the request timeout from the settings.
- The `Content-Type` header says the body is JSON. The `Idempotency-Key` header is the run's key prefix, `-`, and the request number, inside double quotes (the README's own `curl` examples quote the key too). The comment says why a key is unique per request: a retry by the balancer reuses it, so the database can replay the same hold, and a new request never shares one.

```java
  /**
   * Names the seat a hold request asks for.
   *
   * @param seed the run's start of the seat sequence
   * @param requestIndex the request's index; it must be one of the hold requests
   * @param holdPercent the share of requests that are holds
   * @return a seat label such as {@code C-415}
   */
  static String seatFor(long seed, long requestIndex, int holdPercent) {
    // The n-th hold request: holdPercent of every 100 requests are holds, so n follows from the
    // index without a shared counter. Taking the seat from n, not from the index, is what makes
    // 20,000 hold requests ask for 20,000 different seats at any hold share.
    long holdNumber =
        requestIndex / PERCENT * holdPercent + Math.floorMod(requestIndex, (long) PERCENT);
    long seatNumber = Math.floorMod(seed + holdNumber * SEAT_STEP, (long) SEATS);
    return SECTIONS.charAt((int) (seatNumber / SEATS_PER_SECTION))
        + "-"
        + (seatNumber % SEATS_PER_SECTION + 1);
  }
```

`seatFor` is `static` (it needs no object) and not `private`, so `LoadRunTest` can call it directly.

- `holdNumber` is n, the position of this request among the hold requests. In every block of 100 requests the first `holdPercent` are holds. `requestIndex / PERCENT` is the number of whole blocks before this request (integer division drops the remainder), and each block held `holdPercent` holds. `Math.floorMod(requestIndex, 100)` is the position inside the current block, which for a hold is below `holdPercent`. For `holdPercent=50`: request 0 is hold 0, request 49 is hold 49, and request 100 is hold 50. The comment says why this matters: n follows from the index without a shared counter between threads, and taking the seat from n, not from the index, makes 20,000 hold requests ask for 20,000 different seats at any hold share.
- `seatNumber = floorMod(seed + holdNumber * SEAT_STEP, SEATS)` is a number from 0 to 19,999. The section letter is `SECTIONS.charAt(seatNumber / 1000)` and the seat is `seatNumber % 1000 + 1`.
- With `seed=22` and `holdPercent=50`, request 0 asks for `A-23`, request 1 for `H-942`, request 2 for `P-861`, and request 100 for `P-973`. I computed these and the next number with a short script: the 52,500 hold requests of a 105,000-request run reach all 20,000 seats. Version 1.0.0 took the seat from the index, so with half of the indexes being holds it reached only 10,000 seats, and the later holds of a long run got 409.

```java
  private HttpRequest readRequest(String holdId) {
    return HttpRequest.newBuilder(settings.target().resolve(URI.create("/v1/holds/" + holdId)))
        .timeout(settings.requestTimeout())
        .GET()
        .build();
  }
```

`readRequest` builds the `GET /v1/holds/{holdId}`. `resolve(URI.create("/v1/holds/" + holdId))` makes the address from the id. If the id had a character that is not allowed in an address, `URI.create` would throw. That can no longer happen with a real reply, because `remember` keeps only ids of the UUID form, and if it ever did, `sendSafely` would count it.

```java
  private void remember(String body) {
    if (json.read(body, HoldReply.class)
            instanceof Result.Success<HoldReply, JsonCodec.BindingProblem>(HoldReply hold)
        && hold.holdId() != null
        && HOLD_ID.matcher(hold.holdId()).matches()) {
      long slot = holdsMade.getAndIncrement();
      recentHolds.set((int) Math.floorMod(slot, (long) RECENT_HOLDS), hold.holdId());
      return;
    }
    // A 201 whose body this harness cannot read (a field it does not know, a hold id that is not a
    // UUID) is still an answer to the fan, but no read can follow it; the report counts these.
    unreadableReplies.incrementAndGet();
  }
```

`remember` reads the body of a created hold. `json.read(body, HoldReply.class)` binds the JSON text to the record `HoldReply` and returns a `Result`. The `instanceof` with a record pattern, `Result.Success<...>(HoldReply hold)`, checks that it succeeded and takes the record apart in one step. Two more conditions follow with `&&` (and): the id is present (`!= null`, since a JSON reply may leave it out) and has the UUID form. Only then does `holdsMade.getAndIncrement()` take the next slot number, the id is stored in the ring, and `return` ends the method.

Otherwise the method goes on to `unreadableReplies.incrementAndGet()`. The comment says what that counts: a 201 whose body the harness cannot read (a field it does not know, a hold id that is not a UUID) is still an answer to the fan, but no read can follow it. The codec is strict, so if the service added a field to its reply, every 201 would land here, and the report's `mix:` line would show it. Version 1.0.0 dropped such replies without a word.

```java
  private static String describe(IOException failed) {
    // The class says what kind of failure it was; the message adds "Connection reset" and the like.
    String message = failed.getMessage() == null ? "" : ": " + failed.getMessage();
    return failed.getClass().getSimpleName() + message;
  }
```

`describe` turns an `IOException` into the text in the report: the short class name, then `: ` and the message if there is one. That is where `HttpConnectTimeoutException: HTTP connect timed out` and `IOException: HTTP/1.1 header parser received no bytes` in the README come from.

```java
  /** The body of a granted hold, Module 7's shape. */
  record HoldReply(String holdId, long eventId, String seat, String state, String expiresAt) {}

  /**
   * The requests due after time zero, by what they were.
   *
   * @param holds {@code POST /v1/holds}
   * @param reads {@code GET /v1/holds/{id}}
   * @param readsOfUnknownHold reads due before any hold was known, which ask for a hold no run
   *     makes
   * @param unreadableHoldReplies 201 replies, warm-up included, whose body the harness could not
   *     read, so no read could follow them
   */
  record Mix(long holds, long reads, long readsOfUnknownHold, long unreadableHoldReplies) {}
}
```

`HoldReply` is a record with the five fields of the service's reply. Jackson reads JSON into it by field name.

`Mix` is the record behind the report's `mix:` line, and its Javadoc names each field: holds and reads due after time zero, the reads among them that asked for the unknown hold, and the unreadable 201 replies. The last one is the only count that includes the warm-up, as the Javadoc says, because a reply the harness could not read matters whenever it happened: the reads after it have one hold fewer to choose from.

### `harness/RunReport.java`

```java
package io.plainround.seatwave.balancer.harness;

import io.plainround.seatwave.balancer.haproxy.StatRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
```

The imports: collections, streams, `Collectors` for grouping and joining, `TreeMap` and `TreeSet` (sorted maps and sets), `Function` for passing a method as a value. `BigDecimal` is an exact decimal number and `RoundingMode` names a way of rounding; together they round the factors of the Little's Law line.

```java
/**
 * Turns a run's outcomes and the balancers' status changes into the numbers this module asks for:
 * traffic share per instance, failures, latency, the kill analysis (last answer from the victim,
 * when a balancer took it out, failed requests in between), the longest gap without an answer, the
 * balancers' own retries, and a timeline. Immutable after construction; thread-safe.
 */
final class RunReport {

  private static final double NANOS_PER_MILLI = 1_000_000.0;
  private static final double NANOS_PER_SECOND = 1_000_000_000.0;
  private static final double PERCENT = 100.0;
  private static final double MEDIAN = 0.50;
  private static final double P99 = 0.99;
  // The digits the Little's Law line prints of the victim's share and of the detection time.
  private static final int SHARE_DIGITS = 4;
  private static final int SECONDS_DIGITS = 3;

  private final HarnessSettings settings;
  private final LoadRun.Mix mix;
  private final List<Outcome> outcomes;
  private final List<StateChange> changes;
  private final Map<String, List<StatRow>> backendRows;

  /**
   * Prepares a report.
   *
   * @param settings the run's settings
   * @param mix what the requests due after time zero were
   * @param outcomes every request's outcome
   * @param changes the balancers' status changes, from every watch
   * @param backendRows per balancer, the watched backend's summary row first and last read
   */
  RunReport(
      HarnessSettings settings,
      LoadRun.Mix mix,
      List<Outcome> outcomes,
      List<StateChange> changes,
      Map<String, List<StatRow>> backendRows) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.mix = Objects.requireNonNull(mix, "mix");
    // The warm-up's requests started before time zero and are left out of every number.
    this.outcomes = outcomes.stream().filter(outcome -> outcome.sentNanos() >= 0).toList();
    this.changes = changes.stream().sorted(Comparator.comparingLong(StateChange::atNanos)).toList();
    this.backendRows = Map.copyOf(backendRows);
  }
```

The report is built from five inputs: the settings, the mix of requests from `LoadRun`, the outcomes, the status changes and the backend rows. The constructor does two things beyond storing. It keeps only outcomes with `sentNanos() >= 0`, which removes the warm-up. It sorts the status changes by time. `LoadRun.Mix` is written with the outer class's name because `Mix` is a record declared inside `LoadRun`.

`NANOS_PER_MILLI`, `NANOS_PER_SECOND` and `PERCENT` are `double` constants used in the arithmetic. `MEDIAN` is 0.50 and `P99` is 0.99. `SHARE_DIGITS` (4) and `SECONDS_DIGITS` (3) are the digits the Little's Law line prints of the victim's share and of the detection time, as the comment says; `killLines` below uses them.

```java
  /**
   * Counts answered requests per instance.
   *
   * @return instance name to answers, sorted by name
   */
  Map<String, Long> answersByInstance() {
    return countAnswers(Outcome::servedBy);
  }

  /**
   * Counts answered requests per balancer.
   *
   * @return balancer name to answers, sorted by name
   */
  Map<String, Long> answersByBalancer() {
    return countAnswers(Outcome::balancer);
  }
```

`answersByInstance` and `answersByBalancer` count answered requests grouped by the `X-Served-By` header and by the `X-Balancer` header. `Outcome::servedBy` is a **method reference**: a short way to pass the method as a value.

```java
  /**
   * Analyses the removal of the victim instance, if the settings name one.
   *
   * @return the analysis, or empty without a victim or without any answer from it
   */
  Optional<KillAnalysis> killAnalysis() {
    if (settings.victim().isEmpty()) {
      return Optional.empty();
    }
    String victim = settings.victim().get();
    Optional<Long> lastAnswer =
        answered()
            .filter(outcome -> outcome.servedBy().equals(victim))
            .map(Outcome::doneNanos)
            .max(Long::compare);
    if (lastAnswer.isEmpty()) {
      return Optional.empty();
    }
    long lastNanos = lastAnswer.get();
    Optional<StateChange> removal =
        changes.stream()
            .filter(change -> change.server().equals(victim) && change.isRemoval())
            .filter(change -> change.atNanos() >= lastNanos)
            .findFirst();
    long answeredBefore = answered().filter(outcome -> outcome.doneNanos() <= lastNanos).count();
    long victimBefore =
        answered()
            .filter(outcome -> outcome.doneNanos() <= lastNanos)
            .filter(outcome -> outcome.servedBy().equals(victim))
            .count();
    double victimShare = answeredBefore == 0 ? 0 : (double) victimBefore / answeredBefore;
    long removedNanos = removal.map(StateChange::atNanos).orElse(Long.MAX_VALUE);
    long failedBetween =
        failed()
            .filter(outcome -> outcome.sentNanos() >= lastNanos)
            .filter(outcome -> outcome.sentNanos() <= removedNanos)
            .count();
    long failedAfter = failed().filter(outcome -> outcome.sentNanos() > removedNanos).count();
    return Optional.of(
        new KillAnalysis(
            victim,
            lastNanos,
            removal.isPresent(),
            removedNanos,
            removal.map(StateChange::balancer).orElse(""),
            victimShare,
            failedBetween,
            failedAfter));
  }
```

`killAnalysis` answers: when did a balancer take the victim out, and how many requests failed before that?

- With no victim in the settings, there is no analysis.
- `lastAnswer` is the latest `doneNanos` among answered requests served by the victim. If the victim never answered, there is nothing to analyse.
- `removal` is the first status change that is a removal of the victim and happened at or after the last answer. This is how the analysis ignores a drain that happened before the instance's last answer.
- `answeredBefore` and `victimBefore` count answers that ended up to the last answer, and `victimShare` is their ratio: what share of the traffic the victim carried before it died.
- `removedNanos` is the time of the removal, or `Long.MAX_VALUE` if none was seen.
- `failedBetween` counts failed requests **sent** between the last answer and the removal. `failedAfter` counts failures sent after the removal.

A request that was sent before the victim's last answer and failed anyway is in neither count. In the README's active-checks kill, 7,653 requests failed, 7,653 were sent in between and 0 after, so none was sent earlier.

```java
  /**
   * Finds the longest time between two consecutive answers.
   *
   * @return the gap, or empty with fewer than two answers
   */
  Optional<Gap> longestGap() {
    List<Long> doneTimes = answered().map(Outcome::doneNanos).sorted().toList();
    if (doneTimes.size() < 2) {
      return Optional.empty();
    }
    long bestStart = doneTimes.getFirst();
    long bestEnd = bestStart;
    for (int index = 1; index < doneTimes.size(); index++) {
      if (doneTimes.get(index) - doneTimes.get(index - 1) > bestEnd - bestStart) {
        bestStart = doneTimes.get(index - 1);
        bestEnd = doneTimes.get(index);
      }
    }
    long start = bestStart;
    long end = bestEnd;
    long failedInside =
        failed()
            .filter(outcome -> outcome.sentNanos() >= start && outcome.sentNanos() <= end)
            .count();
    return Optional.of(new Gap(start, end, failedInside));
  }
```

`longestGap` sorts the finish times of all answered requests and walks through consecutive pairs. It keeps the pair with the largest difference, and counts the failed requests sent inside that stretch. Starting with `bestStart = bestEnd = first` makes the first best difference 0, so any positive difference replaces it. `start` and `end` are copies because a lambda may only use variables that do not change.

```java
  /**
   * Writes the whole report.
   *
   * @return the report text, one line per fact
   */
  String render() {
    List<String> lines = new ArrayList<>();
    summary(lines);
    share(lines);
    balancerLines(lines);
    killAnalysis().ifPresent(analysis -> killLines(lines, analysis));
    longestGap()
        .ifPresent(gap ->
            lines.add(
                String.format(
                    Locale.ROOT,
                    "longest gap between two answers: %s, from %s to %s (%d failed requests sent"
                        + " inside it)",
                    millis(gap.endNanos() - gap.startNanos()),
                    at(gap.startNanos()),
                    at(gap.endNanos()),
                    gap.failedInside())));
    timeline(lines);
    return String.join("\n", lines) + "\n";
  }
```

`render` builds the report as a list of lines: the summary, the shares, the balancer lines, the kill lines if there is a victim, the longest gap, and the timeline. `ifPresent` runs its lambda only if the `Optional` has a value. The lines are joined with newlines and one more is added at the end.

```java
  private void summary(List<String> lines) {
    lines.add(
        String.format(
            Locale.ROOT,
            "run %s: %s, %d requests/s for %.1f s after a %.1f s warm-up, %d%% holds; client"
                + " timeouts: connect %d ms, request %d ms",
            settings.label(),
            settings.target(),
            settings.ratePerSecond(),
            settings.duration().toMillis() / (NANOS_PER_SECOND / NANOS_PER_MILLI),
            settings.warmup().toMillis() / (NANOS_PER_SECOND / NANOS_PER_MILLI),
            settings.holdPercent(),
            settings.connectTimeout().toMillis(),
            settings.requestTimeout().toMillis()));
    long sent =
        outcomes.stream().filter(outcome -> outcome.kind() != Outcome.Kind.NOT_SENT).count();
    long failedCount = failed().count();
    lines.add(
        String.format(
            Locale.ROOT,
            "sent %d, answered %d, failed %d (%.3f%%), not sent %d",
            sent,
            answered().count(),
            failedCount,
            sent == 0 ? 0 : failedCount * PERCENT / sent,
            outcomes.size() - sent));
    // What really ran, so a run labelled "50% holds" shows whether it was.
    lines.add(
        String.format(
            Locale.ROOT,
            "mix: %d holds (POST), %d reads (GET), of which %d of a hold not made yet; %d hold"
                + " replies the harness could not read",
            mix.holds(),
            mix.reads(),
            mix.readsOfUnknownHold(),
            mix.unreadableHoldReplies()));
    lines.add(
        "statuses: "
            + joined(
                count(
                    outcomes.stream().filter(outcome -> outcome.status() > 0).toList(),
                    outcome -> Integer.toString(outcome.status()))));
    Map<String, Long> failures = count(failed().toList(), Outcome::failure);
    lines.add("failures: " + (failures.isEmpty() ? "none" : joined(failures)));
    List<Long> latencies = answered().map(Outcome::latencyNanos).sorted().toList();
    if (!latencies.isEmpty()) {
      lines.add(
          String.format(
              Locale.ROOT,
              "latency of answered requests: p50 %s, p99 %s, max %s",
              millis(rank(latencies, MEDIAN)),
              millis(rank(latencies, P99)),
              millis(latencies.getLast())));
    }
  }
```

`summary` makes the first lines.

- Line 1: label, target, rate, duration and warm-up in seconds, hold percent, timeouts. `%.1f` is a number with one decimal, `%d` a whole number, and `%%` a literal percent sign. The seconds come from `toMillis() / (NANOS_PER_SECOND / NANOS_PER_MILLI)`, that is, milliseconds divided by 1,000.0.
- Line 2: `sent` counts every outcome that is not `NOT_SENT`. Then answered, failed with its percentage (`failedCount * 100 / sent`), and not sent. If nothing was sent the percentage is 0.
- Line 3, `mix:`, prints the four counts of `LoadRun.Mix`: hold requests and reads due after time zero, the reads among them that asked for a hold not made yet, and the 201 replies the harness could not read. The comment says why the line exists: a run labelled "50% holds" shows whether it really was. In the README's active-checks kill it reads `mix: 45000 holds (POST), 45000 reads (GET), of which 0 of a hold not made yet; 0 hold replies the harness could not read`. The two `%d` placeholders on the second text piece are joined to the first by `+`; Java joins the two pieces into one format text before `String.format` sees it.
- `statuses:` counts the HTTP statuses of the outcomes that had one (greater than 0), sorted by status number.
- `failures:` counts the failure texts, or says `none`. In the README's active-checks kill: `HTTP 502 2, HTTP 503 2882, IOException: HTTP/1.1 header parser received no bytes 4769`.
- The latency line is printed only if there is any answered request: p50, p99 and the maximum, each as `12.3 ms`, from the sorted latency list.

```java
  private void share(List<String> lines) {
    lines.add("share by instance: " + percentages(answersByInstance()));
    lines.add("share by balancer: " + percentages(answersByBalancer()));
  }
```

Two lines of traffic share.

```java
  private void balancerLines(List<String> lines) {
    lines.add("balancer status changes (backend " + settings.backend() + "):");
    if (changes.isEmpty()) {
      lines.add("  none");
    }
    for (StateChange change : changes) {
      lines.add(
          String.format(
              Locale.ROOT,
              "  %s %s %s: %s -> %s",
              at(change.atNanos()),
              change.balancer(),
              change.server(),
              change.from(),
              change.to()));
    }
    new TreeMap<>(backendRows)
        .forEach((balancer, rows) ->
            lines.add(
                String.format(
                    Locale.ROOT,
                    "balancer %s during the run (backend %s): %d requests passed, %d retries, %d"
                        + " redispatches",
                    balancer,
                    settings.backend(),
                    rows.getLast().totalSessions() - rows.getFirst().totalSessions(),
                    rows.getLast().retries() - rows.getFirst().retries(),
                    rows.getLast().redispatches() - rows.getFirst().redispatches())));
  }
```

`balancerLines` prints the status changes, one line each, with the time as `+12.459 s`, the balancer, the server, and `from -> to`. With no change it prints `none`. Then, for each balancer (sorted by name with a `TreeMap`), it subtracts the first row from the last row of the backend summary: requests passed (`stot`), retries (`wretr`) and redispatches (`wredis`). In the README's kill with production checks: `105000 requests passed, 13 retries, 18 redispatches`.

```java
  private void killLines(List<String> lines, KillAnalysis analysis) {
    lines.add("removal of " + analysis.victim() + ":");
    lines.add("  last answer from " + analysis.victim() + ": " + at(analysis.lastAnswerNanos()));
    if (!analysis.isRemoved()) {
      lines.add("  no balancer took it out after its last answer during the run");
      return;
    }
    long detectionNanos = analysis.removedNanos() - analysis.lastAnswerNanos();
    // The product of the factors as printed, so the line multiplies out for a reader.
    double shownShare = roundTo(analysis.victimShare(), SHARE_DIGITS);
    double shownSeconds = roundTo(detectionNanos / NANOS_PER_SECOND, SECONDS_DIGITS);
    double expected = settings.ratePerSecond() * shownShare * shownSeconds;
    lines.add(
        String.format(
            Locale.ROOT,
            "  taken out by %s at %s: %s after its last answer",
            analysis.removedBy(),
            at(analysis.removedNanos()),
            millis(detectionNanos)));
    lines.add(
        String.format(
            Locale.ROOT,
            "  failed requests sent in between: %d; after: %d; Little's Law without retries:"
                + " %d requests/s x %.4f share x %.3f s = %.0f",
            analysis.failedBetween(),
            analysis.failedAfter(),
            settings.ratePerSecond(),
            shownShare,
            shownSeconds,
            expected));
  }
```

`killLines` prints the removal analysis.

- The first lines name the victim and its last answer. If no balancer took it out, the report says so and returns.
- `detectionNanos` is the removal time minus the last answer: the detection time.
- `shownShare` is the victim's share rounded to 4 digits (`SHARE_DIGITS`), and `shownSeconds` is the detection time in seconds rounded to 3 digits (`SECONDS_DIGITS`). These are exactly the numbers the line prints with `%.4f` and `%.3f`.
- `expected` is the module's simple forecast: `rate * shownShare * shownSeconds`. It is how many requests were sent to the victim's share while the balancer did not yet know it was dead. The comment says why it multiplies the rounded factors: a reader who multiplies the printed numbers gets the printed product. For the README's active-checks kill the line reads `3000 requests/s x 0.3333 share x 10.872 s = 10871`, and 3,000 times 0.3333 times 10.872 is 10,870.9, printed by `%.0f` as 10871. (Version 1.0.0 printed the share with 3 digits and computed the product from the unrounded numbers, so the printed factors did not multiply out to the printed result.)
- The module names this "Little's Law without retries". It is really the arrival rate times the time. It counts the failures only when each request to the victim fails and nothing retries it. In that same run 7,653 requests failed, 3,218 fewer than the 10,871 forecast, because the fans' client sent many failed reads once more on a new connection, and the retry reached a live instance (`measuring-a-balancer.md` reads this in full).

The test `printsALittlesLawLineWhoseFactorsMultiplyOut` in `RunReportTest` renders a report with a removed victim and checks this line.

```java
  private void timeline(List<String> lines) {
    long stepNanos = settings.timelineStep().toNanos();
    TreeSet<String> instances = new TreeSet<>(answersByInstance().keySet());
    StringBuilder header =
        new StringBuilder(
            String.format(
                Locale.ROOT,
                "%9s %7s %8s %7s %9s",
                "from s",
                "sent",
                "answered",
                "failed",
                "p99 ms"));
    instances.forEach(name -> header.append(String.format(Locale.ROOT, " %11s", name)));
    lines.add("timeline:");
    lines.add(header.toString());
    long lastNanos = outcomes.stream().mapToLong(Outcome::doneNanos).max().orElse(0);
    for (long fromNanos = 0; fromNanos <= lastNanos; fromNanos += stepNanos) {
      long start = fromNanos;
      long end = fromNanos + stepNanos;
      List<Outcome> sentHere =
          outcomes.stream()
              .filter(outcome -> outcome.sentNanos() >= start && outcome.sentNanos() < end)
              .toList();
      StringBuilder row =
          new StringBuilder(
              String.format(
                  Locale.ROOT,
                  "%9.1f %7d %8d %7d %9.1f",
                  start / NANOS_PER_SECOND,
                  sentHere.stream()
                      .filter(outcome -> outcome.kind() != Outcome.Kind.NOT_SENT)
                      .count(),
                  sentHere.stream()
                      .filter(outcome -> outcome.kind() == Outcome.Kind.ANSWERED)
                      .count(),
                  sentHere.stream()
                      .filter(outcome -> outcome.kind() == Outcome.Kind.FAILED)
                      .count(),
                  p99Millis(sentHere)));
      for (String name : instances) {
        long count =
            sentHere.stream()
                .filter(outcome ->
                    outcome.kind() == Outcome.Kind.ANSWERED && outcome.servedBy().equals(name))
                .count();
        row.append(String.format(Locale.ROOT, " %11d", count));
      }
      lines.add(row.toString());
    }
  }
```

`timeline` prints a table with one row per `report.timelineMillis` step (1 second in the lab files).

- The header uses `%9s %7s %8s %7s %9s` to line up the columns `from s`, `sent`, `answered`, `failed` and `p99 ms`, and one more column of width 11 per instance (a `TreeSet` keeps their names sorted).
- The loop walks `fromNanos` from 0 to the last finish time in steps.
- `sentHere` is the outcomes whose **sent** (due) time falls in the step. All the columns are about requests *sent* in that second, not requests that *finished* in it.
- Each column counts one kind: sent (not `NOT_SENT`), answered, failed, then p99 of the answered ones, then for each instance the answers it served.

A row in which the failed column is high and one instance column is low is the picture of a kill.

```java
  private static double p99Millis(List<Outcome> selected) {
    List<Long> latencies =
        selected.stream()
            .filter(outcome -> outcome.kind() == Outcome.Kind.ANSWERED)
            .map(Outcome::latencyNanos)
            .sorted()
            .toList();
    return latencies.isEmpty() ? 0 : rank(latencies, P99) / NANOS_PER_MILLI;
  }
```

`p99Millis` sorts the latencies of the answered requests in a step and takes the nearest-rank p99, or 0 if there are none.

```java
  private Stream<Outcome> answered() {
    return outcomes.stream().filter(outcome -> outcome.kind() == Outcome.Kind.ANSWERED);
  }

  private Stream<Outcome> failed() {
    return outcomes.stream().filter(outcome -> outcome.kind() == Outcome.Kind.FAILED);
  }

  private Map<String, Long> countAnswers(Function<Outcome, String> key) {
    return count(answered().filter(outcome -> !key.apply(outcome).isEmpty()).toList(), key);
  }

  private static Map<String, Long> count(List<Outcome> selected, Function<Outcome, String> key) {
    return selected.stream()
        .collect(Collectors.groupingBy(key, TreeMap::new, Collectors.counting()));
  }
```

`answered()` and `failed()` are streams over the outcomes of one kind. A stream can be read only once, so each call makes a new one. `countAnswers` leaves out answers with an empty key (a response without the header). `count` groups a list by a key into a `TreeMap`, so the lines come out in sorted order. `Collectors.groupingBy(key, TreeMap::new, Collectors.counting())` means "group by this key, into a `TreeMap`, counting the items in each group".

```java
  private static String joined(Map<String, Long> counts) {
    return counts.entrySet().stream()
        .map(entry -> entry.getKey() + " " + entry.getValue())
        .collect(Collectors.joining(", "));
  }

  private static String percentages(Map<String, Long> counts) {
    long total = counts.values().stream().mapToLong(Long::longValue).sum();
    if (total == 0) {
      return "no answers";
    }
    return counts.entrySet().stream()
        .map(entry ->
            String.format(
                Locale.ROOT,
                "%s %.1f%% (%d)",
                entry.getKey(),
                entry.getValue() * PERCENT / total,
                entry.getValue()))
        .collect(Collectors.joining(", "));
  }
```

`joined` writes a count map as `key count, key count`. `percentages` writes shares as `seatwave-1 33.3% (20000)`: the count times 100 divided by the total, with one decimal, and `no answers` if the total is 0.

```java
  private static long rank(List<Long> sorted, double quantile) {
    // Nearest rank: the smallest value with at least this share of values at or below it.
    int index = (int) Math.ceil(quantile * sorted.size()) - 1;
    return sorted.get(Math.max(0, index));
  }

  private static double roundTo(double value, int digits) {
    return BigDecimal.valueOf(value).setScale(digits, RoundingMode.HALF_UP).doubleValue();
  }

  private static String millis(long nanos) {
    return String.format(Locale.ROOT, "%.1f ms", nanos / NANOS_PER_MILLI);
  }

  private static String at(long nanos) {
    return String.format(Locale.ROOT, "%+.3f s", nanos / NANOS_PER_SECOND);
  }
```

`rank` is the nearest-rank rule: the index is `ceil(quantile * n) - 1`, and `Math.max(0, ...)` keeps it from going below zero. For 100 sorted values and the quantile 0.99, the index is `ceil(99.0) - 1 = 98`, the 99th smallest value.

`roundTo` rounds a number to a given count of digits after the decimal point, the way a person does on paper. `BigDecimal.valueOf(value)` turns the `double` into an exact decimal number built from the shortest text that names the `double` (so `0.33335` stays `0.33335`, not the binary neighbor `0.333349999...`). `setScale(digits, RoundingMode.HALF_UP)` keeps `digits` decimals and rounds a 5 upward. `doubleValue()` turns it back into a `double`. `killLines` uses it so the printed factors and the product agree.

`millis` writes nanoseconds as `12.3 ms`. `at` writes a time on the run's clock as `+12.459 s`; the `+` flag in `%+.3f` always prints the sign, so the warm-up times show as negative.

```java
  /**
   * What the removal of one instance cost.
   *
   * @param victim the instance
   * @param lastAnswerNanos when its last answer arrived
   * @param isRemoved true if a balancer showed it out of rotation after that
   * @param removedNanos when that happened first; {@link Long#MAX_VALUE} when it did not
   * @param removedBy that balancer's name, or an empty string
   * @param victimShare its share of answers before its last answer
   * @param failedBetween failed requests sent between the last answer and the removal
   * @param failedAfter failed requests sent after the removal
   */
  record KillAnalysis(
      String victim,
      long lastAnswerNanos,
      boolean isRemoved,
      long removedNanos,
      String removedBy,
      double victimShare,
      long failedBetween,
      long failedAfter) {

    /** Checks the fields. */
    KillAnalysis {
      Objects.requireNonNull(victim, "victim");
      Objects.requireNonNull(removedBy, "removedBy");
    }
  }

  /**
   * A stretch of time without an answer.
   *
   * @param startNanos the answer before it
   * @param endNanos the answer after it
   * @param failedInside failed requests sent inside it
   */
  record Gap(long startNanos, long endNanos, long failedInside) {}
}
```

Two small records. `KillAnalysis` holds the numbers of the removal analysis; its compact constructor only rejects a missing name. `Gap` holds the start, the end and the failed count of the longest stretch. (Version 1.0.0 had a method `detectionMillis` here that only a test called; it is gone, and `killLines` computes the detection time itself.)

### `harness/HarnessCli.java`

```java
package io.plainround.seatwave.balancer.harness;

import io.plainround.seatwave.balancer.config.SettingsFiles;
import io.plainround.seatwave.balancer.haproxy.NamedBalancer;
import io.plainround.seatwave.balancer.haproxy.StatRow;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
```

The imports.

```java
/**
 * Command-line entry point of the kill-and-drain harness: runs the load the settings describe,
 * watches every balancer's view of the backend during it, and prints the report. It changes
 * nothing; the kills, stops and drains come from {@code scripts/lab.sh} and the deploy tool, which
 * run beside it. Exit status 2 means bad usage or an invalid settings file.
 */
public final class HarnessCli {

  static final int EXIT_BAD_USAGE = 2;
  private static final int EXIT_INTERRUPTED = 1;

  private HarnessCli() {}

  /**
   * Runs one load run.
   *
   * @param arguments the settings file, for example {@code /app/config/kill.properties}
   */
  public static void main(String[] arguments) {
    int status = run(arguments, System.out, System.err);
    if (status != 0) {
      System.exit(status);
    }
  }
```

The Javadoc says what the class does and that it changes nothing. `EXIT_BAD_USAGE` is 2. `main` calls `run` with the real output streams and exits with the status only if it is not 0.

```java
  /**
   * Checks the settings, runs the load and prints the report.
   *
   * @param arguments the command line
   * @param out where the report goes
   * @param err where problems go
   * @return the exit status
   */
  static int run(String[] arguments, PrintStream out, PrintStream err) {
    if (arguments.length != 1) {
      err.println("usage: load <harness.properties>");
      return EXIT_BAD_USAGE;
    }
    Optional<HarnessSettings> loaded =
        SettingsFiles.load(Path.of(arguments[0]), HarnessSettings::from, err);
    if (loaded.isEmpty()) {
      return EXIT_BAD_USAGE;
    }
    HarnessSettings settings = loaded.get();
    try {
      out.print(measure(settings, Clock.systemUTC()).render());
      return 0;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      err.println("the run was interrupted; no report");
      return EXIT_INTERRUPTED;
    }
  }
```

`run` needs exactly one argument, the settings file. It loads the settings with `SettingsFiles.load`, which prints all the problems to `err` and returns an empty `Optional` on failure; then the status is 2. Otherwise it measures and prints `measure(...).render()` to `out`. An `InterruptedException` prints that the run was interrupted, sets the flag again and returns 1.

```java
  private static RunReport measure(HarnessSettings settings, Clock clock)
      throws InterruptedException {
    // Time zero is the end of the warm-up: the watches and the load start now, the report counts
    // from then on.
    long startNanos = System.nanoTime() + settings.warmup().toNanos();
    String keyPrefix = settings.label() + "-" + clock.millis();
    List<BalancerWatch> watches = new ArrayList<>();
    List<Thread> threads = new ArrayList<>();
    for (NamedBalancer balancer : settings.balancers()) {
      BalancerWatch watch =
          new BalancerWatch(balancer, settings.backend(), settings.watchInterval(), startNanos);
      watches.add(watch);
      threads.add(Thread.ofPlatform().name("watch-" + balancer.name()).start(watch));
    }
    List<Outcome> outcomes;
    LoadRun.Mix mix;
    try (HttpClient client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(settings.connectTimeout())
            .build()) {
      LoadRun load = new LoadRun(settings, client, startNanos, keyPrefix);
      outcomes = load.run();
      mix = load.mix();
    } finally {
      // Stopped on every path, so no watch thread outlives the run.
      for (BalancerWatch watch : watches) {
        watch.stop();
      }
    }
    for (Thread thread : threads) {
      thread.join();
    }
    List<StateChange> changes = new ArrayList<>();
    Map<String, List<StatRow>> backendRows = new HashMap<>();
    for (BalancerWatch watch : watches) {
      changes.addAll(watch.changes());
      watch.backendRows().ifPresent(rows -> backendRows.put(watch.balancerName(), rows));
    }
    return new RunReport(settings, mix, outcomes, changes, backendRows);
  }
```

`measure` is the whole run.

- `startNanos = System.nanoTime() + settings.warmup().toNanos()` is time zero, one warm-up from now.
- `keyPrefix` is the label, `-`, and the current time in milliseconds. It makes the Idempotency-Keys unique per run.
- For each balancer it creates a `BalancerWatch` and starts it on a platform thread named `watch-lb1` and `watch-lb2`. `Thread.ofPlatform().name(...).start(watch)` creates, names and starts a thread in one chain.
- `outcomes` and `mix` are declared before the `try` block, without a value, because they are filled inside it and used after it. Java checks that both have a value on every path that reaches their use.
- `HttpClient.newBuilder().version(HTTP_1_1).connectTimeout(...).build()` makes the shared client. The code fixes HTTP/1.1 and gives no reason. My reading: the JDK client otherwise tries HTTP/2 first, and over plain HTTP it does so with an upgrade request, which a lab that wants simple one-request-at-a-time connections does not need. The client is declared in a try-with-resources block, so it is closed afterwards.
- `LoadRun load = new LoadRun(...)` keeps the run in a variable, because two calls are needed: `load.run()` runs the load and returns the outcomes, and `load.mix()` then returns the counts of what really ran. If `run()` finds fewer outcomes than planned, it throws `IllegalStateException`; nothing here catches it, so the program stops with that message instead of printing a wrong report.
- The `finally` stops every watch on every path, so no watch thread outlives the run. Each `stop()` waits for the last reading.
- The threads are joined. The changes of all watches are put together, and the backend rows are collected by balancer name.
- The result is a `new RunReport(settings, mix, outcomes, changes, backendRows)`.

## Try it yourself

Run these in the repository folder. Nothing here needs Docker. The first uses the test suite; the second runs a whole report on your computer. Timings differ on every run.

**1. Run the tests.** The command is under "Try it yourself" in `java-harness-walkthrough-1.md`. The file `LoadRunTest` has 7 tests, `RunReportTest` 14 and `HarnessCliTest` 5. Two of them check the fixes described above: `startsTheMeasuredPartAtTimeZeroAtARateThatDoesNotDivideASecond` and `printsALittlesLawLineWhoseFactorsMultiplyOut`.

**2. Coverage of the report code.** The JaCoCo report of `./gradlew check` shows which lines the tests never ran. To see it for `RunReport` and `LoadRun`:

```bash
./gradlew check --console=plain | tail -n 3
python3 - <<'PY'
import xml.etree.ElementTree as ET
root = ET.parse('build/reports/jacoco/test/jacocoTestReport.xml').getroot()
for package in root.findall('package'):
    for source in package.findall('sourcefile'):
        if source.get('name') in ('RunReport.java', 'LoadRun.java'):
            missed = [line.get('nr') for line in source.findall('line') if int(line.get('mi')) > 0]
            print(source.get('name'), 'never ran:', ' '.join(missed))
PY
```

```text
BUILD SUCCESSFUL in 7s
9 actionable tasks: 5 executed, 4 up-to-date
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.9.1/userguide/configuration_cache_enabling.html
RunReport.java never ran: 99 113 117 155 201 210 340 380
LoadRun.java never ran: 123 131 132 145 146 209 210 211 289
```

A line counts as "never ran" here when at least one of its instructions never ran. In `LoadRun` these are the rare paths: the interrupted run (123, 209 to 211), a request with no free permit (131 and 132), the outcome count check that throws (145 and 146), and an `IOException` without a message (289). In version 1.0.0 the whole Little's Law part of `killLines` was in this list; now a test runs it. The time and the task counts differ on every run.

**3. Run the arithmetic of the due time.** This compares version 1.0.0's rounded interval with the computation from the index:

```bash
jshell - <<'EOF'
long rounded = 1_000_000_000L / 3000;
System.out.println("old: request 15000 is due " + (15000 * rounded - 5_000_000_000L) + " ns after time zero");
System.out.println("new: request 15000 is due " + (Math.multiplyExact(15000L, 1_000_000_000L) / 3000 - 5_000_000_000L) + " ns after time zero");
EOF
```

```text
old: request 15000 is due -5000 ns after time zero
new: request 15000 is due 0 ns after time zero
```

A negative number means the request would be dropped as part of the warm-up. Zero means it is the first request of the measured part, as planned.

**4. A whole run.** See `measuring-a-balancer.md`, section "Try it yourself". It starts a stand-in on the loopback address and runs this harness against it.

## Mistakes people make

- **Letting the tester slow down with the server.** That is a closed loop. Here the main thread parks until each due time and never waits for an answer.
- **Starting one real thread per request.** Thousands of platform threads cost memory. Virtual threads are cheap enough for one per request, and the semaphore caps how many are in flight.
- **Forgetting that streams are single-use.** `answered()` makes a new stream on each call for this reason.
- **Adding up a rounded interval.** It makes the schedule drift: version 1.0.0 lost one request per run this way. Here each due time is computed from the index in one step.
- **Dropping an exception in a background task.** `submit` stores the exception in a `Future`, which nobody reads. Here `sendSafely` turns it into an outcome, and `run` checks that every planned request has one.
- **Printing rounded factors next to a product of unrounded ones.** A reader multiplies the printed numbers and gets a different result. Here the product is computed from the rounded factors.
- **Reading the mix from the setting.** A setting says what was asked for, not what ran. Here the mix follows from the index alone, and the `mix:` line prints what really ran, including reads of a hold not made yet and replies the harness could not read.
- **Using the mean of latencies.** One slow request hides in an average. This report prints p50, p99 and the maximum.

## Check yourself

1. Why does a request's latency count from its due time instead of from the moment it was sent? *Answer: If the harness is late or the target accepts connections slowly, a stall would otherwise be left out. Counting from the due time puts every delay into the number.*
2. What does `close()` on the executor do at the end of the `try` block in `run`? *Answer: It waits for every submitted request task to finish, so `run` returns only when all outcomes are in.*
3. For 10 sorted latencies, which one is p50 by the nearest-rank rule? *Answer: The 5th smallest, because `ceil(0.5 * 10) - 1` is 4, the index of the 5th.*
4. Why does the README now print `sent 90000` for a run of 30 seconds at 3,000 per second, where version 1.0.0 printed `sent 89999`? *Answer: Each due time is computed from the index as `k x 1,000,000,000 / rate`. Version 1.0.0 added up an interval rounded down to 333,333 ns, so the first measured request was due 5,000 ns before time zero and was dropped with the warm-up.*
5. What is the difference between `failedBetween` and `failedAfter`? *Answer: `failedBetween` counts failed requests sent between the victim's last answer and the balancer's removal of it; `failedAfter` counts failures sent after the removal.*

## Further reading

- Java SE 25 API: `java.net.http.HttpClient`, `java.util.concurrent.Semaphore`, `java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor`, `java.util.stream.Collectors`: https://docs.oracle.com/en/java/javase/25/docs/api/
- JEP 444, virtual threads: https://openjdk.org/jeps/444
- JEP 440, record patterns: https://openjdk.org/jeps/440
- Java SE 25 API, `java.util.Formatter` (the format strings): https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Formatter.html
