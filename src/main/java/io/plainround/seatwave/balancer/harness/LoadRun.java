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

/**
 * Starts requests at a fixed rate whatever the answers do (an open loop), so a slow or dead target
 * shows up as failures and latency instead of quietly lowering the load (Module 1's load tool, and
 * the "coordinated omission" trap it avoids).
 *
 * <p>Each request runs on its own virtual thread with the JDK's HTTP client, shared by all of them.
 * The mix is {@code POST /v1/holds} with a unique Idempotency-Key, and {@code GET /v1/holds/{id}}
 * of a hold made earlier in the run, which any instance must be able to answer because holds live
 * in the shared database.
 *
 * <p>The one retry in this harness is the JDK client's own, and it is counted as what a fan's app
 * would do: when a reused connection turns out closed before any byte of the answer, the client
 * sends a {@code GET} once more on a new connection, and never a {@code POST} (the JDK's {@code
 * MultiExchange} retries only GET and HEAD unless {@code jdk.httpclient.enableAllMethodRetry} is
 * set, which this harness does not set). Not thread-safe: one run per instance, started once.
 */
final class LoadRun {

  // Module 1's arena: 20 sections of 1,000 seats.
  private static final int SEATS = 20_000;
  private static final int SEATS_PER_SECTION = 1_000;
  private static final String SECTIONS = "ABCDEFGHIJKLMNOPQRST";
  // A prime step walks every seat once before repeating, so early holds rarely collide.
  private static final long SEAT_STEP = 7_919;
  // Module 1's 200,000 fans.
  private static final int FANS = 200_000;
  private static final int PERCENT = 100;
  // Recent holds the reads choose from; a power of two so the index is a cheap remainder.
  private static final int RECENT_HOLDS = 1_024;
  private static final int CREATED = 201;
  private static final long NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);

  private final HarnessSettings settings;
  private final HttpClient client;
  private final JsonCodec json = new JsonCodec();
  private final AtomicReferenceArray<String> recentHolds = new AtomicReferenceArray<>(RECENT_HOLDS);
  private final AtomicLong holdsMade = new AtomicLong();
  private final long startNanos;
  private final String keyPrefix;

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

  /**
   * Runs the load and waits for every request to finish or time out.
   *
   * @return one outcome per planned request, in no particular order
   * @throws InterruptedException if the run is interrupted
   */
  List<Outcome> run() throws InterruptedException {
    Queue<Outcome> outcomes = new ConcurrentLinkedQueue<>();
    Semaphore slots = new Semaphore(settings.maxInFlight());
    long intervalNanos = NANOS_PER_SECOND / settings.ratePerSecond();
    long planned = settings.plannedRequests();
    long firstNanos = startNanos - settings.warmup().toNanos();
    try (ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor()) {
      for (long requestIndex = 0; requestIndex < planned; requestIndex++) {
        long dueNanos = firstNanos + requestIndex * intervalNanos;
        long waitNanos = dueNanos - System.nanoTime();
        if (waitNanos > 0) {
          LockSupport.parkNanos(waitNanos);
        }
        if (Thread.interrupted()) {
          throw new InterruptedException("the load run was interrupted");
        }
        if (!slots.tryAcquire()) {
          outcomes.add(Outcome.notSent(dueNanos - startNanos));
          continue;
        }
        long index = requestIndex;
        long dueAt = dueNanos - startNanos;
        senders.submit(() -> {
          try {
            outcomes.add(send(index, dueAt));
          } finally {
            slots.release();
          }
        });
      }
    }
    return new ArrayList<>(outcomes);
  }

  private Outcome send(long requestIndex, long dueNanos) {
    Optional<String> holdToRead = recentHold(requestIndex);
    boolean isHold =
        Math.floorMod(requestIndex, (long) PERCENT) < settings.holdPercent()
            || holdToRead.isEmpty();
    HttpRequest request = isHold ? holdRequest(requestIndex) : readRequest(holdToRead.get());
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

  private Optional<String> recentHold(long requestIndex) {
    long made = holdsMade.get();
    if (made == 0) {
      return Optional.empty();
    }
    // One of the last holds made, chosen by the request's index: reads follow the writes closely,
    // as a fan's page reads the hold it just made.
    long back = Math.floorMod(requestIndex, Math.min(made, (long) RECENT_HOLDS));
    // Empty for a moment between counting a hold and storing it; the request is then a hold.
    return Optional.ofNullable(
        recentHolds.get((int) Math.floorMod(made - 1 - back, (long) RECENT_HOLDS)));
  }

  private HttpRequest holdRequest(long requestIndex) {
    long seatNumber = Math.floorMod(settings.seed() + requestIndex * SEAT_STEP, (long) SEATS);
    String seat =
        SECTIONS.charAt((int) (seatNumber / SEATS_PER_SECTION))
            + "-"
            + (seatNumber % SEATS_PER_SECTION + 1);
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

  private HttpRequest readRequest(String holdId) {
    return HttpRequest.newBuilder(settings.target().resolve(URI.create("/v1/holds/" + holdId)))
        .timeout(settings.requestTimeout())
        .GET()
        .build();
  }

  private void remember(String body) {
    if (json.read(body, HoldReply.class)
        instanceof Result.Success<HoldReply, JsonCodec.BindingProblem>(HoldReply hold)) {
      long slot = holdsMade.getAndIncrement();
      recentHolds.set((int) Math.floorMod(slot, (long) RECENT_HOLDS), hold.holdId());
    }
  }

  private static String describe(IOException failed) {
    // The class says what kind of failure it was; the message adds "Connection reset" and the like.
    String message = failed.getMessage() == null ? "" : ": " + failed.getMessage();
    return failed.getClass().getSimpleName() + message;
  }

  /** The body of a granted hold, Module 7's shape. */
  record HoldReply(String holdId, long eventId, String seat, String state, String expiresAt) {}
}
