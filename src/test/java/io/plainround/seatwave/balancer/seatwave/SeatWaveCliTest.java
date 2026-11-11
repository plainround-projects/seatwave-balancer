package io.plainround.seatwave.balancer.seatwave;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SeatWaveCliTest {

  @TempDir Path folder;

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();

  @Test
  void refusesAnInstanceNameThatIsNotAName() {
    Optional<SeatWaveCli.Running> running =
        start("config/seatwave-container.properties", "Seat Wave");

    assertThat(running).isEmpty();
    assertThat(text(err)).contains("usage: serve");
  }

  @Test
  void saysWhichPasswordFileIsMissing() throws IOException {
    Path settings = settings(folder.resolve("absent.password"));

    Optional<SeatWaveCli.Running> running = start(settings.toString(), "seatwave-1");

    assertThat(running).isEmpty();
    assertThat(text(err))
        .contains("the database password file", "absent.password", "does not exist");
  }

  @Test
  void startsReadyAndStopsWithACleanDrain() throws IOException {
    Path password =
        Files.writeString(folder.resolve("db.password"), "lab-only\n", StandardCharsets.UTF_8);
    Path settings = settings(password);

    SeatWaveCli.Running running = start(settings.toString(), "seatwave-1").orElseThrow();
    SeatWaveCli.stop(running, new PrintStream(out, true, StandardCharsets.UTF_8));

    assertThat(text(out))
        .contains("seatwave-1 serving on 127.0.0.1:")
        .contains("seatwave-1 drained cleanly: 0 in flight left");
    assertThat(running.server().state()).isEqualTo(LifecycleState.STOPPED);
  }

  private Optional<SeatWaveCli.Running> start(String settings, String name) {
    return SeatWaveCli.start(
        new String[] {settings, name},
        new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8));
  }

  private Path settings(Path passwordFile) throws IOException {
    Properties properties = ServiceSettingsTest.valid();
    properties.setProperty("http.port", "0");
    properties.setProperty("http.adminPort", "0");
    properties.setProperty("db.url", "jdbc:h2:mem:cli-test");
    properties.setProperty("db.passwordFile", passwordFile.toString());
    properties.setProperty("drain.deadlineMillis", "1000");
    Path file = folder.resolve("seatwave.properties");
    try (FileOutputStream stream = new FileOutputStream(file.toFile())) {
      properties.store(stream, "test");
    }
    return file;
  }

  private static String text(ByteArrayOutputStream stream) {
    return stream.toString(StandardCharsets.UTF_8);
  }
}
