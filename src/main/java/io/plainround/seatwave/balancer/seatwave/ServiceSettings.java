package io.plainround.seatwave.balancer.seatwave;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.balancer.config.SettingsReader;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Everything one SeatWave instance needs, read from a settings file with no defaults.
 *
 * @param bindAddress the address both listeners bind to: 0.0.0.0 inside a container, 127.0.0.1 on a
 *     reader's own machine
 * @param port the request listener, the one the balancer sends requests to
 * @param adminPort the operations listener for {@code /health}, {@code /ready} and {@code
 *     /metrics}, on its own thread, so a busy instance still answers its checks
 * @param backlog connections the kernel may hold for each listener before SeatWave accepts them
 * @param workerThreads the platform threads that run requests, Module 4's 64
 * @param queueCapacity requests that may wait for a worker; one more is refused
 * @param database where the shared database is and how many connections to keep
 * @param drainDeadline the longest a shutdown waits for in-flight requests
 */
public record ServiceSettings(
    InetAddress bindAddress,
    int port,
    int adminPort,
    int backlog,
    int workerThreads,
    int queueCapacity,
    DatabaseSettings database,
    Duration drainDeadline) {

  // An IP address literal; a host name would need DNS before the server could start.
  private static final Pattern ADDRESS = Pattern.compile("[0-9.]{7,15}|[0-9a-fA-F:]{2,39}");
  // Only the two databases this project runs: PostgreSQL in the stack, H2 in a reader's test.
  private static final Pattern JDBC_URL =
      Pattern.compile("jdbc:(postgresql://[A-Za-z0-9.-]{1,100}:[0-9]{1,5}/[a-z_]{1,32}|h2:mem:.*)");
  private static final Pattern USER = Pattern.compile("[a-z_][a-z0-9_]{0,31}");
  private static final int HIGHEST_PORT = 65_535;
  private static final int MAX_WORKERS = 1_024;
  private static final int MAX_BACKLOG = 65_535;
  private static final int MAX_QUEUE = 100_000;
  // PostgreSQL's default max_connections is 100; one instance never needs more than that.
  private static final int MAX_POOL = 100;
  private static final int MAX_WAIT_MILLIS = 600_000;
  // JDBC's isValid counts whole seconds, so the readiness check cannot wait less than one.
  private static final int MIN_CHECK_MILLIS = 1_000;
  // HikariCP refuses a connectionTimeout below 250 ms with an exception (HikariConfig, 7.1.0).
  private static final int MIN_CONNECT_MILLIS = 250;

  /**
   * Creates the settings.
   *
   * @throws IllegalArgumentException if the two listeners share a port
   */
  public ServiceSettings {
    Objects.requireNonNull(bindAddress, "bindAddress");
    Objects.requireNonNull(database, "database");
    Objects.requireNonNull(drainDeadline, "drainDeadline");
    if (port != 0 && port == adminPort) {
      throw new IllegalArgumentException("the two listeners need two different ports");
    }
  }

  /**
   * Reads the settings, reporting every missing or invalid key and every broken rule between keys
   * together.
   *
   * @param reader the settings reader
   * @return the settings, or one line per problem
   */
  public static Result<ServiceSettings, List<String>> from(SettingsReader reader) {
    Objects.requireNonNull(reader, "reader");
    String bind = reader.textMatching("http.bindAddress", ADDRESS);
    int port = reader.intBetween("http.port", 0, HIGHEST_PORT);
    int adminPort = reader.intBetween("http.adminPort", 0, HIGHEST_PORT);
    int backlog = reader.intBetween("http.backlog", 1, MAX_BACKLOG);
    int problemsBeforeWorkers = reader.problems().size();
    int workers = reader.intBetween("http.workerThreads", 1, MAX_WORKERS);
    boolean workersRead = reader.problems().size() == problemsBeforeWorkers;
    int queue = reader.intBetween("http.queueCapacity", 1, MAX_QUEUE);
    String url = reader.textMatching("db.url", JDBC_URL);
    String user = reader.textMatching("db.user", USER);
    String passwordFile = reader.filePath("db.passwordFile");
    int problemsBeforePool = reader.problems().size();
    int poolSize = reader.intBetween("db.poolSize", 1, MAX_POOL);
    boolean poolRead = reader.problems().size() == problemsBeforePool;
    Duration connect =
        reader.millisBetween("db.connectTimeoutMillis", MIN_CONNECT_MILLIS, MAX_WAIT_MILLIS);
    Duration check =
        reader.millisBetween("readiness.checkTimeoutMillis", MIN_CHECK_MILLIS, MAX_WAIT_MILLIS);
    Duration drain = reader.millisBetween("drain.deadlineMillis", 0, MAX_WAIT_MILLIS);
    if (port != 0 && port == adminPort) {
      reader.addProblem("http.port and http.adminPort must differ (0 means any free port)");
    }
    // A rule between two keys is checked only when both were read, so a missing key gives one
    // problem line, not a second one about the stand-in value.
    if (workersRead && poolRead && poolSize > workers) {
      reader.addProblem(
          "db.poolSize must not be larger than http.workerThreads: a connection no worker can use"
              + " is held open at the database for nothing");
    }
    Optional<InetAddress> address = bind.isEmpty() ? Optional.empty() : literal(reader, bind);
    if (!reader.problems().isEmpty() || address.isEmpty()) {
      return Result.error(reader.problems());
    }
    return Result.success(
        new ServiceSettings(
            address.get(),
            port,
            adminPort,
            backlog,
            workers,
            queue,
            new DatabaseSettings(url, user, Path.of(passwordFile), poolSize, connect, check),
            drain));
  }

  private static Optional<InetAddress> literal(SettingsReader reader, String text) {
    try {
      return Optional.of(InetAddress.ofLiteral(text));
    } catch (IllegalArgumentException notAnAddress) {
      reader.addProblem("http.bindAddress must be an IP address, but was " + text);
      return Optional.empty();
    }
  }

  /**
   * Where the shared database is.
   *
   * @param url the JDBC URL
   * @param user the database user
   * @param passwordFile the file that holds the password, generated at run time and never committed
   * @param poolSize connections kept open to the database
   * @param connectTimeout the longest a request waits for a connection from the pool
   * @param checkTimeout the longest the readiness check waits for the database
   */
  public record DatabaseSettings(
      String url,
      String user,
      Path passwordFile,
      int poolSize,
      Duration connectTimeout,
      Duration checkTimeout) {

    /** Checks that no field is missing. */
    public DatabaseSettings {
      Objects.requireNonNull(url, "url");
      Objects.requireNonNull(user, "user");
      Objects.requireNonNull(passwordFile, "passwordFile");
      Objects.requireNonNull(connectTimeout, "connectTimeout");
      Objects.requireNonNull(checkTimeout, "checkTimeout");
    }
  }
}
