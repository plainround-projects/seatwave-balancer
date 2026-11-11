package io.plainround.seatwave.balancer.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsFilesTest {

  @TempDir Path folder;

  @Test
  void printsEveryProblemOfTheFile() throws Exception {
    Path file =
        Files.writeString(folder.resolve("bad.properties"), "a=1\n", StandardCharsets.UTF_8);
    ByteArrayOutputStream err = new ByteArrayOutputStream();

    Optional<String> loaded =
        SettingsFiles.load(
            file,
            reader -> Result.error(List.of("one", "two")),
            new PrintStream(err, true, StandardCharsets.UTF_8));

    assertThat(loaded).isEmpty();
    assertThat(err.toString(StandardCharsets.UTF_8)).contains("2 problem(s)", "  one", "  two");
  }

  @Test
  void saysWhichFileIsMissing() {
    ByteArrayOutputStream err = new ByteArrayOutputStream();

    Optional<String> loaded =
        SettingsFiles.load(
            folder.resolve("absent.properties"),
            reader -> Result.success("unused"),
            new PrintStream(err, true, StandardCharsets.UTF_8));

    assertThat(loaded).isEmpty();
    assertThat(err.toString(StandardCharsets.UTF_8)).contains("absent.properties does not exist");
  }

  @Test
  void reportsAMalformedEscapeAsAProblemNotACrash() throws Exception {
    Path file =
        Files.writeString(
            folder.resolve("escape.properties"), "a=\\uZZZZ\n", StandardCharsets.UTF_8);
    ByteArrayOutputStream err = new ByteArrayOutputStream();

    Optional<String> loaded =
        SettingsFiles.load(
            file,
            reader -> Result.success("unused"),
            new PrintStream(err, true, StandardCharsets.UTF_8));

    assertThat(loaded).isEmpty();
    assertThat(err.toString(StandardCharsets.UTF_8)).contains("escape.properties cannot be read");
  }
}
