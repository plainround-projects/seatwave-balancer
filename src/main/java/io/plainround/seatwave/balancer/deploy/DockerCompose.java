package io.plainround.seatwave.balancer.deploy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs {@code docker compose -p <project> stop|start <service>} as a child process, with a fixed
 * argument list (never a shell string), so a service name cannot become a second command.
 * Thread-safe: it holds only immutable fields.
 */
final class DockerCompose implements Containers {

  // The command's own wait on top of docker's stop timeout: pulling nothing, starting a container.
  private static final Duration COMMAND_MARGIN = Duration.ofSeconds(30);
  private static final List<String> DOCKER_COMPOSE = List.of("docker", "compose");

  private final List<String> program;
  private final String project;
  private final Duration stopTimeout;

  DockerCompose(String project, Duration stopTimeout) {
    this(DOCKER_COMPOSE, project, stopTimeout);
  }

  /**
   * Runs another program in place of {@code docker compose}, for the tests.
   *
   * @param program the program and its first arguments
   * @param project the Compose project
   * @param stopTimeout docker's stop timeout
   */
  DockerCompose(List<String> program, String project, Duration stopTimeout) {
    this.program = List.copyOf(program);
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
    List<String> command = new ArrayList<>(program);
    command.addAll(List.of("-p", project));
    command.addAll(action);
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    // Read while the child runs: a child whose output fills the pipe would otherwise wait for a
    // reader forever, and the wait below would end only at its time limit.
    FutureTask<String> output =
        new FutureTask<>(() -> {
          try (InputStream stream = process.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
          }
        });
    Thread.ofVirtual().name("docker-compose-output").start(output);
    try {
      if (!process.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new IOException(String.join(" ", command) + " did not finish within " + limit);
      }
      // Bounded too: a grandchild that kept the pipe open would otherwise hold the read forever.
      String text = output.get(limit.toMillis(), TimeUnit.MILLISECONDS);
      if (process.exitValue() != 0) {
        throw new IOException(
            String.join(" ", command) + " exited " + process.exitValue() + ": " + text.strip());
      }
    } catch (InterruptedException interrupted) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IOException(String.join(" ", command) + " was interrupted", interrupted);
    } catch (TimeoutException stillOpen) {
      process.destroyForcibly();
      throw new IOException(
          "the output of " + String.join(" ", command) + " did not end within " + limit, stillOpen);
    } catch (ExecutionException unreadable) {
      throw new IOException(
          "the output of " + String.join(" ", command) + " cannot be read", unreadable.getCause());
    }
  }
}
