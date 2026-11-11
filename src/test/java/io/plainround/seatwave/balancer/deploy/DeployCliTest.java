package io.plainround.seatwave.balancer.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.balancer.haproxy.FakeBalancer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeployCliTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-11T09:00:00Z"), ZoneOffset.UTC);
  private static final String HEADER = "# pxname,svname,scur,status,\n";

  @TempDir Path folder;

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();
  private final List<String> containerCalls = new CopyOnWriteArrayList<>();
  // What the fake balancer reports for seatwave-1; the fake containers change it.
  private String[] containerStatus = {"UP"};

  @Test
  void printsThePlanAndChangesNothingByDefault() throws IOException {
    try (FakeBalancer balancer = new FakeBalancer(command -> HEADER)) {
      int status = run(settings(balancer), "");

      assertThat(status).isZero();
      assertThat(text(out)).contains("plan: restart [seatwave-1]", "dry run: nothing changed");
      assertThat(balancer.commands()).isEmpty();
      assertThat(containerCalls).isEmpty();
    }
  }

  @Test
  void changesNothingWithoutAYes() throws IOException {
    try (FakeBalancer balancer = new FakeBalancer(command -> HEADER)) {
      int status = run(settings(balancer), "n\n", "--run");

      assertThat(status).isEqualTo(DeployCli.EXIT_FAILED);
      assertThat(text(err)).contains("not confirmed; nothing changed");
      assertThat(balancer.commands()).isEmpty();
    }
  }

  @Test
  void deploysAfterConfirmationAndWritesTheAudit() throws IOException {
    String[] status = {"UP"};
    try (FakeBalancer balancer =
        new FakeBalancer(command -> {
          if (command.startsWith("set server")) {
            return "\n";
          }
          return HEADER + "seatwave,seatwave-1,0," + status[0] + ",\n";
        })) {
      containerStatus = status;

      int code = run(settings(balancer), "y\n", "--run");

      assertThat(code).isZero();
      assertThat(containerCalls).containsExactly("stop seatwave-1", "start seatwave-1");
      assertThat(Files.readString(folder.resolve("audit.log")))
          .contains("2026-11-11T09:00:00Z deploy: started on [seatwave-1]")
          .contains("deploy: finished, 1 instances restarted");
    }
  }

  @Test
  void refusesBadUsage() {
    assertThat(run(Path.of("x"), "", "--now")).isEqualTo(DeployCli.EXIT_BAD_USAGE);
    assertThat(text(err)).contains("usage: deploy");
  }

  private int run(Path settings, String input, String... flags) {
    String[] arguments = new String[flags.length + 1];
    arguments[0] = settings.toString();
    System.arraycopy(flags, 0, arguments, 1, flags.length);
    return DeployCli.run(
        arguments,
        new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
        new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8),
        CLOCK,
        (project, stopTimeout) ->
            new Containers() {
              @Override
              public void stop(String service) {
                containerCalls.add("stop " + service);
                containerStatus[0] = "DOWN";
              }

              @Override
              public void start(String service) {
                containerCalls.add("start " + service);
                containerStatus[0] = "UP";
              }
            });
  }

  private Path settings(FakeBalancer balancer) throws IOException {
    return Files.writeString(
        folder.resolve("deploy.properties"),
        String.join(
            "\n",
            "compose.project=seatwave-balancer",
            "deploy.instances=seatwave-1",
            "deploy.backends=seatwave",
            "deploy.balancers=lb1=127.0.0.1:" + balancer.port(),
            "deploy.drainTimeoutMillis=1000",
            "deploy.stopTimeoutMillis=1000",
            "deploy.upTimeoutMillis=1000",
            "deploy.settleMillis=0",
            "deploy.pollMillis=10",
            "deploy.auditFile=" + folder.resolve("audit.log"),
            ""),
        StandardCharsets.UTF_8);
  }

  private static String text(ByteArrayOutputStream stream) {
    return stream.toString(StandardCharsets.UTF_8);
  }
}
