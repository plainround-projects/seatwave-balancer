# The `deploy` package: restarting every instance without failing a request

## What it is

The `deploy` package is a command-line program written in Java. It restarts the three SeatWave instances one at a time while the service keeps answering requests. This is called a **rolling deploy**: instead of stopping everything and starting everything (which means downtime), you replace the servers in a rolling order, so that at every moment at least two of the three are working.

A deploy that fails no request needs more than "stop one, start one". A load balancer keeps sending requests to a server until it learns the server is gone. A request that arrives at a server that is shutting down fails. So the program does a careful dance with the two HAProxy balancers (`lb1` and `lb2`) for each instance:

1. **Drain** the instance on every balancer: tell HAProxy to send it no new requests while the running ones finish.
2. **Wait** until no request is running on it.
3. **Stop** it with a polite signal (SIGTERM). The instance also drains itself.
4. **Wait** until every balancer's health checks have seen it go down.
5. **Start** it again.
6. Set it **ready** on every balancer, and **wait** until the health checks see it up.
7. **Settle**: wait while HAProxy's slow start raises its share of traffic step by step.

Only then does the program move to the next instance.

The program is a **dry run by default**: it prints what it would do and changes nothing. You must add `--run` to make it act, and it then asks for a `y` unless you also add `--yes`. It writes one line to an audit file when it starts and when it ends.

## Why this repository uses it

Module 10 claims that a rolling deploy can cost zero failed requests, and it measures the claim. The README's captured run sent 299,999 requests at 3,000 per second for 100 seconds while all three instances were restarted: 0 failed. A claim like that needs a repeatable tool, not a person typing commands at the right moments. The tool also records every step with a time, so the report shows how long each step took.

The package also shows a safety habit of this course. A program that stops servers must be hard to run by accident. Here it is a dry run unless told otherwise, it asks first, it only touches the one Compose project named in its settings, and it leaves an audit line behind.

## Where it appears

| File | What it does |
|---|---|
| `deploy/Containers.java` | The two things the deploy does to a service: stop it and start it. An interface. |
| `deploy/DockerCompose.java` | The real `Containers`: runs `docker compose` as a child process. |
| `deploy/BalancerControl.java` | The two things the deploy does to a balancer: read its table and set a server's state. An interface. |
| `deploy/RuntimeBalancer.java` | The real `BalancerControl`: uses the runtime API of the `haproxy` package. |
| `deploy/DeploySettings.java` | Reads and checks `config/deploy.properties`. |
| `deploy/RollingDeploy.java` | The algorithm above. |
| `deploy/DeployCli.java` | The entry point: arguments, plan, confirmation, audit, exit status. |
| `config/deploy.properties` | The settings for the lab. |
| `build.gradle.kts` | The Gradle task `deploy` that runs `DeployCli`. |
| `scripts/lab.sh` | Runs the deploy at +8 s into a load run (Lab 3). |

## How it works underneath

**Processes and signals.** A **process** is a running program. Docker runs each service in a container, and the main program of the SeatWave container is a process. To stop it politely, an outside program sends it a **signal**. SIGTERM means "please finish and exit". The program may catch it, finish its work, and exit. SIGKILL cannot be caught and ends the program at once. `docker compose stop` sends SIGTERM and waits for a number of seconds (the `-t` value). If the process is still alive after that, it sends SIGKILL.

**What the instance does on SIGTERM.** SeatWave catches SIGTERM and **drains itself**: it stops admitting new requests, waits for the running ones to finish (up to 20 seconds, `drain.deadlineMillis`), then closes. So `config/deploy.properties` gives `docker` 30 seconds (`deploy.stopTimeoutMillis=30000`), more than the 20-second deadline, and Docker never has to use SIGKILL on a draining instance.

**Two layers of draining.** The balancer drain and the instance drain protect against different gaps. The balancer drain stops new requests from being sent. The instance drain protects requests already on the way. The deploy uses both and waits between them.

**Why the deploy waits for "seen down".** After the instance stops, HAProxy does not know yet. It learns by failed health checks: one every 2 seconds, three in a row to mark it down. If the deploy started the instance again quickly, HAProxy might never notice the stop. It would then send the full share of traffic at once to an instance that is still starting, and slow start (which ramps traffic up only after a server comes back from `DOWN`) would not apply. The comment in `RollingDeploy` says exactly this. In the README's run, "seen down" took about 11.7 seconds after the stop.

**One balancer's state is its own.** Two HAProxy programs do not share anything. A drain set on `lb1` is not seen by `lb2`. So every step is applied to both balancers, and every wait waits for both.

**Backends that track others.** The balancers have five backends. Two of them (`seatwave`, `seatwave_active_only`) hold their own copy of each server. The other three are written with `track seatwave/seatwave-1` and similar lines, so they follow the state of the `seatwave` backend. `deploy.backends` lists only the two that hold their own servers, because a state set on a tracked server reaches the others.

**Java words used in this package.**

| Word | Meaning |
|---|---|
| `interface` | A list of methods without bodies. Other classes promise to provide them. The deploy uses two, so a test can replace Docker and HAProxy. |
| `final class` | A class that no other class may extend. |
| `record` | A class that only holds values. The compiler writes the constructor, the getters (written `name()`) and `equals`. |
| `Result<T, E>` | A type from the `result4j` library with two cases: `Success` holds a `T`, `Error` holds an `E`. Used for failures that are expected. |
| `Optional<T>` | A box that holds a `T` or is empty. It replaces `null`. |
| lambda `x -> ...` | A short unnamed function. |
| method reference `Type::method` | A short way to write a lambda that calls one method. |
| `switch` with patterns | A `switch` that matches the shape of a value, and may take a record apart. |
| `try`/`catch` | Run code, and handle an exception if one is thrown. |
| `throws` | A method says which checked exceptions it can throw. |

