package io.plainround.seatwave.balancer.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DockerCompose with a stand-in program in place of {@code docker compose}: a small shell script
 * that writes its arguments to a file, so the tests see the exact command line, and can fail or
 * print a lot on purpose. Needs a POSIX shell, as the project's scripts do.
 */
class DockerComposeTest {

  @TempDir Path folder;

  @Test
  void stopsWithDockersWholeSecondTimeoutAndStartsByServiceName() throws Exception {
    Path calls = folder.resolve("calls.txt");
    DockerCompose compose =
        compose("printf '%s\\n' \"$*\" >> \"" + calls + "\"", Duration.ofMillis(30_500));

    compose.stop("seatwave-2");
    compose.start("seatwave-2");

    assertThat(Files.readAllLines(calls, StandardCharsets.UTF_8))
        .containsExactly(
            "-p seatwave-balancer stop -t 30 seatwave-2", "-p seatwave-balancer start seatwave-2");
  }

  @Test
  void reportsAFailedCommandWithItsExitStatusAndOutput() throws Exception {
    DockerCompose compose = compose("echo 'no such service' >&2; exit 3", Duration.ofSeconds(1));

    assertThatThrownBy(() -> compose.start("seatwave-9"))
        .isInstanceOf(IOException.class)
        .hasMessageEndingWith("start seatwave-9 exited 3: no such service");
  }

  @Test
  void readsALargeOutputWhileTheCommandRuns() throws Exception {
    // About 1 MB, far more than a pipe holds: a child that cannot write would never end.
    DockerCompose compose =
        compose(
            "i=0; while [ $i -lt 20000 ]; do echo " + "x".repeat(50) + "; i=$((i+1)); done; exit 1",
            Duration.ofSeconds(1));

    assertThatThrownBy(() -> compose.start("seatwave-1"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("exited 1");
  }

  private DockerCompose compose(String body, Duration stopTimeout) throws IOException {
    Path program = folder.resolve("docker-stand-in.sh");
    Files.writeString(program, "#!/bin/sh\n" + body + "\n", StandardCharsets.UTF_8);
    return new DockerCompose(List.of("sh", program.toString()), "seatwave-balancer", stopTimeout);
  }
}
