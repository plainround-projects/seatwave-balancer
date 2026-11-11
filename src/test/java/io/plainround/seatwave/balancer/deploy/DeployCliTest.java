package io.plainround.seatwave.balancer.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.balancer.haproxy.FakeBalancer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;
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
  private Runnable onStop = () -> {};

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
  void deploysWithoutAQuestionWhenGivenYes() throws IOException {
    String[] status = {"UP"};
    try (FakeBalancer balancer = deployingBalancer(status)) {
      containerStatus = status;

      int code = run(settings(balancer, folder.resolve("audit.log")), "", "--run", "--yes");

      assertThat(code).isZero();
      assertThat(text(out)).doesNotContain("Go ahead?").contains("deploy finished: 1 instances");
      assertThat(containerCalls).containsExactly("stop seatwave-1", "start seatwave-1");
    }
  }

  @Test
  void saysTheDeployFinishedWhenOnlyTheLastAuditLineFails() throws IOException {
    String[] status = {"UP"};
    Path audit = folder.resolve("audit.log");
    try (FakeBalancer balancer = deployingBalancer(status)) {
      containerStatus = status;
      // The audit file turns read-only after the first line, while the instance restarts.
      onStop = () -> audit.toFile().setReadOnly();

      int code = run(settings(balancer, audit), "", "--run", "--yes");

      assertThat(code).isEqualTo(DeployCli.EXIT_FAILED);
      assertThat(text(out)).contains("deploy finished: 1 instances restarted, all up");
      assertThat(text(err))
          .contains("the audit line of this deploy cannot be written")
          .contains("the deploy itself finished as printed above")
          .doesNotContain("deploy stopped");
    }
  }

  @Test
  void startsNothingWhenTheFirstAuditLineCannotBeWritten() throws IOException {
    String[] status = {"UP"};
    // A folder where the audit file should be: no line can be appended to it.
    Path audit = Files.createDirectories(folder.resolve("audit-folder.log"));
    try (FakeBalancer balancer = deployingBalancer(status)) {
      containerStatus = status;

      int code = run(settings(balancer, audit), "", "--run", "--yes");

      assertThat(code).isEqualTo(DeployCli.EXIT_FAILED);
      assertThat(text(err)).contains("deploy not started", "nothing changed");
      assertThat(containerCalls).isEmpty();
      assertThat(balancer.commands()).isEmpty();
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
                onStop.run();
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
    return settings(balancer, folder.resolve("audit.log"));
  }

  private Path settings(FakeBalancer balancer, Path audit) throws IOException {
    Properties properties = new Properties();
    properties.setProperty("compose.project", "seatwave-balancer");
    properties.setProperty("deploy.instances", "seatwave-1");
    properties.setProperty("deploy.backends", "seatwave");
    properties.setProperty("deploy.balancers", "lb1=127.0.0.1:" + balancer.port());
    properties.setProperty("deploy.drainTimeoutMillis", "1000");
    properties.setProperty("deploy.stopTimeoutMillis", "1000");
    properties.setProperty("deploy.upTimeoutMillis", "1000");
    properties.setProperty("deploy.settleMillis", "0");
    properties.setProperty("deploy.pollMillis", "10");
    // Written by Properties.store, which escapes a backslash, so a Windows path reads back whole.
    properties.setProperty("deploy.auditFile", audit.toString());
    Path file = folder.resolve("deploy.properties");
    try (OutputStream stream = Files.newOutputStream(file)) {
      properties.store(stream, "test");
    }
    return file;
  }

  private static FakeBalancer deployingBalancer(String[] status) throws IOException {
    return new FakeBalancer(command ->
        command.startsWith("set server")
            ? "\n"
            : HEADER + "seatwave,seatwave-1,0," + status[0] + ",\n");
  }

  private static String text(ByteArrayOutputStream stream) {
    return stream.toString(StandardCharsets.UTF_8);
  }
}