## Walkthrough of this repository's use

### `deploy/Containers.java`

```java
package io.plainround.seatwave.balancer.deploy;

import java.io.IOException;

/**
 * Stops and starts one service of the Compose project. The real one runs {@code docker compose};
 * the tests use one that records the calls, because Docker is not something this project owns.
 */
interface Containers {
```

An **interface** lists methods and gives no code. `Containers` is package-private (there is no `public`), so only this package sees it. The Javadoc names the reason it exists: the real implementation runs Docker, and a test must not. `{@code docker compose}` marks code inside a comment.

```java
  /**
   * Stops a service gracefully: SIGTERM, then SIGKILL after the stop timeout.
   *
   * @param service the Compose service
   * @throws IOException if the command fails or does not finish in time
   */
  void stop(String service) throws IOException;

  /**
   * Starts a stopped service again.
   *
   * @param service the Compose service
   * @throws IOException if the command fails or does not finish in time
   */
  void start(String service) throws IOException;
}
```

Two methods. `stop` stops a Compose **service**, which is one named entry in `compose.yaml` (here `seatwave-2`). `start` starts a stopped one. Both throw `IOException` when the command fails or takes too long. The Javadoc for `stop` repeats the signal story: SIGTERM first, SIGKILL after the stop timeout.

### `deploy/BalancerControl.java`

```java
package io.plainround.seatwave.balancer.deploy;

import io.plainround.seatwave.balancer.haproxy.RuntimeCommand;
import io.plainround.seatwave.balancer.haproxy.StatTable;
import java.io.IOException;

/**
 * What the deploy needs from one balancer: read its servers, and set a server's administrative
 * state. The real one talks to HAProxy's runtime API; the tests use one they script, because
 * HAProxy is not something this project owns.
 */
interface BalancerControl {
```

The same idea for a balancer: an interface so that tests can script one. It imports `RuntimeCommand` and `StatTable` from the `haproxy` package (see `java-haproxy-client-walkthrough.md`).

```java
  /**
   * Returns the balancer's name.
   *
   * @return the name, such as {@code lb1}
   */
  String name();

  /**
   * Reads every row of {@code show stat}.
   *
   * @return the table
   * @throws IOException if the balancer does not answer
   */
  StatTable stat() throws IOException;

  /**
   * Sets a server's administrative state.
   *
   * @param backend the backend
   * @param server the server
   * @param state the state
   * @throws IOException if the balancer does not answer or refuses
   */
  void setState(String backend, String server, RuntimeCommand.AdminState state) throws IOException;
}
```

Three methods. `name()` is the balancer's name (`lb1`) and appears in messages. `stat()` reads the whole `show stat` table. `setState(...)` sets one server's administrative state. Everything the algorithm needs from HAProxy is these three methods.

### `deploy/RuntimeBalancer.java`

```java
package io.plainround.seatwave.balancer.deploy;

import io.plainround.seatwave.balancer.haproxy.NamedBalancer;
import io.plainround.seatwave.balancer.haproxy.RuntimeCommand;
import io.plainround.seatwave.balancer.haproxy.StatTable;
import java.io.IOException;
import java.util.Objects;

/**
 * One HAProxy reached through its runtime API. Thread-safe: it holds only immutable fields.
 *
 * @param balancer the balancer and its API address
 */
record RuntimeBalancer(NamedBalancer balancer) implements BalancerControl {

  /** Checks the balancer. */
  RuntimeBalancer {
    Objects.requireNonNull(balancer, "balancer");
  }
```

`RuntimeBalancer` is a record with one field, the `NamedBalancer`, and `implements BalancerControl` means it provides the three methods. The compact constructor checks for null.

```java
  @Override
  public String name() {
    return balancer.name();
  }

  @Override
  public StatTable stat() throws IOException {
    return balancer.api().showStat();
  }

  @Override
  public void setState(String backend, String server, RuntimeCommand.AdminState state)
      throws IOException {
    balancer.api().setState(backend, server, state);
  }
}
```

Each method passes the call to the runtime API client: `balancer.api().showStat()` and `balancer.api().setState(...)`. `@Override` tells the compiler "this method comes from the interface", so a typo in the name is an error instead of a new method.

### `deploy/DockerCompose.java`

```java
package io.plainround.seatwave.balancer.deploy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@code docker compose -p <project> stop|start <service>} as a child process, with a fixed
 * argument list (never a shell string), so a service name cannot become a second command.
 * Thread-safe: it holds only immutable fields.
 */
final class DockerCompose implements Containers {

  // The command's own wait on top of docker's stop timeout: pulling nothing, starting a container.
  private static final Duration COMMAND_MARGIN = Duration.ofSeconds(30);

  private final String project;
  private final Duration stopTimeout;

  DockerCompose(String project, Duration stopTimeout) {
    this.project = Objects.requireNonNull(project, "project");
    this.stopTimeout = Objects.requireNonNull(stopTimeout, "stopTimeout");
  }
```

`DockerCompose` runs the command `docker compose -p <project> stop|start <service>`. The Javadoc makes a security point: the command is a fixed list of arguments, never a text given to a shell. If a service name were pasted into a shell string, a name such as `x; rm -rf ~` could become a second command. With a list, each item is one argument whatever it contains.

`COMMAND_MARGIN` is 30 seconds. The comment explains it: it is added to Docker's own stop timeout, to allow for starting a container. There are two fields: the Compose project name and the stop timeout. The constructor stores them after null checks.

