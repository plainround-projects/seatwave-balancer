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

  private HttpRequest readRequest(String holdId) {
    return HttpRequest.newBuilder(settings.target().resolve(URI.create("/v1/holds/" + holdId)))
        .timeout(settings.requestTimeout())
        .GET()
        .build();
  }

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

  private static String describe(IOException failed) {
    // The class says what kind of failure it was; the message adds "Connection reset" and the like.
    String message = failed.getMessage() == null ? "" : ": " + failed.getMessage();
    return failed.getClass().getSimpleName() + message;
  }

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
