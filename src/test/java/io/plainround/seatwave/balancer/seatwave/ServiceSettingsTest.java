package io.plainround.seatwave.balancer.seatwave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import com.zaxxer.hikari.HikariDataSource;
import io.plainround.seatwave.balancer.config.SettingsReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ServiceSettingsTest {

  @Test
  void readsTheContainerSettingsShippedWithTheProject() throws IOException {
    Result<ServiceSettings, List<String>> read =
        ServiceSettings.from(
            new SettingsReader(
                SettingsReader.load(Path.of("config/seatwave-container.properties"))));

    ServiceSettings settings =
        switch (read) {
          case Result.Success<ServiceSettings, List<String>>(ServiceSettings checked) -> checked;
          case Result.Error<ServiceSettings, List<String>>(List<String> problems) ->
              throw new AssertionError("the shipped settings have problems: " + problems);
        };
    assertThat(settings.database().poolSize()).isEqualTo(16);
    assertThat(settings.drainDeadline()).isEqualTo(Duration.ofSeconds(20));
  }

  @Test
  void givesDockerStopMoreTimeThanTheWorstCaseShutdown() throws IOException {
    ServiceSettings shipped =
        switch (ServiceSettings.from(
            new SettingsReader(
                SettingsReader.load(Path.of("config/seatwave-container.properties"))))) {
          case Result.Success<ServiceSettings, List<String>>(ServiceSettings checked) -> checked;
          case Result.Error<ServiceSettings, List<String>>(List<String> problems) ->
              throw new AssertionError(problems.toString());
        };
    String compose = Files.readString(Path.of("compose.yaml"), StandardCharsets.UTF_8);
    Matcher grace = Pattern.compile("\n  stop_grace_period: ([0-9]+)s\n").matcher(compose);
    assertThat(grace.find()).isTrue();

    // The drain deadline, then the wait for the workers: docker must not SIGKILL inside either.
    Duration worstCaseStop = shipped.drainDeadline().plus(SeatWaveServer.WORKER_STOP_WAIT);

    assertThat(Duration.ofSeconds(Long.parseLong(grace.group(1)))).isGreaterThan(worstCaseStop);
  }

  @Test
  void acceptsAPasswordFileInALongFolderWithSpaces() {
    Properties properties = valid();
    String deep = "/tmp/a folder with spaces/" + "x".repeat(240) + "/db.password";
    properties.setProperty("db.passwordFile", deep);

    Result<ServiceSettings, List<String>> read =
        ServiceSettings.from(new SettingsReader(properties));

    assertThat(read).isInstanceOf(Result.Success.class);
  }

  @Test
  void reportsEveryMissingKeyAndBrokenRuleTogether() {
    Properties properties = valid();
    properties.remove("db.url");
    properties.setProperty("http.adminPort", properties.getProperty("http.port"));
    properties.setProperty("db.poolSize", "65");

    Result<ServiceSettings, List<String>> read =
        ServiceSettings.from(new SettingsReader(properties));

    assertThat(read)
        .isEqualTo(
            Result.error(
                List.of(
                    "db.url is missing",
                    "http.port and http.adminPort must differ (0 means any free port)",
                    "db.poolSize must not be larger than http.workerThreads: a connection no worker"
                        + " can use is held open at the database for nothing")));
  }

  @Test
  void reportsAMissingWorkerCountOnceWithoutTheRuleThatNeedsIt() {
    Properties properties = valid();
    properties.remove("http.workerThreads");

    Result<ServiceSettings, List<String>> read =
        ServiceSettings.from(new SettingsReader(properties));

    assertThat(read).isEqualTo(Result.error(List.of("http.workerThreads is missing")));
  }

  @Test
  void refusesAConnectTimeoutTheConnectionPoolCannotUse() {
    // HikariCP throws for a connectionTimeout under 250 ms; it must be a settings problem instead.
    Properties properties = valid();
    properties.setProperty("db.connectTimeoutMillis", "249");

    Result<ServiceSettings, List<String>> read =
        ServiceSettings.from(new SettingsReader(properties));

    assertThat(read)
        .isEqualTo(
            Result.error(
                List.of("db.connectTimeoutMillis must be from 250 to 600000, but was 249")));
  }

  @Test
  void opensThePoolWithTheShortestConnectTimeoutTheSettingsAllow() throws IOException {
    Properties properties = valid();
    properties.setProperty("db.connectTimeoutMillis", "250");
    ServiceSettings settings =
        switch (ServiceSettings.from(new SettingsReader(properties))) {
          case Result.Success<ServiceSettings, List<String>>(ServiceSettings checked) -> checked;
          case Result.Error<ServiceSettings, List<String>>(List<String> problems) ->
              throw new AssertionError(problems.toString());
        };
    Path password = Files.createTempFile("seatwave-password", ".txt");
    try {
      Files.writeString(password, "test-only", StandardCharsets.UTF_8);
      ServiceSettings.DatabaseSettings database =
          new ServiceSettings.DatabaseSettings(
              "jdbc:h2:mem:pool-" + System.nanoTime(),
              "sa",
              password,
              1,
              settings.database().connectTimeout(),
              settings.database().checkTimeout());

      try (HikariDataSource pool = DatabasePool.open(database, "seatwave-test")) {
        assertThat(pool.getConnectionTimeout()).isEqualTo(250);
      }
    } finally {
      Files.delete(password);
    }
  }

  @Test
  void refusesAJdbcUrlForAnotherDatabase() {
    Properties properties = valid();
    properties.setProperty("db.url", "jdbc:mysql://10.0.0.1:3306/seatwave");

    Result<ServiceSettings, List<String>> read =
        ServiceSettings.from(new SettingsReader(properties));

    assertThat(read).isInstanceOf(Result.Error.class);
  }

  @Test
  void refusesAHostNameAsTheBindAddress() {
    Properties properties = valid();
    properties.setProperty("http.bindAddress", "localhost");

    Result<ServiceSettings, List<String>> read =
        ServiceSettings.from(new SettingsReader(properties));

    assertThat(read)
        .isEqualTo(
            Result.error(
                List.of(
                    "http.bindAddress must match [0-9.]{7,15}|[0-9a-fA-F:]{2,39}, but was"
                        + " localhost")));
  }

  @Test
  void refusesDottedNumbersThatAreNotAnIpAddress() {
    Properties properties = valid();
    properties.setProperty("http.bindAddress", "999.1.1.1");

    Result<ServiceSettings, List<String>> read =
        ServiceSettings.from(new SettingsReader(properties));

    assertThat(read)
        .isEqualTo(
            Result.error(List.of("http.bindAddress must be an IP address, but was 999.1.1.1")));
  }

  @Test
  void cannotBeBuiltWithOnePortForBothListeners() {
    ServiceSettings good = TestSettings.withDrainDeadline(Duration.ZERO);

    assertThatThrownBy(() ->
            new ServiceSettings(
                good.bindAddress(), 8080, 8080, 1, 1, 1, good.database(), Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static Properties valid() {
    Properties properties = new Properties();
    properties.setProperty("http.bindAddress", "127.0.0.1");
    properties.setProperty("http.port", "21090");
    properties.setProperty("http.adminPort", "21091");
    properties.setProperty("http.backlog", "50");
    properties.setProperty("http.workerThreads", "64");
    properties.setProperty("http.queueCapacity", "100");
    properties.setProperty("db.url", "jdbc:postgresql://10.210.10.5:5432/seatwave");
    properties.setProperty("db.user", "seatwave");
    properties.setProperty("db.passwordFile", "build/secrets/db.password");
    properties.setProperty("db.poolSize", "16");
    properties.setProperty("db.connectTimeoutMillis", "1000");
    properties.setProperty("readiness.checkTimeoutMillis", "1000");
    properties.setProperty("drain.deadlineMillis", "20000");
    return properties;
  }
}