```java
  @Override
  public void stop(String service) throws IOException {
    run(
        List.of("stop", "-t", Long.toString(stopTimeout.toSeconds()), service),
        stopTimeout.plus(COMMAND_MARGIN));
  }

  @Override
  public void start(String service) throws IOException {
    run(List.of("start", service), COMMAND_MARGIN);
  }
```

`stop` builds the arguments `stop -t 30 seatwave-2`. `-t` is Docker's timeout in whole seconds; `Long.toString(stopTimeout.toSeconds())` writes it as text. The limit for the whole command is the stop timeout plus the margin. `start` is `start seatwave-2` with the margin as its limit.

```java
  private void run(List<String> action, Duration limit) throws IOException {
    List<String> command = new ArrayList<>(List.of("docker", "compose", "-p", project));
    command.addAll(action);
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    try {
      if (!process.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new IOException(String.join(" ", command) + " did not finish within " + limit);
      }
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (process.exitValue() != 0) {
        throw new IOException(
            String.join(" ", command) + " exited " + process.exitValue() + ": " + output.strip());
      }
    } catch (InterruptedException interrupted) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IOException(String.join(" ", command) + " was interrupted", interrupted);
    }
  }
```

`run` does the real work.

- `new ArrayList<>(List.of("docker", "compose", "-p", project))` starts the argument list with the program name and the project flag. `-p` names the Compose project, so the command can touch nothing else. `command.addAll(action)` adds the rest.
- `new ProcessBuilder(command).redirectErrorStream(true).start()` starts a child **process**, a new program run by this one. `redirectErrorStream(true)` merges the error output into the normal output, so one read gets both.
- `process.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)` waits for the process to end, and returns `false` if the time ran out. Then the code calls `destroyForcibly()` to kill the child and throws an `IOException` that includes the full command and the limit.
- On time, the code reads everything the process printed with `readAllBytes()` and turns it into text. It reads after the process has ended. That is safe for `docker compose stop` and `start`, which print a few short lines. A command that printed more than the operating system's pipe buffer (commonly 64 KiB) would block forever waiting for a reader, and the timeout would then fire.
- A nonzero **exit status** means failure by the convention of every command-line program: 0 is success. The exception message contains the command, the status and Docker's own output.
- `catch (InterruptedException interrupted)`: waiting can be interrupted by another thread. The code kills the child, calls `Thread.currentThread().interrupt()` to set the thread's interrupted flag again (so code further up can still see it), and throws an `IOException`.

This class is not covered by any unit test: Docker is not something the project owns, and the unit tests never start a child process. Its behavior was seen only in the README's captured run.

### `deploy/DeploySettings.java`

```java
package io.plainround.seatwave.balancer.deploy;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.balancer.config.SettingsReader;
import io.plainround.seatwave.balancer.haproxy.NamedBalancer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A rolling deploy's target and limits, read from a settings file with no defaults.
 *
 * @param project the Compose project, the only one the deploy may touch
 * @param instances the Compose services to restart, one at a time, in order
 * @param backends the HAProxy backends that hold their own copy of each server (backends that only
 *     track another are left alone: a state set on the tracked server reaches them)
 * @param balancers every balancer, with its runtime API on this machine
 * @param drainTimeout the longest wait for a drained server's sessions to reach zero
 * @param stopTimeout what {@code docker compose stop -t} gives the instance before SIGKILL; longer
 *     than the instance's own drain deadline
 * @param upTimeout the longest wait for the restarted instance to pass its checks again
 * @param settle the wait after an instance is back, so slow start has ramped it before the next
 * @param poll how often the balancers are read while waiting
 * @param auditFile where one line per change is appended
 */
public record DeploySettings(
    String project,
    List<String> instances,
    List<String> backends,
    List<NamedBalancer> balancers,
    Duration drainTimeout,
    Duration stopTimeout,
    Duration upTimeout,
    Duration settle,
    Duration poll,
    Path auditFile) {
```

A record with ten fields. The Javadoc explains each. Two details matter. `backends` lists only the backends that hold their own copy of each server. `stopTimeout` must be longer than the instance's own drain deadline. `Duration` is Java's type for a length of time, and `Path` is a file path.

```java
  private static final Pattern PROJECT = Pattern.compile("seatwave-balancer");
  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_-]{0,63}");
  private static final Pattern ENTRY = Pattern.compile("[a-z0-9=.:-]{1,64}");
  // Any path a file system accepts, spaces and long folders included, but no control character
  // (a newline would let a value end one line of a log and start another).
  private static final Pattern FILE = Pattern.compile("[^\\p{Cntrl}]{1,4096}");
  private static final int MAX_WAIT_MILLIS = 600_000;
  private static final int MIN_POLL_MILLIS = 10;
  private static final int RUNTIME_API_TIMEOUT_MILLIS = 2_000;

  /** Copies the lists and checks the fields. */
  public DeploySettings {
    Objects.requireNonNull(project, "project");
    instances = List.copyOf(instances);
    backends = List.copyOf(backends);
    balancers = List.copyOf(balancers);
    Objects.requireNonNull(drainTimeout, "drainTimeout");
    Objects.requireNonNull(stopTimeout, "stopTimeout");
    Objects.requireNonNull(upTimeout, "upTimeout");
    Objects.requireNonNull(settle, "settle");
    Objects.requireNonNull(poll, "poll");
    Objects.requireNonNull(auditFile, "auditFile");
  }
```

The patterns restrict what a settings file may say.

