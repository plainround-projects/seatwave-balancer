package io.plainround.seatwave.balancer.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.balancer.config.SettingsReader;
import io.plainround.seatwave.balancer.haproxy.StatRow;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RunReportTest {

  private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
  private static final long MILLI = TimeUnit.MILLISECONDS.toNanos(1);
  private static final LoadRun.Mix NO_MIX = new LoadRun.Mix(0, 0, 0, 0);

  @Test
  void countsTrafficSharePerInstanceAndPerBalancer() {
    RunReport report =
        new RunReport(
            settings("none"),
            NO_MIX,
            List.of(answer(0, "seatwave-1"), answer(1, "seatwave-2"), answer(2, "seatwave-2")),
            List.of(),
            Map.of());

    assertThat(report.answersByInstance())
        .containsExactly(Map.entry("seatwave-1", 1L), Map.entry("seatwave-2", 2L));
    assertThat(report.answersByBalancer()).containsExactly(Map.entry("lb1", 3L));
    assertThat(report.render()).contains("seatwave-2 66.7% (2)");
  }

  @Test
  void measuresDetectionFromTheVictimsLastAnswerToItsRemoval() {
    List<Outcome> outcomes =
        List.of(
            answer(1 * SECOND, "seatwave-1"),
            answer(2 * SECOND, "seatwave-2"),
            Outcome.failure(3 * SECOND, 4 * SECOND, "HTTP 503"),
            Outcome.failure(9 * SECOND, 9 * SECOND + MILLI, "HTTP 503"));
    List<StateChange> changes =
        List.of(new StateChange(8 * SECOND, "lb1", "seatwave-2", "UP 1/3", "DOWN"));

    RunReport.KillAnalysis analysis =
        new RunReport(settings("seatwave-2"), NO_MIX, outcomes, changes, Map.of())
            .killAnalysis()
            .orElseThrow();

    assertThat(analysis.removedNanos() - analysis.lastAnswerNanos()).isEqualTo(6 * SECOND);
    assertThat(analysis.failedBetween()).isEqualTo(1);
    assertThat(analysis.failedAfter()).isEqualTo(1);
    assertThat(analysis.removedBy()).isEqualTo("lb1");
    assertThat(analysis.victimShare()).isEqualTo(0.5);
  }

  @Test
  void ignoresARemovalBeforeTheLastAnswerAndSaysSo() {
    List<StateChange> changes =
        List.of(new StateChange(SECOND, "lb1", "seatwave-2", "UP", "DRAIN"));
    RunReport report =
        new RunReport(
            settings("seatwave-2"),
            NO_MIX,
            List.of(answer(2 * SECOND, "seatwave-2")),
            changes,
            Map.of());

    assertThat(report.killAnalysis().orElseThrow().isRemoved()).isFalse();
    assertThat(report.render()).contains("no balancer took it out after its last answer");
  }

  @Test
  void hasNoKillAnalysisWithoutAVictim() {
    RunReport report =
        new RunReport(
            settings("none"), NO_MIX, List.of(answer(0, "seatwave-1")), List.of(), Map.of());

    assertThat(report.killAnalysis()).isEmpty();
  }

  @Test
  void findsTheLongestGapBetweenAnswersAndTheFailuresInsideIt() {
    List<Outcome> outcomes =
        List.of(
            answer(1 * SECOND, "seatwave-1"),
            answer(2 * SECOND, "seatwave-1"),
            Outcome.failure(3 * SECOND, 4 * SECOND, "HttpConnectTimeoutException"),
            answer(6 * SECOND, "seatwave-1"));

    RunReport.Gap gap =
        new RunReport(settings("none"), NO_MIX, outcomes, List.of(), Map.of())
            .longestGap()
            .orElseThrow();

    assertThat(gap.endNanos() - gap.startNanos()).isEqualTo(4 * SECOND);
    assertThat(gap.failedInside()).isEqualTo(1);
  }

  @Test
  void saysNoFailuresAndAllFailuresDifferently() {
    String none =
        new RunReport(
                settings("none"), NO_MIX, List.of(answer(0, "seatwave-1")), List.of(), Map.of())
            .render();
    String all =
        new RunReport(
                settings("none"),
                NO_MIX,
                List.of(Outcome.failure(0, MILLI, "HTTP 502")),
                List.of(),
                Map.of())
            .render();

    assertThat(none).contains("failures: none", "failed 0 (0.000%)");
    assertThat(all)
        .contains("failures: HTTP 502 1", "failed 1 (100.000%)", "share by instance: no answers");
  }

  @Test
  void leavesTheWarmUpOutOfEveryNumber() {
    RunReport report =
        new RunReport(
            settings("none"),
            NO_MIX,
            List.of(Outcome.failure(-SECOND, -SECOND + MILLI, "HTTP 503"), answer(0, "seatwave-1")),
            List.of(),
            Map.of());

    assertThat(report.render()).contains("sent 1, answered 1, failed 0");
  }

  @Test
  void showsTheBalancersOwnRetriesDuringTheRun() {
    StatRow before =
        new StatRow(
            Map.of(
                "pxname",
                "seatwave",
                "svname",
                "BACKEND",
                "stot",
                "10",
                "wretr",
                "1",
                "wredis",
                "0"));
    StatRow after =
        new StatRow(
            Map.of(
                "pxname",
                "seatwave",
                "svname",
                "BACKEND",
                "stot",
                "110",
                "wretr",
                "9",
                "wredis",
                "11"));

    String text =
        new RunReport(
                settings("none"),
                NO_MIX,
                List.of(answer(0, "seatwave-1")),
                List.of(),
                Map.of("lb1", List.of(before, after)))
            .render();

    assertThat(text)
        .contains(
            "balancer lb1 during the run (backend seatwave): 100 requests passed, 8 retries, 11 redispatches");
  }

  @Test
  void printsALittlesLawLineWhoseFactorsMultiplyOut() {
    // One answer in three from the victim, then 100 s until the removal: the unrounded factors
    // would give 33,333, which the printed 0.3333 and 100.000 do not multiply to.
    List<Outcome> outcomes =
        List.of(
            answer(SECOND, "seatwave-1"),
            answer(SECOND + MILLI, "seatwave-3"),
            answer(SECOND + 2 * MILLI, "seatwave-2"));
    long lastNanos = SECOND + 2 * MILLI;
    List<StateChange> changes =
        List.of(new StateChange(lastNanos + 100 * SECOND, "lb1", "seatwave-2", "UP 1/3", "DOWN"));

    String text = report("seatwave-2", outcomes, changes).render();

    assertThat(text)
        .contains("taken out by lb1 at +101.002 s: 100000.0 ms after its last answer")
        .contains("1000 requests/s x 0.3333 share x 100.000 s = 33330");
  }

  @Test
  void countsAFailureSentAtTheMomentOfRemovalAsSentInBetween() {
    List<Outcome> outcomes =
        List.of(
            answer(2 * SECOND, "seatwave-2"),
            Outcome.failure(5 * SECOND, 5 * SECOND + MILLI, "HTTP 503"),
            Outcome.failure(5 * SECOND + 1, 5 * SECOND + MILLI, "HTTP 503"));
    List<StateChange> changes =
        List.of(new StateChange(5 * SECOND, "lb1", "seatwave-2", "UP 1/3", "DOWN"));

    RunReport.KillAnalysis analysis =
        report("seatwave-2", outcomes, changes).killAnalysis().orElseThrow();

    assertThat(analysis.failedBetween()).isEqualTo(1);
    assertThat(analysis.failedAfter()).isEqualTo(1);
  }

  @Test
  void takesOnlyAChangeIntoTheOutStatesAsARemoval() {
    // DRAIN to DOWN is a change between two out-of-rotation states: the instance was already out.
    List<StateChange> changes =
        List.of(
            new StateChange(SECOND, "lb1", "seatwave-2", "UP", "DRAIN"),
            new StateChange(5 * SECOND, "lb1", "seatwave-2", "DRAIN", "DOWN"));

    RunReport.KillAnalysis analysis =
        report("seatwave-2", List.of(answer(2 * SECOND, "seatwave-2")), changes)
            .killAnalysis()
            .orElseThrow();

    assertThat(analysis.isRemoved()).isFalse();
  }

  @Test
  void readsTheMedianAndTheP99ByNearestRank() {
    // Latencies of 1, 2 and 3 ms: the nearest rank of 50% is the 2nd value, of 99% the 3rd.
    List<Outcome> outcomes =
        List.of(
            Outcome.response(0, MILLI, 200, "seatwave-1", "lb1"),
            Outcome.response(0, 2 * MILLI, 200, "seatwave-1", "lb1"),
            Outcome.response(0, 3 * MILLI, 200, "seatwave-1", "lb1"));

    String text = report("none", outcomes, List.of()).render();

    assertThat(text).contains("latency of answered requests: p50 2.0 ms, p99 3.0 ms, max 3.0 ms");
  }

  @Test
  void printsTheMixThatReallyRan() {
    RunReport report =
        new RunReport(
            settings("none"),
            new LoadRun.Mix(45, 55, 3, 2),
            List.of(answer(0, "seatwave-1")),
            List.of(),
            Map.of());

    assertThat(report.render())
        .contains(
            "mix: 45 holds (POST), 55 reads (GET), of which 3 of a hold not made yet; 2 hold"
                + " replies the harness could not read");
  }

  @Test
  void countsEveryStatusBelowFiveHundredAsAnAnswerAndFromFiveHundredAsAFailure() {
    assertThat(Outcome.response(0, MILLI, 409, "seatwave-1", "lb1").kind())
        .isEqualTo(Outcome.Kind.ANSWERED);
    assertThat(Outcome.response(0, MILLI, 499, "seatwave-1", "lb1").kind())
        .isEqualTo(Outcome.Kind.ANSWERED);
    assertThat(Outcome.response(0, MILLI, 500, "", "lb1").kind()).isEqualTo(Outcome.Kind.FAILED);
    assertThat(Outcome.response(0, MILLI, 503, "", "lb1").failure()).isEqualTo("HTTP 503");
  }

  private static RunReport report(
      String victim, List<Outcome> outcomes, List<StateChange> changes) {
    return new RunReport(settings(victim), NO_MIX, outcomes, changes, Map.of());
  }

  private static Outcome answer(long doneNanos, String instance) {
    return Outcome.response(Math.max(0, doneNanos - MILLI), doneNanos, 201, instance, "lb1");
  }

  static HarnessSettings settings(String victim) {
    Properties properties = new Properties();
    properties.setProperty("run.label", "test");
    properties.setProperty("target.url", "http://127.0.0.1:21000");
    properties.setProperty("load.ratePerSecond", "1000");
    properties.setProperty("load.warmupMillis", "0");
    properties.setProperty("load.durationMillis", "10000");
    properties.setProperty("load.holdPercent", "50");
    properties.setProperty("load.maxInFlight", "100");
    properties.setProperty("load.connectTimeoutMillis", "1000");
    properties.setProperty("load.requestTimeoutMillis", "5000");
    properties.setProperty("load.seed", "1");
    properties.setProperty("watch.balancers", "lb1=127.0.0.1:21005");
    properties.setProperty("watch.backend", "seatwave");
    properties.setProperty("watch.intervalMillis", "100");
    properties.setProperty("report.victim", victim);
    properties.setProperty("report.timelineMillis", "1000");
    return switch (HarnessSettings.from(new SettingsReader(properties))) {
      case Result.Success<HarnessSettings, List<String>>(HarnessSettings checked) -> checked;
      case Result.Error<HarnessSettings, List<String>>(List<String> problems) ->
          throw new AssertionError(problems.toString());
    };
  }
}
