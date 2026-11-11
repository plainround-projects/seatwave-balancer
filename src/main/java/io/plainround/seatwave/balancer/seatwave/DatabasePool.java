package io.plainround.seatwave.balancer.seatwave;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.Objects;

/**
 * Opens the one connection pool an instance shares between all its requests (HikariCP).
 * Thread-safe: it holds no state.
 */
final class DatabasePool {

  private DatabasePool() {}

  /**
   * Creates the pool. It does not fail when the database is down at start: the instance then starts
   * with a failing readiness check, and the balancer sends it nothing until the database answers.
   *
   * @param settings where the database is
   * @param instanceName the instance, which names the pool in the database's own view
   * @return the pool; the caller closes it when the instance stops
   * @throws IOException if the password file cannot be read; the message names the file
   */
  static HikariDataSource open(ServiceSettings.DatabaseSettings settings, String instanceName)
      throws IOException {
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(instanceName, "instanceName");
    String password;
    try {
      password = Files.readString(settings.passwordFile(), StandardCharsets.UTF_8).strip();
    } catch (NoSuchFileException missing) {
      throw new IOException(
          "the database password file " + settings.passwordFile() + " does not exist", missing);
    }
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(settings.url());
    config.setUsername(settings.user());
    config.setPassword(password);
    config.setPoolName(instanceName);
    // A fixed pool: the number of connections the database sees never changes under load, which
    // is what HikariCP's own sizing advice recommends.
    config.setMaximumPoolSize(settings.poolSize());
    config.setMinimumIdle(settings.poolSize());
    config.setConnectionTimeout(settings.connectTimeout().toMillis());
    config.setValidationTimeout(settings.checkTimeout().toMillis());
    // Negative: start without the database instead of failing; readiness tells the balancer.
    config.setInitializationFailTimeout(-1);
    return new HikariDataSource(config);
  }
}