- `PROJECT` allows exactly one text, `seatwave-balancer`. The deploy can touch no other Compose project, even if the file is edited.
- `NAME` is a lowercase letter followed by up to 63 letters, digits, `_` or `-`: the shape of a Compose service or HAProxy backend name.
- `ENTRY` is the characters of a `name=address:port` item, so a list item cannot hold anything else.
- `FILE` is any text of one to 4,096 characters without a control character. `[^\p{Cntrl}]` means "not a control character". The comment gives the reason: a newline in a path would let one value end a line of a log and start another. Spaces and long folders are allowed, which Module 9's settings did not allow.
- The `MAX_WAIT_MILLIS` limit is 600,000 (ten minutes), `MIN_POLL_MILLIS` is 10, and `RUNTIME_API_TIMEOUT_MILLIS` is 2,000 for each runtime API command.

The compact constructor makes unchangeable copies of the three lists and rejects null values.

```java
  /**
   * Reads the settings, reporting every missing or invalid key together.
   *
   * @param reader the settings reader
   * @return the settings, or one line per problem
   */
  public static Result<DeploySettings, List<String>> from(SettingsReader reader) {
    Objects.requireNonNull(reader, "reader");
    String project = reader.textMatching("compose.project", PROJECT);
    List<String> instances = reader.listMatching("deploy.instances", NAME);
    List<String> backends = reader.listMatching("deploy.backends", NAME);
    List<String> entries = reader.listMatching("deploy.balancers", ENTRY);
    Duration drain = reader.millisBetween("deploy.drainTimeoutMillis", 1, MAX_WAIT_MILLIS);
    Duration stop = reader.millisBetween("deploy.stopTimeoutMillis", 1, MAX_WAIT_MILLIS);
    Duration up = reader.millisBetween("deploy.upTimeoutMillis", 1, MAX_WAIT_MILLIS);
    Duration settle = reader.millisBetween("deploy.settleMillis", 0, MAX_WAIT_MILLIS);
    Duration poll = reader.millisBetween("deploy.pollMillis", MIN_POLL_MILLIS, MAX_WAIT_MILLIS);
    String audit = reader.textMatching("deploy.auditFile", FILE);
    if (stop.toSeconds() < 1) {
      reader.addProblem(
          "deploy.stopTimeoutMillis must be at least 1000: docker counts whole seconds");
    }
    if (instances.size() != instances.stream().distinct().count()) {
      reader.addProblem("deploy.instances names an instance twice");
    }
```

`from` receives a `SettingsReader`, a helper that reads typed values from a `.properties` file and collects one sentence per mistake instead of stopping at the first. It returns `Result<DeploySettings, List<String>>`: the settings, or the list of problems.

The keys, with the lab's values from `config/deploy.properties`:

```properties
# Lab 3's rolling deploy, run on this machine (./gradlew -q deploy --args='config/deploy.properties').
# The only project it touches; Compose service names are also the balancers' server names.
compose.project=seatwave-balancer
deploy.instances=seatwave-1,seatwave-2,seatwave-3
# Backends with their own copy of each server; the others track seatwave and follow it.
deploy.backends=seatwave,seatwave_active_only
# Both balancers' runtime APIs, published on 127.0.0.1 only (compose.yaml).
deploy.balancers=lb1=127.0.0.1:21005,lb2=127.0.0.1:21015
deploy.drainTimeoutMillis=10000
# Longer than the instance's drain.deadlineMillis (20 s), so docker never kills a draining instance.
deploy.stopTimeoutMillis=30000
deploy.upTimeoutMillis=30000
# HAProxy's slowstart (10 s): the next instance waits until this one carries its full share.
deploy.settleMillis=10000
deploy.pollMillis=100
deploy.auditFile=build/stack/audit.log

```

(`#` lines are comments in a properties file; the tool that counts quoted lines skips them.) The first line of each pair that matters:

- `compose.project=seatwave-balancer` is matched by the pattern above.
- `deploy.instances` and `deploy.backends` are comma-separated lists of names. The names of Compose services are also the names HAProxy uses for the servers (`compose.yaml` and `haproxy.cfg` agree on that), which is why one list serves both.
- `deploy.balancers=lb1=127.0.0.1:21005,lb2=127.0.0.1:21015` gives each balancer's runtime API as the host sees it. Docker publishes the container port 9999 of `lb1` to `127.0.0.1:21005` and of `lb2` to `21015`. Only the local machine can reach them.
- The time settings are in milliseconds. `drainTimeoutMillis=10000` bounds the wait for sessions to reach zero. `stopTimeoutMillis=30000` is passed to Docker. `upTimeoutMillis=30000` bounds the waits for down and for up. `settleMillis=10000` equals HAProxy's `slowstart 10s`. `pollMillis=100` is how often the balancers are read while waiting.
- `deploy.auditFile=build/stack/audit.log` is where the audit lines go. The same file collects the lines of the `scripts/` tools.

Each call such as `reader.millisBetween("deploy.drainTimeoutMillis", 1, MAX_WAIT_MILLIS)` returns a `Duration` between the two limits, or records a problem. If the key is missing or wrong, it returns the minimum so that the code can go on and collect more problems. That has a side effect, shown next.

```java
    Optional<List<NamedBalancer>> balancers = Optional.empty();
    if (!entries.isEmpty()) {
      switch (NamedBalancer.parse(entries, Duration.ofMillis(RUNTIME_API_TIMEOUT_MILLIS))) {
        case Result.Success<List<NamedBalancer>, String>(List<NamedBalancer> parsed) ->
            balancers = Optional.of(parsed);
        case Result.Error<List<NamedBalancer>, String>(String reason) ->
            reader.addProblem("deploy.balancers: " + reason);
      }
    }
    if (!reader.problems().isEmpty() || balancers.isEmpty()) {
      return Result.error(reader.problems());
    }
    return Result.success(
        new DeploySettings(
            project,
            instances,
            backends,
            balancers.get(),
            drain,
            stop,
            up,
            settle,
            poll,
            Path.of(audit)));
  }
```

