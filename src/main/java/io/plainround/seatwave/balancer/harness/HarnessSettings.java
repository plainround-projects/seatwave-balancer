package io.plainround.seatwave.balancer.harness;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.balancer.config.SettingsReader;
import io.plainround.seatwave.balancer.haproxy.NamedBalancer;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One load run and what to watch during it, read from a settings file with no defaults.
 *
 * @param label the run's name, printed first and used in every Idempotency-Key it sends
 * @param target the balancer address requests go to, such as {@code http://10.210.10.21:8080}
 * @param ratePerSecond requests started per second, whatever the answers do (an open loop)
 * @param warmup how long requests are started before time zero; they warm the JIT compilers and
 *     open the connections, and the report leaves them out
 * @param duration how long requests are started after time zero, the measured part
 * @param holdPercent the share of requests that are {@code POST /v1/holds}; the rest read a hold
 * @param maxInFlight requests allowed in flight at once; a request due beyond it is counted as not
 *     sent, so a stuck target shows up instead of slowing the load down
 * @param connectTimeout the client's connect timeout
 * @param requestTimeout the client's whole-request timeout, a fan's app giving up
 * @param seed the start of the seat sequence, so two runs ask for the same seats
 * @param balancers the balancers whose runtime API is read during the run
 * @param backend the backend whose servers are watched
 * @param watchInterval how often the balancers are read
 * @param victimName the instance a kill removes, for the report's analysis, or an empty string
 * @param timelineStep the width of one timeline row
 */
public record HarnessSettings(
    String label,
    URI target,
    int ratePerSecond,
    Duration warmup,
    Duration duration,
    int holdPercent,
    int maxInFlight,
    Duration connectTimeout,
    Duration requestTimeout,
    long seed,
    List<NamedBalancer> balancers,
    String backend,
    Duration watchInterval,
    String victimName,
    Duration timelineStep) {

  private static final Pattern LABEL = Pattern.compile("[a-z0-9-]{1,32}");
  // Only plain HTTP to a dotted IPv4 literal of the lab network or this machine, with a port from 1
  // to 65,535 written without a leading zero; the address is checked again once it is parsed.
  private static final Pattern TARGET = Pattern.compile("http://([0-9.]{7,15}):([1-9][0-9]{0,4})");
  private static final int HIGHEST_PORT = 65_535;
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  private static final Pattern ENTRY = Pattern.compile("[a-z0-9=.:-]{1,64}");
  private static final String NO_VICTIM = "none";
  // Module 1's peak was 13,333 holds and 20,000 seat maps per second; the lab stays below that.
  private static final int MAX_RATE = 20_000;
  private static final int MAX_IN_FLIGHT = 10_000;
  private static final int MAX_DURATION_MILLIS = 600_000;
  private static final int MAX_TIMEOUT_MILLIS = 60_000;
  private static final int MIN_WATCH_MILLIS = 10;
  private static final int PERCENT = 100;
  private static final int MAX_SEED = 1_000_000;

  /** Copies the list and checks the fields. */
  public HarnessSettings {
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(warmup, "warmup");
    Objects.requireNonNull(duration, "duration");
    Objects.requireNonNull(connectTimeout, "connectTimeout");
    Objects.requireNonNull(requestTimeout, "requestTimeout");
    balancers = List.copyOf(balancers);
    Objects.requireNonNull(backend, "backend");
    Objects.requireNonNull(watchInterval, "watchInterval");
    Objects.requireNonNull(victimName, "victimName");
    Objects.requireNonNull(timelineStep, "timelineStep");
  }

  /**
   * Reads the settings, reporting every missing or invalid key together.
   *
   * @param reader the settings reader
   * @return the settings, or one line per problem
   */
  public static Result<HarnessSettings, List<String>> from(SettingsReader reader) {
    Objects.requireNonNull(reader, "reader");
    String label = reader.textMatching("run.label", LABEL);
    String target = reader.textMatching("target.url", TARGET);
    int rate = reader.intBetween("load.ratePerSecond", 1, MAX_RATE);
    Duration warmup = reader.millisBetween("load.warmupMillis", 0, MAX_DURATION_MILLIS);
    Duration duration = reader.millisBetween("load.durationMillis", 1, MAX_DURATION_MILLIS);
    int holdPercent = reader.intBetween("load.holdPercent", 0, PERCENT);
    int maxInFlight = reader.intBetween("load.maxInFlight", 1, MAX_IN_FLIGHT);
    int problemsBeforeTimeouts = reader.problems().size();
    Duration connect = reader.millisBetween("load.connectTimeoutMillis", 1, MAX_TIMEOUT_MILLIS);
    Duration request = reader.millisBetween("load.requestTimeoutMillis", 1, MAX_TIMEOUT_MILLIS);
    // The rule between the two keys is checked only when both were read, so a missing key gives
    // one problem line, not a second one about the stand-in values.
    boolean timeoutsRead = reader.problems().size() == problemsBeforeTimeouts;
    int seed = reader.intBetween("load.seed", 0, MAX_SEED);
    List<String> entries = reader.listMatching("watch.balancers", ENTRY);
    String backend = reader.textMatching("watch.backend", NAME);
    Duration watch =
        reader.millisBetween("watch.intervalMillis", MIN_WATCH_MILLIS, MAX_TIMEOUT_MILLIS);
    String victim = reader.textMatching("report.victim", NAME);
    Duration step = reader.millisBetween("report.timelineMillis", 1, MAX_DURATION_MILLIS);
    if (!target.isEmpty() && !isExactTarget(target)) {
      reader.addProblem(
          "target.url must be http:// with a dotted IPv4 address and a port from 1 to 65535, such"
              + " as http://10.210.10.21:8080, but was "
              + target);
    }
    if (timeoutsRead && request.compareTo(connect) <= 0) {
      reader.addProblem(
          "load.requestTimeoutMillis must be longer than load.connectTimeoutMillis, or no request"
              + " that needs a new connection can finish");
    }
    Optional<List<NamedBalancer>> balancers = Optional.empty();
    if (!entries.isEmpty()) {
      switch (NamedBalancer.parse(entries, connect)) {
        case Result.Success<List<NamedBalancer>, String>(List<NamedBalancer> parsed) ->
            balancers = Optional.of(parsed);
        case Result.Error<List<NamedBalancer>, String>(String reason) ->
            reader.addProblem("watch.balancers: " + reason);
      }
    }
    if (!reader.problems().isEmpty() || balancers.isEmpty()) {
      return Result.error(reader.problems());
    }
    return Result.success(
        new HarnessSettings(
            label,
            URI.create(target),
            rate,
            warmup,
            duration,
            holdPercent,
            maxInFlight,
            connect,
            request,
            seed,
            balancers.get(),
            backend,
            watch,
            victim.equals(NO_VICTIM) ? "" : victim,
            step));
  }

  private static boolean isExactTarget(String target) {
    Matcher parts = TARGET.matcher(target);
    if (!parts.matches() || Integer.parseInt(parts.group(2)) > HIGHEST_PORT) {
      return false;
    }
    // ofLiteral also reads short forms such as 127.1; only the form that prints back the same is
    // the address the reader meant.
    try {
      return InetAddress.ofLiteral(parts.group(1)).getHostAddress().equals(parts.group(1));
    } catch (IllegalArgumentException notAnAddress) {
      return false;
    }
  }

  /**
   * Returns the instance the report analyses as removed.
   *
   * @return the name, or empty when the settings say {@code none}
   */
  public Optional<String> victim() {
    return victimName.isEmpty() ? Optional.empty() : Optional.of(victimName);
  }

  /**
   * Returns the number of requests the run starts, warm-up included.
   *
   * @return rate times warm-up plus duration, rounded down
   */
  public long plannedRequests() {
    return Math.multiplyExact((long) ratePerSecond, warmup.plus(duration).toMillis())
        / Duration.ofSeconds(1).toMillis();
  }
}
