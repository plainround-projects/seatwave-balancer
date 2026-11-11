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

      assertThat(drained.get(LONG_WAIT.toSeconds(), TimeUnit.SECONDS).inFlightLeft()).isZero();
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
}