Two cross-checks follow the reads. First, Docker counts the stop timeout in whole seconds, so `stop.toSeconds() < 1` (less than 1,000 ms) is a problem. Second, `instances` must not name an instance twice (`distinct().count()` counts the different names).

A caveat I confirmed by running it in a scratch copy. If `deploy.stopTimeoutMillis` is missing, the reader has already recorded "is missing" and returned the minimum, 1 ms. The check `stop.toSeconds() < 1` then fires too, and the user sees a second line, "must be at least 1000", that is a consequence of the first. The output was:

```text
Error[error=[deploy.stopTimeoutMillis is missing, deploy.stopTimeoutMillis must be at least 1000: docker counts whole seconds]]
```

It is a harmless extra line. The same thing happens in `HarnessSettings` with the connect and request timeouts.

The `switch` is the pattern-matching form. `NamedBalancer.parse(...)` returns a `Result`, which has exactly two cases. `case Result.Success<List<NamedBalancer>, String>(List<NamedBalancer> parsed)` matches the success case and takes the record apart, so `parsed` is the list. `case Result.Error<...>(String reason)` matches the other, and the code adds a problem line that starts with the key name. The compiler checks that both cases are handled.

At the end, if any problem exists (or the balancers did not parse), the method returns `Result.error(reader.problems())`. Otherwise it builds the record. `Path.of(audit)` turns the file text into a `Path`.

### `deploy/RollingDeploy.java`

```java
package io.plainround.seatwave.balancer.deploy;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.balancer.haproxy.RuntimeCommand.AdminState;
import io.plainround.seatwave.balancer.haproxy.StatRow;
import io.plainround.seatwave.balancer.haproxy.StatTable;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Restarts the SeatWave instances one at a time without failing a request: for each one, drain it
 * on every balancer, wait until it has no session left, stop it (it then drains itself too), start
 * it, set it ready again, wait until every balancer's checks see it up, and let slow start ramp it
 * before the next one.
 *
 * <p>The state of a balancer lives in that balancer: a drain set on one HAProxy is not seen by the
 * other, so every step is applied to every balancer of the pair. Not thread-safe: one deploy at a
 * time, which is also the rule for the infrastructure it changes.
 */
final class RollingDeploy {
```

The Javadoc is the algorithm in prose, and the second paragraph repeats the rule about two balancers. "Not thread-safe" means two threads must not use one `RollingDeploy` at once; the program runs one deploy at a time anyway, and so does the infrastructure it changes.

```java
  private static final double NANOS_PER_MILLI = 1_000_000.0;

  private final DeploySettings settings;
  private final List<BalancerControl> balancers;
  private final Containers containers;
  private final PrintStream log;
  private final long startNanos = System.nanoTime();

  RollingDeploy(
      DeploySettings settings,
      List<BalancerControl> balancers,
      Containers containers,
      PrintStream log) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.balancers = List.copyOf(balancers);
    this.containers = Objects.requireNonNull(containers, "containers");
    this.log = Objects.requireNonNull(log, "log");
  }
```

`NANOS_PER_MILLI` is used to print times. The fields are the settings, the balancers (as `BalancerControl`, so tests can pass their own), the container runner, and a `PrintStream` for the progress lines (`System.out` in real use). `startNanos` records the moment the object was created, from `System.nanoTime()`, a clock that only moves forward and is meant for measuring durations. The constructor takes an unchangeable copy of the balancer list.

```java
  /**
   * Checks that every instance is up everywhere, then restarts them one by one.
   *
   * @return the number of instances restarted, or what stopped the deploy; on a failure the
   *     instance in progress is left drained or stopped, never sent traffic it cannot serve
   * @throws InterruptedException if the deploy is interrupted while it waits
   */
  Result<Integer, String> run() throws InterruptedException {
    Optional<String> notUp = firstNotUp();
    if (notUp.isPresent()) {
      return Result.error("check before: " + notUp.get() + "; nothing was changed");
    }
    for (String instance : settings.instances()) {
      Result<Integer, String> step = restart(instance);
      if (step instanceof Result.Error<Integer, String> failed) {
        return failed;
      }
    }
    Optional<String> afterwards = firstNotUp();
    return afterwards.isPresent()
        ? Result.error("check after: " + afterwards.get())
        : Result.success(settings.instances().size());
  }
```

`run` returns a `Result<Integer, String>`: the number of instances restarted, or a sentence that says what stopped the deploy.

- First it asks `firstNotUp()` whether every instance is up on every balancer. If not, it returns an error that ends in "nothing was changed". The deploy refuses to start from a half-broken state, because taking one more server out of a service that already lost one could leave too little capacity.
- Then it loops over the instances in the order of the settings and calls `restart`. `step instanceof Result.Error<Integer, String> failed` is a **type pattern**: if `step` is the error case, `failed` names it as that type, and the method returns it unchanged.
- After the loop it checks again that everything is up ("check after") and returns the count.

`throws InterruptedException` is declared because waiting can be interrupted.

