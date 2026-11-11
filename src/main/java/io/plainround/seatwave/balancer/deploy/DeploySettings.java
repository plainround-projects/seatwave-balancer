package io.plainround.seatwave.balancer.deploy;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.balancer.config.SettingsReader;
import io.plainround.seatwave.balancer.haproxy.NamedBalancer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A rolling deploy's target and limits, read from a settings file with no defaults.
 *
 * @param project the Compose project, the only one the deploy may touch
 * @param instances the Compose services to restart, one at a time, in order
 * @param backends the HAProxy backends that hold their own copy of each server (backends that only
 *     track another are left alone: a state set on the tracked server reaches them)
 * @param balancers every balancer, with its runtime API on this machine
 * @param drainTimeout the longest wait for a drained server's sessions to reach zero
 * @param stopTimeout what {@code docker compose stop -t} gives the instance before SIGKILL; longer
 *     than the instance's own drain deadline
 * @param upTimeout the longest wait for the balancers' checks to see a change of the instance: down
 *     after it was stopped, then up after it was started (both waits are paced by the same checks)
 * @param settle the wait after an instance is back, so slow start has ramped it before the next
 * @param poll how often the balancers are read while waiting
 * @param auditFile where one line per change is appended
 */
public record DeploySettings(
    String project,
    List<String> instances,
    List<String> backends,
    List<NamedBalancer> balancers,
    Duration drainTimeout,
    Duration stopTimeout,
    Duration upTimeout,
    Duration settle,
    Duration poll,
    Path auditFile) {

  private static final Pattern PROJECT = Pattern.compile("seatwave-balancer");
  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_-]{0,63}");
  private static final Pattern ENTRY = Pattern.compile("[a-z0-9=.:-]{1,64}");
  private static final int MAX_WAIT_MILLIS = 600_000;
  private static final int MIN_POLL_MILLIS = 10;
  private static final int RUNTIME_API_TIMEOUT_MILLIS = 2_000;

  /** Copies the lists and checks the fields. */
  public DeploySettings {
    Objects.requireNonNull(project, "project");
    instances = List.copyOf(instances);
    backends = List.copyOf(backends);
    balancers = List.copyOf(balancers);
    Objects.requireNonNull(drainTimeout, "drainTimeout");
    Objects.requireNonNull(stopTimeout, "stopTimeout");
    Objects.requireNonNull(upTimeout, "upTimeout");
    Objects.requireNonNull(settle, "settle");
    Objects.requireNonNull(poll, "poll");
    Objects.requireNonNull(auditFile, "auditFile");
  }

  /**
   * Reads the settings, reporting every missing or invalid key together.
   *
   * @param reader the settings reader
   * @return the settings, or one line per problem
   */
  public static Result<DeploySettings, List<String>> from(SettingsReader reader) {
    Objects.requireNonNull(reader, "reader");
    String project = reader.textMatching("compose.project", PROJECT);
    List<String> instances = reader.listMatching("deploy.instances", NAME);
    List<String> backends = reader.listMatching("deploy.backends", NAME);
    List<String> entries = reader.listMatching("deploy.balancers", ENTRY);
    Duration drain = reader.millisBetween("deploy.drainTimeoutMillis", 1, MAX_WAIT_MILLIS);
    int problemsBeforeStop = reader.problems().size();
    Duration stop = reader.millisBetween("deploy.stopTimeoutMillis", 1, MAX_WAIT_MILLIS);
    // The whole-seconds rule is checked only on a value that was read; a missing or invalid key
    // already has its own problem line.
    boolean stopRead = reader.problems().size() == problemsBeforeStop;
    Duration up = reader.millisBetween("deploy.upTimeoutMillis", 1, MAX_WAIT_MILLIS);
    Duration settle = reader.millisBetween("deploy.settleMillis", 0, MAX_WAIT_MILLIS);
    Duration poll = reader.millisBetween("deploy.pollMillis", MIN_POLL_MILLIS, MAX_WAIT_MILLIS);
    String audit = reader.filePath("deploy.auditFile");
    if (stopRead && stop.toSeconds() < 1) {
      reader.addProblem(
          "deploy.stopTimeoutMillis must be at least 1000: docker counts whole seconds");
    }
    if (instances.size() != instances.stream().distinct().count()) {
      reader.addProblem("deploy.instances names an instance twice");
    }
    Optional<List<NamedBalancer>> balancers = Optional.empty();
    if (!entries.isEmpty()) {
      switch (NamedBalancer.parse(entries, Duration.ofMillis(RUNTIME_API_TIMEOUT_MILLIS))) {
        case Result.Success<List<NamedBalancer>, String>(List<NamedBalancer> parsed) ->
            balancers = Optional.of(parsed);
        case Result.Error<List<NamedBalancer>, String>(String reason) ->
            reader.addProblem("deploy.balancers: " + reason);
      }
    }
    if (!reader.problems().isEmpty() || balancers.isEmpty()) {
      return Result.error(reader.problems());
    }
    return Result.success(
        new DeploySettings(
            project,
            instances,
            backends,
            balancers.get(),
            drain,
            stop,
            up,
            settle,
            poll,
            Path.of(audit)));
  }
}
