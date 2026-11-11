package io.plainround.seatwave.balancer.deploy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@code docker compose -p <project> stop|start <service>} as a child process, with a fixed
 * argument list (never a shell string), so a service name cannot become a second command.
 * Thread-safe: it holds only immutable fields.
 */
final class DockerCompose implements Containers {

  // The command's own wait on top of docker's stop timeout: pulling nothing, starting a container.
  private static final Duration COMMAND_MARGIN = Duration.ofSeconds(30);

  private final String project;
  private final Duration stopTimeout;

  DockerCompose(String project, Duration stopTimeout) {
    this.project = Objects.requireNonNull(project, "project");
    this.stopTimeout = Objects.requireNonNull(stopTimeout, "stopTimeout");
  }

  @Override
  public void stop(String service) throws IOException {
    run(
        List.of("stop", "-t", Long.toString(stopTimeout.toSeconds()), service),
        stopTimeout.plus(COMMAND_MARGIN));
  }

  @Override
  public void start(String service) throws IOException {
    run(List.of("start", service), COMMAND_MARGIN);
  }

  private void run(List<String> action, Duration limit) throws IOException {
    List<String> command = new ArrayList<>(List.of("docker", "compose", "-p", project));
    command.addAll(action);
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    try {
      if (!process.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new IOException(String.join(" ", command) + " did not finish within " + limit);
      }
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (process.exitValue() != 0) {
        throw new IOException(
            String.join(" ", command) + " exited " + process.exitValue() + ": " + output.strip());
      }
    } catch (InterruptedException interrupted) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IOException(String.join(" ", command) + " was interrupted", interrupted);
    }
  }
}