```java
  private Result<Integer, String> restart(String instance) throws InterruptedException {
    try {
      setEverywhere(instance, AdminState.DRAIN);
      say(instance + ": drain set on " + balancers.size() + " balancer(s)");
      if (!waitFor(instance, row -> row.currentSessions() == 0, settings.drainTimeout())) {
        return Result.error(instance + " still had sessions after " + settings.drainTimeout());
      }
      say(instance + ": no session left on any balancer");
      containers.stop(instance);
      say(instance + ": stopped (SIGTERM; the instance drained itself)");
      // Wait until every balancer's checks have seen it down. Started sooner, a quick restart can
      // go unnoticed: the balancer would then send full traffic at once to a cold instance, with no
      // slow start, possibly before it is ready.
      if (!waitFor(instance, row -> row.status().startsWith("DOWN"), settings.upTimeout())) {
        return Result.error(instance + " was never seen down within " + settings.upTimeout());
      }
      say(instance + ": seen down by every balancer's checks");
      containers.start(instance);
      say(instance + ": started");
      setEverywhere(instance, AdminState.READY);
      say(instance + ": ready set; the checks decide when it gets traffic again");
      if (!waitFor(instance, StatRow::isUp, settings.upTimeout())) {
        return Result.error(instance + " did not pass its checks within " + settings.upTimeout());
      }
      say(instance + ": up on every balancer; slow start ramps its weight");
      Thread.sleep(settings.settle());
      return Result.success(1);
    } catch (IOException failure) {
      return Result.error(instance + ": " + failure.getMessage());
    }
  }
```

`restart` runs the steps below in order, and prints one message per step.

1. `setEverywhere(instance, AdminState.DRAIN)` drains the instance on every balancer and in both backends.
2. `waitFor(instance, row -> row.currentSessions() == 0, settings.drainTimeout())` waits until the row for this server shows 0 current sessions in every balancer and backend. The second argument is a **lambda**: the test applied to each row. If the time runs out the deploy stops with an error and does not stop the instance, so it never kills running requests.
3. `containers.stop(instance)` sends SIGTERM through Docker and returns when the container has ended.
4. `waitFor(... startsWith("DOWN") ...)` waits for every balancer to report `DOWN`. The long comment gives the reason. The wait uses `upTimeout` (an odd name for this use, but it is the same bound).
5. `containers.start(instance)` starts it.
6. `setEverywhere(instance, AdminState.READY)` clears the drain, so the health checks decide again.
7. `waitFor(instance, StatRow::isUp, ...)` waits until all balancers show it up. `StatRow::isUp` is a **method reference**: the same as `row -> row.isUp()`.
8. `Thread.sleep(settings.settle())` pauses for the settle time so slow start can ramp the instance's weight before the next instance is drained.

`catch (IOException failure)` handles an error from Docker or from a balancer and turns it into `Result.error(instance + ": " + failure.getMessage())`. An `InterruptedException` is not caught here: it travels up to the caller.

On any failure the instance in progress stays drained, stopped, or not yet up. HAProxy sends no traffic to a server in `DRAIN` or `DOWN`, so a failed deploy does not send requests to something that cannot serve them.

```java
  private void setEverywhere(String instance, AdminState state) throws IOException {
    for (BalancerControl balancer : balancers) {
      for (String backend : settings.backends()) {
        balancer.setState(backend, instance, state);
      }
    }
  }
```

`setEverywhere` applies one state to every server entry that belongs to the instance: for each balancer, for each backend in `deploy.backends`, one `set server` command.

```java
  private boolean waitFor(String instance, Predicate<StatRow> condition, Duration limit)
      throws IOException, InterruptedException {
    long deadlineNanos = System.nanoTime() + limit.toNanos();
    while (true) {
      if (holdsEverywhere(instance, condition)) {
        return true;
      }
      if (System.nanoTime() > deadlineNanos) {
        return false;
      }
      Thread.sleep(settings.poll());
    }
  }
```

`waitFor` is a polling loop. It computes a deadline from `System.nanoTime()` plus the limit, then repeats: if the condition holds everywhere, return `true`; if the deadline has passed, return `false`; otherwise sleep for the poll time and try again. Checking the condition before the deadline means a condition that is already true never fails because the limit was tiny.

```java
  private boolean holdsEverywhere(String instance, Predicate<StatRow> condition)
      throws IOException {
    for (BalancerControl balancer : balancers) {
      StatTable table = balancer.stat();
      for (String backend : settings.backends()) {
        Optional<StatRow> row = table.server(backend, instance);
        if (row.isEmpty() || !condition.test(row.get())) {
          return false;
        }
      }
    }
    return true;
  }
```

`holdsEverywhere` reads each balancer's table once and checks the condition on the row of this instance in each backend. If a row is missing (`row.isEmpty()`) the answer is "not yet" and the loop goes on. It returns `false` at the first row that does not satisfy the condition. A `Predicate<StatRow>` is a function that takes a row and answers true or false.

```java
  private Optional<String> firstNotUp() {
    for (BalancerControl balancer : balancers) {
      StatTable table;
      try {
        table = balancer.stat();
      } catch (IOException unreachable) {
        return Optional.of(balancer.name() + " does not answer: " + unreachable.getMessage());
      }
      for (String backend : settings.backends()) {
        for (String instance : settings.instances()) {
          Optional<StatRow> row = table.server(backend, instance);
          if (row.isEmpty()) {
            return Optional.of(balancer.name() + " has no server " + backend + "/" + instance);
          }
          if (!row.get().isUp()) {
            return Optional.of(
                balancer.name() + " sees " + backend + "/" + instance + " " + row.get().status());
          }
        }
      }
    }
    return Optional.empty();
  }
```

