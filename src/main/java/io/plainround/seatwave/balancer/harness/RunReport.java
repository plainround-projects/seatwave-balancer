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

  private void share(List<String> lines) {
    lines.add("share by instance: " + percentages(answersByInstance()));
    lines.add("share by balancer: " + percentages(answersByBalancer()));
  }

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

  private static double p99Millis(List<Outcome> selected) {
    List<Long> latencies =
        selected.stream()
            .filter(outcome -> outcome.kind() == Outcome.Kind.ANSWERED)
            .map(Outcome::latencyNanos)
            .sorted()
            .toList();
    return latencies.isEmpty() ? 0 : rank(latencies, P99) / NANOS_PER_MILLI;
  }

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
