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

  @Test
  void countsTrafficSharePerInstanceAndPerBalancer() {
    RunReport report =
        new RunReport(
            settings("none"),
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
        new RunReport(settings("seatwave-2"), outcomes, changes, Map.of())
            .killAnalysis()
            .orElseThrow();

    assertThat(analysis.detectionMillis()).contains(6_000L);
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
            settings("seatwave-2"), List.of(answer(2 * SECOND, "seatwave-2")), changes, Map.of());

    assertThat(report.killAnalysis().orElseThrow().isRemoved()).isFalse();
    assertThat(report.render()).contains("no balancer took it out after its last answer");
  }

  @Test
  void hasNoKillAnalysisWithoutAVictim() {
    RunReport report =
        new RunReport(settings("none"), List.of(answer(0, "seatwave-1")), List.of(), Map.of());

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
        new RunReport(settings("none"), outcomes, List.of(), Map.of()).longestGap().orElseThrow();

    assertThat(gap.endNanos() - gap.startNanos()).isEqualTo(4 * SECOND);
    assertThat(gap.failedInside()).isEqualTo(1);
  }

  @Test
  void saysNoFailuresAndAllFailuresDifferently() {
    String none =
        new RunReport(settings("none"), List.of(answer(0, "seatwave-1")), List.of(), Map.of())
            .render();
    String all =
        new RunReport(
                settings("none"),
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
                List.of(answer(0, "seatwave-1")),
                List.of(),
                Map.of("lb1", List.of(before, after)))
            .render();

    assertThat(text)
        .contains(
            "balancer lb1 during the run (backend seatwave): 100 requests passed, 8 retries, 11 redispatches");
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