`firstNotUp` is the check before and after. For each balancer it reads the table. If the balancer does not answer, the message says so. For each backend and each instance it requires the row to exist and `isUp()` to be true, and the first thing that is not satisfied becomes the message, for example `lb1 sees seatwave/seatwave-2 DOWN`. Since `isUp()` is true for `UP 1/3`, a server that has just failed its first checks counts as up here. That is a small gap in the pre-check, not a hazard: the drain-then-stop path would handle the instance anyway.

```java
  private void say(String message) {
    double elapsedMillis = (System.nanoTime() - startNanos) / NANOS_PER_MILLI;
    log.println(String.format(Locale.ROOT, "+%9.1f ms  %s", elapsedMillis, message));
  }
}
```

`say` prints a progress line with the elapsed time since the object was created. `String.format(Locale.ROOT, "+%9.1f ms  %s", ...)` writes a plus sign, the time in milliseconds in 9 characters with one decimal, and the message. This is the format of the lines in the README, for example `+     31.5 ms  seatwave-1: drain set on 2 balancer(s)`. `Locale.ROOT` keeps the decimal point a dot on every computer.

### `deploy/DeployCli.java`

```java
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
```

The Javadoc lists the exit statuses. A **command-line program** returns a number to whoever started it: 0 means success. Here 1 means the deploy stopped (or was not confirmed), and 2 means bad usage or invalid settings. `private DeployCli() {}` is a private constructor: nobody creates an object of this class, it only has static methods.

```java
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
```

`main` is where Java starts a program. It calls `run` with the real standard input, standard output, standard error, the UTC clock, and `DockerCompose::new` as the factory for the container runner. `System.exit(status)` is only called for a nonzero status. All logic is in `run`, so tests can call it with their own streams and a fake clock.

```java
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
```

`run` has the order of events.

- Between one and three arguments are allowed: the settings file, then optionally `--run` and `--yes`. Anything else prints the usage line to `err` and returns 2.
- `List.of(arguments).subList(1, arguments.length)` takes the flags. `allMatch(...)` checks that each flag is one of the two known flags.
- `SettingsFiles.load(Path.of(arguments[0]), DeploySettings::from, err)` reads the file, builds the settings, and prints every problem to `err`. If it returns an empty `Optional`, the program returns 2.
- `printPlan(...)` always runs. Without `--run`, the program prints "dry run: nothing changed. Add --run to do it." and returns 0.
- With `--run` and without `--yes`, `confirmed(in, out)` asks the question. A "no" prints "not confirmed; nothing changed" and returns 1.
- Otherwise it wraps each balancer in a `RuntimeBalancer` (`.<BalancerControl>map(RuntimeBalancer::new)` states the result type of `map` explicitly, so the list has the interface type), creates the container runner from the factory, and runs the deploy.

```java
  private static int deploy(
      RollingDeploy rolling,
      DeploySettings settings,
      PrintStream out,
      PrintStream err,
      Clock clock) {
    try {
      audit(settings, clock, "deploy: started on " + settings.instances());
      Result<Integer, String> result = rolling.run();
      return switch (result) {
        case Result.Success<Integer, String>(Integer restarted) -> {
          audit(settings, clock, "deploy: finished, " + restarted + " instances restarted");
          out.println("deploy finished: " + restarted + " instances restarted, all up");
          yield 0;
        }
        case Result.Error<Integer, String>(String reason) -> {
          audit(settings, clock, "deploy: stopped: " + reason);
          err.println("deploy stopped: " + reason);
          yield EXIT_FAILED;
        }
      };
    } catch (IOException cannotAudit) {
      err.println("deploy stopped: the audit file cannot be written: " + cannotAudit.getMessage());
      return EXIT_FAILED;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      err.println("deploy interrupted");
      return EXIT_FAILED;
    }
  }
```

`deploy` wraps the algorithm in the audit.

- It writes the "started" audit line first. If that write fails, nothing has been changed yet.
- `rolling.run()` returns the `Result`. A `switch` expression takes the two cases apart, writes the "finished" or "stopped" audit line, prints the message and **yields** the exit status (0 or 1). `yield` gives the value of a `case` block in a `switch` expression.
- The audit calls can throw `IOException`, so one `catch` handles that. The text it prints is "deploy stopped: the audit file cannot be written". An `InterruptedException` prints "deploy interrupted", sets the interrupted flag again and returns 1.

One message in this method is misleading, and I confirmed it by running a probe in a scratch copy (a fake balancer, fake containers, and an audit file made read-only after the first line). The audit "finished" line is written after the deploy has finished. If that write fails, the user sees `deploy stopped: the audit file cannot be written` and the status is 1, although every instance was restarted and is up. The probe's output:

```text
exit 1
out: +     17.8 ms  seatwave-1: up on every balancer; slow start ramps its weight
err: deploy stopped: the audit file cannot be written: <path>
audit lines: 1
```

The wording should say that the deploy finished and only the audit line was lost.

```java
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
```

`printPlan` prints two lines: which instances, which project, which balancers and backends, then the steps and the settle time. The strings are joined with `+`; Java prints a `List` as `[a, b]`. The README's dry run shows exactly these two lines.

```java
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
```

`confirmed` prints the question, flushes the output so it appears before the program waits, and reads one line from standard input. Only an exact `y` counts. Anything else, an empty line, or a closed input (`readLine` returns `null`) is a "no". Since the question defaults to no (`[y/N]`), a pipe from `/dev/null` can never start a deploy.

```java
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
```

`audit` appends one line to the audit file: the time from the clock, a space, and the text. It first creates the folder if the path has one (`getParent()` is null for a file in the current folder). `Files.writeString(..., CREATE, APPEND)` creates the file if it is missing and adds to the end if it exists, so earlier lines are never lost. Every line ends with `\n`.

