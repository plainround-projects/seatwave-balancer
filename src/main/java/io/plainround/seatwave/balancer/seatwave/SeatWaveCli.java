package io.plainround.seatwave.balancer.seatwave;

import com.zaxxer.hikari.HikariDataSource;
import io.plainround.seatwave.balancer.config.SettingsFiles;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;

/**
 * Command-line entry point of one SeatWave instance: reads the settings, opens the database pool,
 * starts the listeners, marks itself ready, and runs until it is told to stop (Ctrl-C, or {@code
 * docker stop}'s SIGTERM). Then it drains: it refuses new requests, waits for the ones in flight up
 * to {@code drain.deadlineMillis}, and only then closes. Exit status 2 means bad usage or an
 * invalid settings file.
 */
public final class SeatWaveCli {

  // Exit status 2 is the shell convention for "the command was used incorrectly".
  static final int EXIT_BAD_USAGE = 2;
  // The name shows up in X-Served-By, in the balancer's server list and in log lines.
  private static final Pattern INSTANCE_NAME = Pattern.compile("[a-z][a-z0-9-]{0,31}");

  private SeatWaveCli() {}

  /**
   * Runs one instance.
   *
   * @param arguments the settings file and the instance name, for example {@code
   *     /app/config/seatwave-container.properties seatwave-1}
   * @throws InterruptedException if the main thread is interrupted while it waits
   */
  public static void main(String[] arguments) throws InterruptedException {
    Optional<Running> running = start(arguments, System.out, System.err);
    if (running.isEmpty()) {
      System.exit(EXIT_BAD_USAGE);
      return;
    }
    CountDownLatch stopped = new CountDownLatch(1);
    // The JVM runs this hook on SIGTERM and waits for it, so the drain finishes before the process
    // exits; docker stop's grace period (compose.yaml) is longer than the drain deadline.
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(() -> {
              stop(running.get(), System.out);
              stopped.countDown();
            }));
    stopped.await();
  }

  /**
   * Checks the arguments and the settings, opens the pool and starts the listeners.
   *
   * @param arguments the command line
   * @param out where the listening addresses are printed
   * @param err where problems are printed
   * @return the running instance, or empty after printing why it cannot start
   */
  static Optional<Running> start(String[] arguments, PrintStream out, PrintStream err) {
    if (arguments.length != 2 || !INSTANCE_NAME.matcher(arguments[1]).matches()) {
      err.println("usage: SeatWaveCli <seatwave.properties> <instance name, such as seatwave-1>");
      return Optional.empty();
    }
    Optional<ServiceSettings> loaded =
        SettingsFiles.load(Path.of(arguments[0]), ServiceSettings::from, err);
    if (loaded.isEmpty()) {
      return Optional.empty();
    }
    ServiceSettings settings = loaded.get();
    String instanceName = arguments[1];
    HikariDataSource pool;
    try {
      pool = DatabasePool.open(settings.database(), instanceName);
    } catch (IOException cannotRead) {
      err.println("SeatWave cannot start: " + cannotRead.getMessage());
      return Optional.empty();
    }
    try {
      HoldStore store =
          new JdbcHoldStore(pool, Clock.systemUTC(), settings.database().checkTimeout());
      SeatWaveServer server =
          SeatWaveServer.start(settings, instanceName, store, Clock.systemUTC());
      server.markReady();
      String address = settings.bindAddress().getHostAddress();
      out.println(instanceName + " serving on " + address + ":" + server.port());
      out.println(
          instanceName
              + " checks (/health, /ready, /metrics) on "
              + address
              + ":"
              + server.adminPort());
      return Optional.of(new Running(server, pool, settings, instanceName));
    } catch (IOException cannotStart) {
      pool.close();
      err.println("SeatWave cannot start: " + cannotStart.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Drains and stops a running instance, then closes its pool.
   *
   * @param running the instance
   * @param out where the drain's result is printed
   */
  static void stop(Running running, PrintStream out) {
    Lifecycle.DrainResult result =
        running.server().drainAndStop(running.settings().drainDeadline());
    running.pool().close();
    out.println(
        running.instanceName()
            + (result.isClean() ? " drained cleanly: " : " drain deadline passed: ")
            + result.inFlightLeft()
            + " in flight left after "
            + result.waitedMillis()
            + " ms; "
            + running.server().notAdmittedCount()
            + " requests refused while not ready; stopped");
  }

  /** A started instance and what must be closed with it. */
  record Running(
      SeatWaveServer server,
      HikariDataSource pool,
      ServiceSettings settings,
      String instanceName) {}
}
