package io.plainround.seatwave.balancer.deploy;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.balancer.config.SettingsFiles;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Command-line entry point of the rolling deploy. A dry run by default: it prints the plan and
 * changes nothing. With {@code --run} it asks for confirmation (or takes {@code --yes}), checks
 * that every instance is up on every balancer, restarts them one by one, checks again, and appends
 * an audit line for the start and the end. Exit status 0 on success, 1 when the deploy stopped, 2
 * for bad usage or invalid settings.
 */
public final class DeployCli {

  static final int EXIT_BAD_USAGE = 2;
  static final int EXIT_FAILED = 1;

  private DeployCli() {}

  /**
   * Runs the deploy.
   *
   * @param arguments the settings file, then {@code --run} and optionally {@code --yes}
   */
  public static void main(String[] arguments) {
    int status =
        run(arguments, System.in, System.out, System.err, Clock.systemUTC(), DockerCompose::new);
    if (status != 0) {
      System.exit(status);
    }
  }

  /**
   * Parses the arguments, loads the settings, and either prints the plan or runs the deploy.
   *
   * @param arguments the command line
   * @param in where the confirmation is read
   * @param out where the plan and progress go
   * @param err where problems go
   * @param clock the clock of the audit lines
   * @param containersFor makes the container runner for a project and stop timeout
   * @return the exit status
   */
  static int run(
      String[] arguments,
      InputStream in,
      PrintStream out,
      PrintStream err,
      Clock clock,
      ContainersFactory containersFor) {
    if (arguments.length < 1 || arguments.length > 3) {
      err.println("usage: deploy <deploy.properties> [--run] [--yes]");
      return EXIT_BAD_USAGE;
    }
    List<String> flags = List.of(arguments).subList(1, arguments.length);
    if (!flags.stream().allMatch(flag -> flag.equals("--run") || flag.equals("--yes"))) {
      err.println("usage: deploy <deploy.properties> [--run] [--yes]");
      return EXIT_BAD_USAGE;
    }
    Optional<DeploySettings> loaded =
        SettingsFiles.load(Path.of(arguments[0]), DeploySettings::from, err);
    if (loaded.isEmpty()) {
      return EXIT_BAD_USAGE;
    }
    DeploySettings settings = loaded.get();
    printPlan(settings, out);
    if (!flags.contains("--run")) {
      out.println("dry run: nothing changed. Add --run to do it.");
      return 0;
    }
    if (!flags.contains("--yes") && !confirmed(in, out)) {
      err.println("not confirmed; nothing changed");
      return EXIT_FAILED;
    }
    List<BalancerControl> balancers =
        settings.balancers().stream().<BalancerControl>map(RuntimeBalancer::new).toList();
    return deploy(
        new RollingDeploy(
            settings,
            balancers,
            containersFor.create(settings.project(), settings.stopTimeout()),
            out),
        settings,
        out,
        err,
        clock);
  }

  private static int deploy(
      RollingDeploy rolling,
      DeploySettings settings,
      PrintStream out,
      PrintStream err,
      Clock clock) {
    try {
      audit(settings, clock, "deploy: started on " + settings.instances());
    } catch (IOException cannotAudit) {
      err.println(
          "deploy not started: the audit file cannot be written: "
              + cannotAudit.getMessage()
              + "; nothing changed");
      return EXIT_FAILED;
    }
    Result<Integer, String> result;
    try {
      result = rolling.run();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      err.println("deploy interrupted");
      return EXIT_FAILED;
    }
    // The deploy's own outcome is printed first, so a failing audit file never hides what happened
    // to the instances.
    String auditLine;
    int status;
    switch (result) {
      case Result.Success<Integer, String>(Integer restarted) -> {
        out.println("deploy finished: " + restarted + " instances restarted, all up");
        auditLine = "deploy: finished, " + restarted + " instances restarted";
        status = 0;
      }
      case Result.Error<Integer, String>(String reason) -> {
        err.println("deploy stopped: " + reason);
        auditLine = "deploy: stopped: " + reason;
        status = EXIT_FAILED;
      }
    }
    try {
      audit(settings, clock, auditLine);
    } catch (IOException cannotAudit) {
      // A guardrail failed, so the exit status is not 0, but the message says what really happened.
      err.println(
          "the audit line of this deploy cannot be written ("
              + cannotAudit.getMessage()
              + "); the deploy itself "
              + (status == 0 ? "finished" : "stopped")
              + " as printed above");
      return EXIT_FAILED;
    }
    return status;
  }

  private static void printPlan(DeploySettings settings, PrintStream out) {
    out.println(
        "plan: restart "
            + settings.instances()
            + " of project "
            + settings.project()
            + " one at a time, on balancers "
            + settings.balancers().stream().map(balancer -> balancer.name()).toList()
            + ", backends "
            + settings.backends());
    out.println(
        "  each: drain, wait for 0 sessions, stop (SIGTERM), wait until seen down, start, ready,"
            + " wait until up, settle "
            + settings.settle().toMillis()
            + " ms");
  }

  private static boolean confirmed(InputStream in, PrintStream out) {
    out.print("Go ahead? [y/N] ");
    out.flush();
    try {
      String answer =
          new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).readLine();
      return "y".equals(answer);
    } catch (IOException unreadable) {
      return false;
    }
  }

  private static void audit(DeploySettings settings, Clock clock, String line) throws IOException {
    Path file = settings.auditFile();
    if (file.getParent() != null) {
      Files.createDirectories(file.getParent());
    }
    Files.writeString(
        file,
        clock.instant() + " " + line + "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
  }

  /** Makes the container runner, so the tests can give one that records calls. */
  @FunctionalInterface
  interface ContainersFactory {

    /**
     * Makes a runner.
     *
     * @param project the Compose project
     * @param stopTimeout docker's stop timeout
     * @return the runner
     */
    Containers create(String project, Duration stopTimeout);
  }
}
