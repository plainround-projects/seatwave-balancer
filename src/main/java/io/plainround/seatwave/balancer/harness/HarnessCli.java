package io.plainround.seatwave.balancer.harness;

import io.plainround.seatwave.balancer.config.SettingsFiles;
import io.plainround.seatwave.balancer.haproxy.NamedBalancer;
import io.plainround.seatwave.balancer.haproxy.StatRow;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Command-line entry point of the kill-and-drain harness: runs the load the settings describe,
 * watches every balancer's view of the backend during it, and prints the report. It changes
 * nothing; the kills, stops and drains come from {@code scripts/lab.sh} and the deploy tool, which
 * run beside it. Exit status 2 means bad usage or an invalid settings file.
 */
public final class HarnessCli {

  static final int EXIT_BAD_USAGE = 2;
  private static final int EXIT_INTERRUPTED = 1;

  private HarnessCli() {}

  /**
   * Runs one load run.
   *
   * @param arguments the settings file, for example {@code /app/config/kill.properties}
   */
  public static void main(String[] arguments) {
    int status = run(arguments, System.out, System.err);
    if (status != 0) {
      System.exit(status);
    }
  }

  /**
   * Checks the settings, runs the load and prints the report.
   *
   * @param arguments the command line
   * @param out where the report goes
   * @param err where problems go
   * @return the exit status
   */
  static int run(String[] arguments, PrintStream out, PrintStream err) {
    if (arguments.length != 1) {
      err.println("usage: load <harness.properties>");
      return EXIT_BAD_USAGE;
    }
    Optional<HarnessSettings> loaded =
        SettingsFiles.load(Path.of(arguments[0]), HarnessSettings::from, err);
    if (loaded.isEmpty()) {
      return EXIT_BAD_USAGE;
    }
    HarnessSettings settings = loaded.get();
    try {
      out.print(measure(settings, Clock.systemUTC()).render());
      return 0;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      err.println("the run was interrupted; no report");
      return EXIT_INTERRUPTED;
    }
  }

  private static RunReport measure(HarnessSettings settings, Clock clock)
      throws InterruptedException {
    // Time zero is the end of the warm-up: the watches and the load start now, the report counts
    // from then on.
    long startNanos = System.nanoTime() + settings.warmup().toNanos();
    String keyPrefix = settings.label() + "-" + clock.millis();
    List<BalancerWatch> watches = new ArrayList<>();
    List<Thread> threads = new ArrayList<>();
    for (NamedBalancer balancer : settings.balancers()) {
      BalancerWatch watch =
          new BalancerWatch(balancer, settings.backend(), settings.watchInterval(), startNanos);
      watches.add(watch);
      threads.add(Thread.ofPlatform().name("watch-" + balancer.name()).start(watch));
    }
    List<Outcome> outcomes;
    LoadRun.Mix mix;
    try (HttpClient client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(settings.connectTimeout())
            .build()) {
      LoadRun load = new LoadRun(settings, client, startNanos, keyPrefix);
      outcomes = load.run();
      mix = load.mix();
    } finally {
      // Stopped on every path, so no watch thread outlives the run.
      for (BalancerWatch watch : watches) {
        watch.stop();
      }
    }
    for (Thread thread : threads) {
      thread.join();
    }
    List<StateChange> changes = new ArrayList<>();
    Map<String, List<StatRow>> backendRows = new HashMap<>();
    for (BalancerWatch watch : watches) {
      changes.addAll(watch.changes());
      watch.backendRows().ifPresent(rows -> backendRows.put(watch.balancerName(), rows));
    }
    return new RunReport(settings, mix, outcomes, changes, backendRows);
  }
}
