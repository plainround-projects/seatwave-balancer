package io.plainround.seatwave.balancer.deploy;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.balancer.haproxy.RuntimeCommand.AdminState;
import io.plainround.seatwave.balancer.haproxy.StatRow;
import io.plainround.seatwave.balancer.haproxy.StatTable;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Restarts the SeatWave instances one at a time without failing a request: for each one, drain it
 * on every balancer, wait until it has no session left, stop it (it then drains itself too), start
 * it, set it ready again, wait until every balancer's checks see it up, and let slow start ramp it
 * before the next one.
 *
 * <p>The state of a balancer lives in that balancer: a drain set on one HAProxy is not seen by the
 * other, so every step is applied to every balancer of the pair. Not thread-safe: one deploy at a
 * time, which is also the rule for the infrastructure it changes.
 */
final class RollingDeploy {

  private static final double NANOS_PER_MILLI = 1_000_000.0;

  private final DeploySettings settings;
  private final List<BalancerControl> balancers;
  private final Containers containers;
  private final PrintStream log;
  private final long startNanos = System.nanoTime();

  RollingDeploy(
      DeploySettings settings,
      List<BalancerControl> balancers,
      Containers containers,
      PrintStream log) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.balancers = List.copyOf(balancers);
    this.containers = Objects.requireNonNull(containers, "containers");
    this.log = Objects.requireNonNull(log, "log");
  }

  /**
   * Checks that every instance is up everywhere, then restarts them one by one.
   *
   * @return the number of instances restarted, or what stopped the deploy; on a failure the
   *     instance in progress is left drained or stopped, never sent traffic it cannot serve
   * @throws InterruptedException if the deploy is interrupted while it waits
   */
  Result<Integer, String> run() throws InterruptedException {
    Optional<String> notUp = firstNotUp();
    if (notUp.isPresent()) {
      return Result.error("check before: " + notUp.get() + "; nothing was changed");
    }
    for (String instance : settings.instances()) {
      Result<Integer, String> step = restart(instance);
      if (step instanceof Result.Error<Integer, String> failed) {
        return failed;
      }
    }
    Optional<String> afterwards = firstNotUp();
    return afterwards.isPresent()
        ? Result.error("check after: " + afterwards.get())
        : Result.success(settings.instances().size());
  }

  private Result<Integer, String> restart(String instance) throws InterruptedException {
    try {
      setEverywhere(instance, AdminState.DRAIN);
      say(instance + ": drain set on " + balancers.size() + " balancer(s)");
      if (!waitFor(instance, row -> row.currentSessions() == 0, settings.drainTimeout())) {
        return Result.error(instance + " still had sessions after " + settings.drainTimeout());
      }
      say(instance + ": no session left on any balancer");
      containers.stop(instance);
      say(instance + ": stopped (SIGTERM; the instance drained itself)");
      // Wait until every balancer's checks have seen it down. Started sooner, a quick restart can
      // go unnoticed: the balancer would then send full traffic at once to a cold instance, with no
      // slow start, possibly before it is ready.
      if (!waitFor(instance, row -> row.status().startsWith("DOWN"), settings.upTimeout())) {
        return Result.error(instance + " was never seen down within " + settings.upTimeout());
      }
      say(instance + ": seen down by every balancer's checks");
      containers.start(instance);
      say(instance + ": started");
      setEverywhere(instance, AdminState.READY);
      say(instance + ": ready set; the checks decide when it gets traffic again");
      if (!waitFor(instance, StatRow::isFullyUp, settings.upTimeout())) {
        return Result.error(instance + " did not pass its checks within " + settings.upTimeout());
      }
      say(instance + ": up on every balancer; slow start ramps its weight");
      Thread.sleep(settings.settle());
      return Result.success(1);
    } catch (IOException failure) {
      return Result.error(instance + ": " + failure.getMessage());
    }
  }

  private void setEverywhere(String instance, AdminState state) throws IOException {
    for (BalancerControl balancer : balancers) {
      for (String backend : settings.backends()) {
        balancer.setState(backend, instance, state);
      }
    }
  }

  private boolean waitFor(String instance, Predicate<StatRow> condition, Duration limit)
      throws IOException, InterruptedException {
    long deadlineNanos = System.nanoTime() + limit.toNanos();
    while (true) {
      if (holdsEverywhere(instance, condition)) {
        return true;
      }
      if (System.nanoTime() > deadlineNanos) {
        return false;
      }
      Thread.sleep(settings.poll());
    }
  }

  private boolean holdsEverywhere(String instance, Predicate<StatRow> condition)
      throws IOException {
    for (BalancerControl balancer : balancers) {
      StatTable table = balancer.stat();
      for (String backend : settings.backends()) {
        Optional<StatRow> row = table.server(backend, instance);
        if (row.isEmpty() || !condition.test(row.get())) {
          return false;
        }
      }
    }
    return true;
  }

  private Optional<String> firstNotUp() {
    for (BalancerControl balancer : balancers) {
      StatTable table;
      try {
        table = balancer.stat();
      } catch (IOException unreachable) {
        return Optional.of(balancer.name() + " does not answer: " + unreachable.getMessage());
      }
      for (String backend : settings.backends()) {
        for (String instance : settings.instances()) {
          Optional<StatRow> row = table.server(backend, instance);
          if (row.isEmpty()) {
            return Optional.of(balancer.name() + " has no server " + backend + "/" + instance);
          }
          // Fully up: "UP 1/3" is an instance already failing its checks, not one to deploy over.
          if (!row.get().isFullyUp()) {
            return Optional.of(
                balancer.name() + " sees " + backend + "/" + instance + " " + row.get().status());
          }
        }
      }
    }
    return Optional.empty();
  }

  private void say(String message) {
    double elapsedMillis = (System.nanoTime() - startNanos) / NANOS_PER_MILLI;
    log.println(String.format(Locale.ROOT, "+%9.1f ms  %s", elapsedMillis, message));
  }
}
