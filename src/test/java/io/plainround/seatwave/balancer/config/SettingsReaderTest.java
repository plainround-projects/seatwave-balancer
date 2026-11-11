package io.plainround.seatwave.balancer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsReaderTest {

  private static final Pattern WORD = Pattern.compile("[a-z]+");

  @Test
  void collectsOneProblemPerMissingOrInvalidKey() {
    Properties properties = new Properties();
    properties.setProperty("count", "seven");
    properties.setProperty("range", "99");
    properties.setProperty("word", "Upper");
    properties.setProperty("list", "one, Two");
    SettingsReader reader = new SettingsReader(properties);

    reader.intBetween("count", 1, 10);
    reader.intBetween("range", 1, 10);
    reader.textMatching("word", WORD);
    reader.listMatching("list", WORD);
    reader.text("absent");
    reader.addProblem("a and b must differ");

    assertThat(reader.problems())
        .containsExactly(
            "count must be a whole number, but was seven",
            "range must be from 1 to 10, but was 99",
            "word must match [a-z]+, but was Upper",
            "list has the item 'Two', which must match [a-z]+",
            "absent is missing",
            "a and b must differ");
  }

  @Test
  void readsValidValues() {
    Properties properties = new Properties();
    properties.setProperty("waitMillis", "250");
    properties.setProperty("names", "alpha, beta");
    SettingsReader reader = new SettingsReader(properties);

    assertThat(reader.millisBetween("waitMillis", 1, 1_000)).isEqualTo(Duration.ofMillis(250));
    assertThat(reader.listMatching("names", WORD)).containsExactly("alpha", "beta");
    assertThat(reader.problems()).isEmpty();
  }

  @Test
  void saysThatAMissingFileDoesNotExist(@TempDir Path folder) {
    Path missing = folder.resolve("absent.properties");

    assertThatThrownBy(() -> SettingsReader.load(missing))
        .isInstanceOf(IOException.class)
        .hasMessage("the settings file " + missing + " does not exist");
  }

  @Test
  void acceptsAFilePathInADeepFolderWithSpacesAndPlusSigns() {
    String deepPath = "/checkouts/My Projects+Labs/" + "nested-folder/".repeat(20) + "ca.pem";
    Properties properties = new Properties();
    properties.setProperty("tls.authorityPem", deepPath);
    SettingsReader reader = new SettingsReader(properties);

    String path = reader.filePath("tls.authorityPem");

    assertThat(deepPath).hasSizeGreaterThan(220);
    assertThat(path).isEqualTo(deepPath);
    assertThat(reader.problems()).isEmpty();
  }

  @Test
  void refusesAFilePathWithAControlCharacter() {
    Properties properties = new Properties();
    properties.setProperty("tls.authorityPem", "build/tls/ca\t.pem");
    SettingsReader reader = new SettingsReader(properties);

    String path = reader.filePath("tls.authorityPem");

    assertThat(path).isEmpty();
    assertThat(reader.problems())
        .singleElement()
        .asString()
        .startsWith("tls.authorityPem must match");
  }

  @Test
  void reportsABrokenEscapeAsAnUnreadableSettingsFile(@TempDir Path folder) throws IOException {
    Path broken =
        Files.writeString(
            folder.resolve("broken.properties"),
            "http.bindAddress=\\u12\n",
            StandardCharsets.UTF_8);

    assertThatThrownBy(() -> SettingsReader.load(broken))
        .isInstanceOf(IOException.class)
        .hasMessage(
            "the settings file "
                + broken
                + " is not a valid properties file: Malformed \\uxxxx encoding.");
  }
}
