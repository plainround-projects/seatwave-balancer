package io.plainround.seatwave.balancer.harness;

import io.plainround.seatwave.balancer.haproxy.NamedBalancer;
import io.plainround.seatwave.balancer.haproxy.StatRow;
import io.plainround.seatwave.balancer.haproxy.StatTable;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Reads one balancer's {@code show stat} at a fixed interval during a run and records each change
 * of a watched server's status, with the time on the run's clock. It also keeps the first and the
 * last table it read, so the report can show how many retries the balancer made during the run. One
 * watch per balancer, each on its own thread, so a dead balancer's timeouts never delay the reading
 * of the live one.
 *
 * <p>Thread-safe: {@link #run} is the only writer and runs on one thread; the getters may be called
 * only after {@link #stop} has returned, which waits for that thread.
 */
final class BalancerWatch implements Runnable {

  private static final String UNREACHABLE = "UNREACHABLE";
  private static final String ANY_SERVER = "*";

  private final NamedBalancer balancer;
  private final String backend;
  private final Duration interval;
  private final long startNanos;
  private final CountDownLatch stopRequested = new CountDownLatch(1);
  private final CountDownLatch finished = new CountDownLatch(1);
  private final Map<String, String> lastStatus = new HashMap<>();
  private final List<StateChange> changes = new ArrayList<>();
  // The first table read, then the latest one: two at most, so the retry counters can be compared.
  private final List<StatTable> firstAndLast = new ArrayList<>();

  BalancerWatch(NamedBalancer balancer, String backend, Duration interval, long startNanos) {
    this.balancer = Objects.requireNonNull(balancer, "balancer");
    this.backend = Objects.requireNonNull(backend, "backend");
    this.interval = Objects.requireNonNull(interval, "interval");
    this.startNanos = startNanos;
  }

  @Override
  public void run() {
    try {
      do {
        readOnce();
      } while (!stopRequested.await(interval.toNanos(), TimeUnit.NANOSECONDS));
      // One last reading after the load ended, so the retry counters include its last requests.
      readOnce();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } finally {
      finished.countDown();
    }
  }

  /**
   * Asks the watch to end and waits for its last reading.
   *
   * @throws InterruptedException if the wait is interrupted
   */
  void stop() throws InterruptedException {
    stopRequested.countDown();
    finished.await();
  }

  /**
   * Returns the status changes in the order they were seen.
   *
   * @return the changes; call only after {@link #stop}
   */
  List<StateChange> changes() {
    return List.copyOf(changes);
  }

  /**
   * Returns the balancer's name.
   *
   * @return the name
   */
  String balancerName() {
    return balancer.name();
  }

  /**
   * Returns the watched backend's summary row as first and last read; call only after {@link
   * #stop}.
   *
   * @return both rows, or empty if the balancer never answered
   */
  Optional<List<StatRow>> backendRows() {
    if (firstAndLast.isEmpty()) {
      return Optional.empty();
    }
    Optional<StatRow> before = firstAndLast.getFirst().backendSummary(backend);
    Optional<StatRow> after = firstAndLast.getLast().backendSummary(backend);
    return before.isPresent() && after.isPresent()
        ? Optional.of(List.of(before.get(), after.get()))
        : Optional.empty();
  }

  private void readOnce() {
    long atNanos = System.nanoTime() - startNanos;
    try {
      StatTable table = balancer.api().showStat();
      if (firstAndLast.size() < 2) {
        firstAndLast.add(table);
      } else {
        firstAndLast.set(1, table);
      }
      record(atNanos, balancer.name(), ANY_SERVER, "ANSWERING");
      for (StatRow row : table.servers(backend)) {
        record(atNanos, balancer.name(), row.server(), row.status());
      }
    } catch (IOException silent) {
      // A balancer that does not answer is exactly what the pair lab kills; it is a status here.
      record(atNanos, balancer.name(), ANY_SERVER, UNREACHABLE);
    }
  }

  private void record(long atNanos, String balancer, String server, String status) {
    String key = balancer + "/" + server;
    String before = lastStatus.put(key, status);
    if (before == null) {
      // The first reading is the starting point, not a change, unless it already says unreachable.
      if (status.equals(UNREACHABLE)) {
        changes.add(new StateChange(atNanos, balancer, server, "NOT READ", status));
      }
      return;
    }
    if (!before.equals(status)) {
      changes.add(new StateChange(atNanos, balancer, server, before, status));
    }
  }
}
