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

  @Test
  void stopsAtOnceWhenNothingIsInFlight() throws InterruptedException {
    Lifecycle lifecycle = new Lifecycle();
    lifecycle.markReady();

    Lifecycle.DrainResult result = lifecycle.awaitDrained(LONG_WAIT);

    assertThat(result.isClean()).isTrue();
    assertThat(lifecycle.state()).isEqualTo(LifecycleState.STOPPED);
  }

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

  @Test
  void rejectsANegativeDeadline() {
    Lifecycle lifecycle = new Lifecycle();

    assertThatThrownBy(() -> lifecycle.awaitDrained(Duration.ofMillis(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be negative");
  }

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
