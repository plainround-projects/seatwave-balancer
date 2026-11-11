package io.plainround.seatwave.balancer.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.balancer.haproxy.NamedBalancer;
import io.plainround.seatwave.balancer.haproxy.RuntimeCommand.AdminState;
import io.plainround.seatwave.balancer.haproxy.StatRow;
import io.plainround.seatwave.balancer.haproxy.StatTable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class RollingDeployTest {

  private static final List<String> INSTANCES = List.of("seatwave-1", "seatwave-2");
  private static final List<String> BACKENDS = List.of("seatwave", "seatwave_active_only");

  private final List<String> calls = new CopyOnWriteArrayList<>();
  private final World world = new World();

  @Test
  void restartsEachInstanceInOrderOnEveryBalancer() throws InterruptedException {
    Result<Integer, String> result = deploy(List.of(world.balancer("lb1"), world.balancer("lb2")));

    assertThat(result).isEqualTo(Result.success(2));
    assertThat(calls)
        .containsSubsequence(
            "lb1 seatwave/seatwave-1 DRAIN",
            "lb2 seatwave_active_only/seatwave-1 DRAIN",
            "stop seatwave-1",
            "start seatwave-1",
            "lb1 seatwave/seatwave-1 READY",
            "lb2 seatwave_active_only/seatwave-1 READY",
            "lb1 seatwave/seatwave-2 DRAIN",
            "stop seatwave-2");
  }

  @Test
  void startsAnInstanceOnlyAfterEveryBalancerHasSeenItDown() throws InterruptedException {
    // The checks take three readings to notice the stop, as real checks take seconds.
    world.readsUntilSeenDown = 3;

    Result<Integer, String> result = deploy(List.of(world.balancer("lb1"), world.balancer("lb2")));

    assertThat(result).isEqualTo(Result.success(2));
    assertThat(calls).contains("start seatwave-1 while DOWN", "start seatwave-2 while DOWN");
    assertThat(calls).noneMatch(call -> call.startsWith("start") && call.endsWith("while UP"));
  }

  @Test
  void waitsTheSettleTimeBeforeTheNextInstance() throws InterruptedException {
    Duration settle = Duration.ofMillis(300);

    Result<Integer, String> result = deploy(List.of(world.balancer("lb1")), settle);

    assertThat(result).isEqualTo(Result.success(2));
    // A lower bound only: a sleep is never shorter than asked, however busy the machine is.
    assertThat(world.secondDrainNanos - world.firstReadyNanos)
        .isGreaterThanOrEqualTo(settle.toNanos());
  }

  @Test
  void changesNothingWhenAnInstanceIsAlreadyFailingItsChecks() throws InterruptedException {
    world.status.put("seatwave/seatwave-2", "UP 1/3");

    Result<Integer, String> result = deploy(List.of(world.balancer("lb1")));

    assertThat(result)
        .isEqualTo(
            Result.error("check before: lb1 sees seatwave/seatwave-2 UP 1/3; nothing was changed"));
    assertThat(calls).isEmpty();
  }

  @Test
  void changesNothingWhenAnInstanceIsNotUpBeforehand() throws InterruptedException {
    world.status.put("seatwave/seatwave-2", "DOWN");

    Result<Integer, String> result = deploy(List.of(world.balancer("lb1")));

    assertThat(result)
        .isEqualTo(
            Result.error("check before: lb1 sees seatwave/seatwave-2 DOWN; nothing was changed"));
    assertThat(calls).isEmpty();
  }

  @Test
  void stopsWhenTheDrainedInstanceKeepsItsSessions() throws InterruptedException {
    world.sessionsStayOpen = true;

    Result<Integer, String> result = deploy(List.of(world.balancer("lb1")));

    assertThat(result).isInstanceOf(Result.Error.class);
    assertThat(calls).doesNotContain("stop seatwave-1");
  }

  @Test
  void stopsWhenTheBalancerDoesNotAnswer() throws InterruptedException {
    world.isAnswering = false;

    Result<Integer, String> result = deploy(List.of(world.balancer("lb1")));

    assertThat(result)
        .isEqualTo(
            Result.error(
                "check before: lb1 does not answer: the test balancer is off; nothing was changed"));
  }

  @Test
  void stopsWhenTheRestartedInstanceNeverComesUp() throws InterruptedException {
    world.staysDownAfterStart = true;

    Result<Integer, String> result = deploy(List.of(world.balancer("lb1")));

    assertThat(result).isEqualTo(Result.error("seatwave-1 did not pass its checks within PT0.2S"));
  }

  private Result<Integer, String> deploy(List<BalancerControl> balancers)
      throws InterruptedException {
    return deploy(balancers, Duration.ZERO);
  }

  private Result<Integer, String> deploy(List<BalancerControl> balancers, Duration settle)
      throws InterruptedException {
    return new RollingDeploy(
            settings(settle),
            balancers,
            world.containers(),
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
        .run();
  }

  private static DeploySettings settings(Duration settle) {
    return new DeploySettings(
        "seatwave-balancer",
        INSTANCES,
        BACKENDS,
        List.<NamedBalancer>of(),
        Duration.ofMillis(200),
        Duration.ofSeconds(1),
        Duration.ofMillis(200),
        settle,
        Duration.ofMillis(10),
        Path.of("build/unused-audit.log"));
  }

  /** The servers' status as both balancers see it, and the containers that change it. */
  private final class World {
    private final Map<String, String> status = new ConcurrentHashMap<>();
    private volatile boolean sessionsStayOpen;
    private volatile boolean isAnswering = true;
    private volatile boolean staysDownAfterStart;
    // Readings of the balancers after a stop before they show the instance down; 0 is at once.
    private volatile int readsUntilSeenDown;
    private final Map<String, Integer> readsLeft = new ConcurrentHashMap<>();
    private volatile long firstReadyNanos;
    private volatile long secondDrainNanos;

    World() {
      for (String backend : BACKENDS) {
        for (String instance : INSTANCES) {
          status.put(backend + "/" + instance, "UP");
        }
      }
    }

    BalancerControl balancer(String name) {
      return new BalancerControl() {
        @Override
        public String name() {
          return name;
        }

        @Override
        public StatTable stat() throws IOException {
          if (!isAnswering) {
            throw new IOException("the test balancer is off");
          }
          readsLeft.replaceAll((service, left) -> {
            if (left == 1) {
              BACKENDS.forEach(backend -> status.put(backend + "/" + service, "DOWN"));
            }
            return Math.max(0, left - 1);
          });
          List<StatRow> rows = new ArrayList<>();
          status.forEach((key, value) -> {
            String[] parts = key.split("/");
            rows.add(
                new StatRow(
                    Map.of(
                        "pxname",
                        parts[0],
                        "svname",
                        parts[1],
                        "status",
                        value,
                        "scur",
                        sessionsStayOpen ? "3" : "0")));
          });
          return new StatTable(rows);
        }

        @Override
        public void setState(String backend, String server, AdminState state) {
          calls.add(name + " " + backend + "/" + server + " " + state);
          if (server.equals("seatwave-1") && state == AdminState.READY && firstReadyNanos == 0) {
            firstReadyNanos = System.nanoTime();
          }
          if (server.equals("seatwave-2") && state == AdminState.DRAIN && secondDrainNanos == 0) {
            secondDrainNanos = System.nanoTime();
          }
        }
      };
    }

    Containers containers() {
      return new Containers() {
        @Override
        public void stop(String service) {
          calls.add("stop " + service);
          if (readsUntilSeenDown == 0) {
            BACKENDS.forEach(backend -> status.put(backend + "/" + service, "DOWN"));
          } else {
            readsLeft.put(service, readsUntilSeenDown);
          }
        }

        @Override
        public void start(String service) {
          calls.add("start " + service);
          calls.add("start " + service + " while " + status.get("seatwave/" + service));
          if (!staysDownAfterStart) {
            BACKENDS.forEach(backend -> status.put(backend + "/" + service, "UP"));
          }
        }
      };
    }
  }
}