```java
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
    Containers create(String project, java.time.Duration stopTimeout);
  }
}
```

`ContainersFactory` is a **functional interface**: it has exactly one method, so a lambda or a method reference can be used where it is expected. It exists so that `DeployCliTest` can give a runner that records calls instead of running Docker. `java.time.Duration` is written in full here because the file does not import `Duration`.

## Try it yourself

Run these in the repository folder. Nothing here needs Docker, and none of it touches a balancer. Timings differ on every run.

**1. The dry run.** This prints the plan and changes nothing.

```bash
./gradlew -q deploy --args='config/deploy.properties'
```

```text
plan: restart [seatwave-1, seatwave-2, seatwave-3] of project seatwave-balancer one at a time, on balancers [lb1, lb2], backends [seatwave, seatwave_active_only]
  each: drain, wait for 0 sessions, stop (SIGTERM), wait until seen down, start, ready, wait until up, settle 10000 ms
dry run: nothing changed. Add --run to do it.
```

**2. A flag the program does not know.** Gradle reports exit status 2 as a failed build; the line before that comes from the program.

```bash
./gradlew -q deploy --args='config/deploy.properties --now' 2>&1 | head -n 1
./gradlew -q deploy --args='config/nothing.properties' 2>&1 | head -n 1
```

```text
usage: deploy <deploy.properties> [--run] [--yes]
the settings file config/nothing.properties does not exist
```

**3. Run the tests of the package.**

```bash
./gradlew test --tests 'io.plainround.seatwave.balancer.deploy.*' --console=plain | grep -E 'PASSED|FAILED|BUILD'
```

```text
DeployCliTest > printsThePlanAndChangesNothingByDefault() PASSED
DeployCliTest > changesNothingWithoutAYes() PASSED
DeployCliTest > refusesBadUsage() PASSED
DeployCliTest > deploysAfterConfirmationAndWritesTheAudit() PASSED
RollingDeployTest > stopsWhenTheBalancerDoesNotAnswer() PASSED
RollingDeployTest > stopsWhenTheRestartedInstanceNeverComesUp() PASSED
RollingDeployTest > restartsEachInstanceInOrderOnEveryBalancer() PASSED
RollingDeployTest > changesNothingWhenAnInstanceIsNotUpBeforehand() PASSED
RollingDeployTest > stopsWhenTheDrainedInstanceKeepsItsSessions() PASSED
BUILD SUCCESSFUL in 1s
```

**4. Prove a test is weak.** In a scratch copy, open `RollingDeploy.java` and change the line `if (!waitFor(instance, row -> row.status().startsWith("DOWN"), settings.upTimeout())) {` to `if (false) {`. This removes the wait for "seen down", the step the comment calls essential. Run command 3 again. All nine tests still pass (I ran this change). The tests' fake containers mark the instance `DOWN` the moment `stop` is called, so no test notices if the deploy never waits for it. Undo the change.

## Mistakes people make

- **A deploy that restarts first and tells the balancer later.** Requests fail in the gap. This tool drains first, waits for zero sessions, and only then stops.
- **Restarting faster than the balancer can notice.** The balancer never marks the server down, so slow start never applies and the full share lands on a cold instance. This tool waits until every balancer shows `DOWN`.
- **Treating one balancer's view as the truth.** With a redundant pair, each balancer has its own state. This tool applies each step to both and waits for both.
- **A shell string for a command.** A name pasted into a shell string can carry a second command. `DockerCompose` passes an argument list and the settings restrict every name by a pattern.
- **A destructive command that runs by default.** This tool is a dry run until `--run`, asks `[y/N]`, and touches only the project `seatwave-balancer`.
- **Giving Docker a shorter stop timeout than the drain deadline.** Docker would send SIGKILL in the middle of a drain. `deploy.stopTimeoutMillis` (30 s) is longer than `drain.deadlineMillis` (20 s).
- **Waiting forever.** Every wait has a limit, and when it passes the deploy stops with a message instead of hanging.

## Check yourself

1. Why does the deploy wait for the sessions of a drained instance to reach zero before it stops it? *Answer: Requests already running on the instance would otherwise be cut off. A drain only stops new requests.*
2. What is the instance doing during the time between SIGTERM and the end of the process? *Answer: It drains itself: it admits no new request and waits for running requests, up to its own deadline of 20 seconds.*
3. Why must every step be applied to both balancers? *Answer: Each HAProxy keeps its own server states; a drain on one is not seen by the other.*
4. What does the exit status 2 mean, and what does 1 mean? *Answer: 2 is bad usage or invalid settings; 1 is a stopped deploy or a refused confirmation.*
5. What does `DockerCompose` do if `docker compose stop` takes longer than its limit? *Answer: It kills the child process and throws an `IOException`, which `RollingDeploy` turns into a failed deploy.*

## Further reading

- Docker Compose CLI reference, `docker compose stop` and `docker compose start`: https://docs.docker.com/reference/cli/docker/compose/
- HAProxy 3.4 Configuration Manual, `slowstart`, `track`, and the server states: https://docs.haproxy.org/3.4/configuration.html
- HAProxy 3.4 Management Guide, `set server ... state`: https://docs.haproxy.org/3.4/management.html
- Java SE 25 API: `java.lang.ProcessBuilder`, `java.nio.file.Files`, `java.time.Clock`: https://docs.oracle.com/en/java/javase/25/docs/api/
- JEP 441, pattern matching for `switch`: https://openjdk.org/jeps/441
