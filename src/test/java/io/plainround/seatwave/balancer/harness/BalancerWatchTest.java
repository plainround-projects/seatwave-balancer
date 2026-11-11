package io.plainround.seatwave.balancer.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.balancer.haproxy.FakeBalancer;
import io.plainround.seatwave.balancer.haproxy.NamedBalancer;
import io.plainround.seatwave.balancer.haproxy.StatRow;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BalancerWatchTest {

  // Longer than the test: the watch's loop reads once, and only stop() can end its wait.
  private static final Duration NEVER_AGAIN = Duration.ofSeconds(60);
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  @Test
  void readsOnceMoreAfterTheLoadEndedSoTheCountersIncludeItsLastRequests() throws Exception {
    AtomicInteger readings = new AtomicInteger();
    // The backend's request counter grows with every reading, as a balancer under load does.
    try (FakeBalancer balancer =
        new FakeBalancer(command ->
            "# pxname,svname,stot,status,\nseatwave,BACKEND,"
                + readings.incrementAndGet()
                + ",UP,\n")) {
      BalancerWatch watch = new BalancerWatch(named(balancer), "seatwave", NEVER_AGAIN, 0);
      Thread thread = Thread.ofPlatform().start(watch);
      await().atMost(TIMEOUT).until(() -> readings.get() == 1);

      watch.stop();
      thread.join(TIMEOUT);

      List<StatRow> rows = watch.backendRows().orElseThrow();
      assertThat(rows.getFirst().totalSessions()).isEqualTo(1);
      assertThat(rows.getLast().totalSessions()).isEqualTo(2);
    }
  }

  private static NamedBalancer named(FakeBalancer balancer) {
    return switch (NamedBalancer.parse(
        List.of("lb1=127.0.0.1:" + balancer.port()), Duration.ofSeconds(2))) {
      case Result.Success<List<NamedBalancer>, String>(List<NamedBalancer> parsed) ->
          parsed.getFirst();
      case Result.Error<List<NamedBalancer>, String>(String reason) ->
          throw new AssertionError(reason);
    };
  }
}
