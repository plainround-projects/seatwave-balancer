# The `harness` package, part 1: the settings, the two value types and the balancer watch

This is part 1 of 2. Part 1 reads the files that describe a load run before it happens and the thread that watches a balancer while it runs: `Outcome`, `StateChange`, `HarnessSettings` and `BalancerWatch`. Part 2 (`java-harness-walkthrough-2.md`) reads the files that send the requests, turn the results into the report and start everything: `LoadRun`, `RunReport` and `HarnessCli`.

## What it is

The `harness` package is a **load generator** with a built-in **observer**. A load generator is a program that sends many requests to a server on purpose, so that you can see what the server does under pressure. The observer reads the load balancer's own view of its servers during the run.

One run does this, in this order:

1. Read a settings file (`config/kill-production.properties`, for example).
2. Start one **watch** per balancer. A watch is a thread that asks the balancer for its table of servers ten times a second and writes down every change of a server's status.
3. Send requests at a fixed rate, for example 3,000 per second, for a fixed time. Record what happened to each: answered, failed, or never sent.
4. Print one report: how many requests were answered and failed, which instance answered, how long the middle request and the slowest one percent took, when the balancer took a killed server out, and the longest stretch with no answer at all.

The harness changes nothing. Kills, stops and drains come from other tools (`scripts/lab.sh` and the `deploy` package) that run beside it. The harness is what you read the effect with.

The package has seven files. This part covers four of them:

- `Outcome` is what happened to one request.
- `StateChange` is one change of one server's status in one balancer.
- `HarnessSettings` holds and checks the settings.
- `BalancerWatch` is the thread that watches a balancer.

## Why this repository uses it

Module 10 makes measurable claims: a kill behind active health checks costs thousands of failed requests and about 11 seconds; the same kill behind the production backend costs none and about 50 milliseconds; a rolling deploy costs none; killing the active balancer leaves a gap of a few seconds. A claim needs a measurement that anyone can repeat. The harness is that measurement. The README prints its output for each lab, and `measuring-a-balancer.md` explains how to read it.

Three rules shape the package:

- **Open loop.** The harness starts requests at the planned moments whatever the answers do. A **closed-loop** tester sends the next request only after the last one finished, so when the server stalls the tester slows down too and the stall hides itself. An **open-loop** tester keeps the rate, so a stalled server shows up as failures and as latency. The Javadoc calls the hidden stall the "coordinated omission" trap.
- **No defaults.** Every setting is read from the file, and a missing key is an error. Every value is checked against a pattern or a range.
- **Times in nanoseconds since time zero.** Every request and every status change is stamped on one clock, so they can be compared.

## Where it appears

| File | What it does |
|---|---|
| `harness/Outcome.java` | One request's result: when it was due, when it ended, answered, failed or not sent. |
| `harness/StateChange.java` | One server's status change as one balancer's watch saw it. |
| `harness/HarnessSettings.java` | Reads and checks a harness settings file. |
| `harness/BalancerWatch.java` | The thread that reads `show stat` at a fixed interval and records changes. |
| `config/*.properties` | The settings files: `share-*.properties`, `kill-*.properties`, `stop-*.properties`, `deploy-*.properties`, `pair.properties`. |
| `scripts/lab.sh` | Starts the harness inside the lab network and does the disruption at a fixed time. |
| `compose.yaml` | Defines the `harness` service (address 10.210.10.30 in `ARCHITECTURE.md`). |
| `build.gradle.kts` | The Gradle task `load` runs `HarnessCli` on the host. |

## How it works underneath

**Time zero.** A run has a **warm-up** and a **measured part**. The warm-up (5 seconds in the lab files) sends the same requests, so that the Java program's code is compiled to machine code and the connections are open. Time zero is the end of the warm-up. The report counts only requests that started at time zero or later. Warm-up requests carry negative times.

**The clock.** `System.nanoTime()` returns a number of nanoseconds (billionths of a second) from an arbitrary starting point, and it only moves forward. It is meant for measuring durations. The harness fixes time zero as one such number and stamps everything as "nanoseconds since time zero".

**Which kinds of result there are.** A request can be answered (a response below status 500), failed (a response of 500 or more, a timeout, or a broken connection), or not sent (the harness had too many requests in flight and did not start this one). Status 409 ("the seat is taken") is an answer, not a failure: the service did what it should.

**The two clocks of one watch.** Each watch asks one balancer, over the runtime API (see `java-haproxy-client-walkthrough.md`), every `watch.intervalMillis` (100 ms in the lab files). A server's status changes seldom, so the watch remembers the last status of every server and writes a `StateChange` only when the status differs. When the balancer does not answer, the watch records the status `UNREACHABLE` for the pseudo server `*`. That is how the pair lab sees a killed balancer.

**One thread per watch.** A balancer that is dead makes each reading wait for its connect timeout (1 second in the lab files). If one thread read both balancers, the live balancer would be read only once a second during the failure. So each balancer has its own thread.

**Java words used in this part.**

| Word | Meaning |
|---|---|
| `record` | A class that only holds values; the compiler writes the constructor, the getters (called as `name()`), `equals` and `toString`. |
| compact constructor | The part of a record written without a parameter list. It runs before the fields are set and may reject bad values. |
| `enum` | A type with a fixed list of values. |
| `Pattern` | A compiled regular expression: a text that describes which strings are allowed. |
| `Duration` | A length of time. |
| `Runnable` | Something with a `run()` method that a thread can execute. |
| `Thread` | One line of execution inside the program. Several run at the same time. |
| `CountDownLatch` | A gate: threads wait at it until it has been counted down to zero. |
| `Optional<T>` | A box that holds a value or is empty. |
| `Result<T, E>` | From the `result4j` library: `Success` holds a `T`, `Error` holds an `E`. |

## Walkthrough of this repository's use

### `harness/Outcome.java`

```java
package io.plainround.seatwave.balancer.harness;

import java.util.Objects;
```

`package` names the group this file belongs to. `Objects` is the helper class with null checks.

```java
/**
 * What happened to one request of a load run, with times in nanoseconds since the run began.
 *
 * @param sentNanos when the request was started
 * @param doneNanos when its answer or its failure arrived; equal to {@code sentNanos} when it was
 *     never sent
 * @param kind whether it was answered, failed or never sent
 * @param status the HTTP status, or 0 when there was none
 * @param servedBy the {@code X-Served-By} header: which SeatWave instance answered, or empty
 * @param balancer the {@code X-Balancer} header: which balancer passed it on, or empty
 * @param failure why it failed: {@code HTTP 503} or the client's exception, or empty
 */
public record Outcome(
    long sentNanos,
    long doneNanos,
    Kind kind,
    int status,
    String servedBy,
    String balancer,
    String failure) {
```

The record has seven fields. The Javadoc lists them. Times are `long` (a 64-bit whole number) in nanoseconds since time zero. `status` is the HTTP status number, or 0 when there was none. `servedBy` and `balancer` are the values of two response headers: `X-Served-By`, set by SeatWave, names the instance that answered; `X-Balancer`, set by HAProxy, names the balancer that passed it on. `failure` is a short text: `HTTP 503`, or the name and message of the exception.

One detail of meaning: `sentNanos` is documented as "when the request was started", but `LoadRun` fills it with the moment the request was **due**. If the harness is late in starting a request, the latency still counts from the planned moment. This is deliberate (see `java-harness-walkthrough-2.md`).

```java
  // Every status from 500 up is a failure the fan sees; 4xx answers (409 seat taken) are answers.
  private static final int FIRST_SERVER_ERROR = 500;

  /** Checks the fields. */
  public Outcome {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(servedBy, "servedBy");
    Objects.requireNonNull(balancer, "balancer");
    Objects.requireNonNull(failure, "failure");
    if (doneNanos < sentNanos) {
      throw new IllegalArgumentException("a request cannot end before it starts");
    }
  }
```

`FIRST_SERVER_ERROR` is 500. The comment states the rule: every status from 500 up is a failure the user sees; 4xx answers such as 409 are answers. The compact constructor requires the fields to be present and refuses a request that ends before it begins, because that would be a bug in the harness.

```java
  /**
   * Classifies a response.
   *
   * @param sentNanos when the request started
   * @param doneNanos when the response arrived
   * @param status its status
   * @param servedBy its {@code X-Served-By}
   * @param balancer its {@code X-Balancer}
   * @return answered below 500, failed from 500 up
   */
  static Outcome response(
      long sentNanos, long doneNanos, int status, String servedBy, String balancer) {
    boolean isFailure = status >= FIRST_SERVER_ERROR;
    return new Outcome(
        sentNanos,
        doneNanos,
        isFailure ? Kind.FAILED : Kind.ANSWERED,
        status,
        servedBy,
        balancer,
        isFailure ? "HTTP " + status : "");
  }
```

`response` is a **static factory method** for a request that got an HTTP response. It decides the kind with one comparison, `status >= FIRST_SERVER_ERROR`. The ternary `isFailure ? Kind.FAILED : Kind.ANSWERED` means "if `isFailure` then `FAILED`, otherwise `ANSWERED`". For a failure the text is `"HTTP " + status`, which is how `HTTP 503` appears in the report.

```java
  /**
   * Records a request that got no response.
   *
   * @param sentNanos when it started
   * @param doneNanos when the client gave up
   * @param failure why
   * @return a failed outcome
   */
  static Outcome failure(long sentNanos, long doneNanos, String failure) {
    return new Outcome(sentNanos, doneNanos, Kind.FAILED, 0, "", "", failure);
  }
```

`failure` is for a request that got no response at all (a timeout, a refused connection, a connection that closed before any byte). The status is 0 and the two header fields are empty.

```java
  /**
   * Records a request that was due but not sent, because the in-flight limit was reached.
   *
   * @param dueNanos when it was due
   * @return a not-sent outcome
   */
  static Outcome notSent(long dueNanos) {
    return new Outcome(dueNanos, dueNanos, Kind.NOT_SENT, 0, "", "", "");
  }
```

`notSent` is for a request that was due while too many requests were in flight. Its sent and done times are both the due time.

```java
  /**
   * Returns the request's latency.
   *
   * @return done minus sent, in nanoseconds
   */
  long latencyNanos() {
    return doneNanos - sentNanos;
  }
```

`latencyNanos` is done minus sent. For an answered request it is the time the user waited, counted from the due moment.

```java
  /** The three things that can happen to a request. */
  public enum Kind {
    /** A response below 500. */
    ANSWERED,
    /** A response from 500 up, a timeout, or a broken connection. */
    FAILED,
    /** Due, but the in-flight limit was reached. */
    NOT_SENT
  }
}
```

The three kinds as an **enum**.

### `harness/StateChange.java`

```java
package io.plainround.seatwave.balancer.harness;

import java.util.Objects;

/**
 * A server's status changed in one balancer's view, as the watch saw it.
 *
 * @param atNanos when the watch read the new status, in nanoseconds since the run began
 * @param balancer the balancer's name
 * @param server the server's name, or {@code *} when the balancer itself did not answer
 * @param from the status before
 * @param to the status now
 */
public record StateChange(long atNanos, String balancer, String server, String from, String to) {

  /** Checks the fields. */
  public StateChange {
    Objects.requireNonNull(balancer, "balancer");
    Objects.requireNonNull(server, "server");
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
  }
```

A `StateChange` says: at this time, in this balancer, this server went from this status to that one. `server` is `*` when the balancer itself did not answer. The compact constructor checks for null.

```java
  /**
   * Says whether this change took the server out of the balancer's rotation.
   *
   * @return true for a change to DOWN, MAINT or DRAIN from a status that was not one of those
   */
  boolean isRemoval() {
    return isOut(to) && !isOut(from);
  }

  private static boolean isOut(String status) {
    return status.startsWith("DOWN") || status.startsWith("MAINT") || status.startsWith("DRAIN");
  }
}
```

`isRemoval()` is true when the change took the server out of rotation: it went to `DOWN`, `MAINT` or `DRAIN` from something that was not one of those. `startsWith` is used, not equality, because HAProxy writes transitions such as `DOWN 1/2` and `DRAIN 2/3`. The report uses this to find the moment a balancer took a killed server out.

### `harness/HarnessSettings.java`

```java
package io.plainround.seatwave.balancer.harness;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.balancer.config.SettingsReader;
import io.plainround.seatwave.balancer.haproxy.NamedBalancer;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
```

The imports. `URI` is a web address as an object. `InetAddress` is an internet address as an object; it is used to check the target's address. `Matcher` holds the result of applying a `Pattern` to a text, including the parts the pattern marked. `Optional` and `Result` were explained above.

```java
/**
 * One load run and what to watch during it, read from a settings file with no defaults.
 *
 * @param label the run's name, printed first and used in every Idempotency-Key it sends
 * @param target the balancer address requests go to, such as {@code http://10.210.10.21:8080}
 * @param ratePerSecond requests started per second, whatever the answers do (an open loop)
 * @param warmup how long requests are started before time zero; they warm the JIT compilers and
 *     open the connections, and the report leaves them out
 * @param duration how long requests are started after time zero, the measured part
 * @param holdPercent the share of requests that are {@code POST /v1/holds}; the rest read a hold
 * @param maxInFlight requests allowed in flight at once; a request due beyond it is counted as not
 *     sent, so a stuck target shows up instead of slowing the load down
 * @param connectTimeout the client's connect timeout
 * @param requestTimeout the client's whole-request timeout, a fan's app giving up
 * @param seed the start of the seat sequence, so two runs ask for the same seats
 * @param balancers the balancers whose runtime API is read during the run
 * @param backend the backend whose servers are watched
 * @param watchInterval how often the balancers are read
 * @param victimName the instance a kill removes, for the report's analysis, or an empty string
 * @param timelineStep the width of one timeline row
 */
public record HarnessSettings(
    String label,
    URI target,
    int ratePerSecond,
    Duration warmup,
    Duration duration,
    int holdPercent,
    int maxInFlight,
    Duration connectTimeout,
    Duration requestTimeout,
    long seed,
    List<NamedBalancer> balancers,
    String backend,
    Duration watchInterval,
    String victimName,
    Duration timelineStep) {
```

The record has fifteen fields, one per setting. The Javadoc explains each. Four deserve a word:

- `ratePerSecond` is the number of requests **started** per second, whatever the answers do.
- `maxInFlight` is the most requests allowed to be running at once. A request due beyond the limit is counted as "not sent". This keeps a stuck target from making the harness run out of memory, and it shows up in the report instead of silently slowing the load.
- `seed` is the start of a sequence of seat numbers, so two runs ask for the same seats.
- `victimName` is the instance whose removal the report analyses. An empty string means there is none.

```java
  private static final Pattern LABEL = Pattern.compile("[a-z0-9-]{1,32}");
  // Only plain HTTP to a dotted IPv4 literal of the lab network or this machine, with a port from 1
  // to 65,535 written without a leading zero; the address is checked again once it is parsed.
  private static final Pattern TARGET = Pattern.compile("http://([0-9.]{7,15}):([1-9][0-9]{0,4})");
  private static final int HIGHEST_PORT = 65_535;
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  private static final Pattern ENTRY = Pattern.compile("[a-z0-9=.:-]{1,64}");
  private static final String NO_VICTIM = "none";
  // Module 1's peak was 13,333 holds and 20,000 seat maps per second; the lab stays below that.
  private static final int MAX_RATE = 20_000;
  private static final int MAX_IN_FLIGHT = 10_000;
  private static final int MAX_DURATION_MILLIS = 600_000;
  private static final int MAX_TIMEOUT_MILLIS = 60_000;
  private static final int MIN_WATCH_MILLIS = 10;
  private static final int PERCENT = 100;
  private static final int MAX_SEED = 1_000_000;
```

Each setting is limited before any request is sent.

- `LABEL` is one to 32 characters out of lowercase letters, digits and `-`. The label starts the report and is part of every Idempotency-Key the run sends.
- `TARGET` is `http://`, then 7 to 15 characters of digits and dots, then `:` and a port of one to five digits whose first digit is 1 to 9 (`[1-9][0-9]{0,4}`), so a port of 0 or one with a leading zero, such as `09999`, does not match. Only plain HTTP, and only an address of digits: the comment says so. A host name cannot be given, so the harness cannot be pointed at a name by mistake. The round brackets mark two groups, the address and the port, which `isExactTarget` below reads; the comment says the address is checked again once it is parsed.
- `HIGHEST_PORT` is 65,535, the largest port number.
- `NAME` is for backend and instance names. `ENTRY` is for the list items of `watch.balancers`.
- The numbers are limits: at most 20,000 requests per second (Module 1's peak was 13,333 holds and 20,000 seat maps per second), at most 10,000 in flight, runs of up to ten minutes, timeouts up to a minute, a watch interval of at least 10 ms, and a seed up to 1,000,000.

```java
  /** Copies the list and checks the fields. */
  public HarnessSettings {
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(warmup, "warmup");
    Objects.requireNonNull(duration, "duration");
    Objects.requireNonNull(connectTimeout, "connectTimeout");
    Objects.requireNonNull(requestTimeout, "requestTimeout");
    balancers = List.copyOf(balancers);
    Objects.requireNonNull(backend, "backend");
    Objects.requireNonNull(watchInterval, "watchInterval");
    Objects.requireNonNull(victimName, "victimName");
    Objects.requireNonNull(timelineStep, "timelineStep");
  }
```

The compact constructor makes an unchangeable copy of the balancer list and rejects missing values.

```java
  /**
   * Reads the settings, reporting every missing or invalid key together.
   *
   * @param reader the settings reader
   * @return the settings, or one line per problem
   */
  public static Result<HarnessSettings, List<String>> from(SettingsReader reader) {
    Objects.requireNonNull(reader, "reader");
    String label = reader.textMatching("run.label", LABEL);
    String target = reader.textMatching("target.url", TARGET);
    int rate = reader.intBetween("load.ratePerSecond", 1, MAX_RATE);
    Duration warmup = reader.millisBetween("load.warmupMillis", 0, MAX_DURATION_MILLIS);
    Duration duration = reader.millisBetween("load.durationMillis", 1, MAX_DURATION_MILLIS);
    int holdPercent = reader.intBetween("load.holdPercent", 0, PERCENT);
    int maxInFlight = reader.intBetween("load.maxInFlight", 1, MAX_IN_FLIGHT);
    int problemsBeforeTimeouts = reader.problems().size();
    Duration connect = reader.millisBetween("load.connectTimeoutMillis", 1, MAX_TIMEOUT_MILLIS);
    Duration request = reader.millisBetween("load.requestTimeoutMillis", 1, MAX_TIMEOUT_MILLIS);
    // The rule between the two keys is checked only when both were read, so a missing key gives
    // one problem line, not a second one about the stand-in values.
    boolean timeoutsRead = reader.problems().size() == problemsBeforeTimeouts;
    int seed = reader.intBetween("load.seed", 0, MAX_SEED);
    List<String> entries = reader.listMatching("watch.balancers", ENTRY);
    String backend = reader.textMatching("watch.backend", NAME);
    Duration watch =
        reader.millisBetween("watch.intervalMillis", MIN_WATCH_MILLIS, MAX_TIMEOUT_MILLIS);
    String victim = reader.textMatching("report.victim", NAME);
    Duration step = reader.millisBetween("report.timelineMillis", 1, MAX_DURATION_MILLIS);
    if (!target.isEmpty() && !isExactTarget(target)) {
      reader.addProblem(
          "target.url must be http:// with a dotted IPv4 address and a port from 1 to 65535, such"
              + " as http://10.210.10.21:8080, but was "
              + target);
    }
    if (timeoutsRead && request.compareTo(connect) <= 0) {
      reader.addProblem(
          "load.requestTimeoutMillis must be longer than load.connectTimeoutMillis, or no request"
              + " that needs a new connection can finish");
    }
    Optional<List<NamedBalancer>> balancers = Optional.empty();
    if (!entries.isEmpty()) {
      switch (NamedBalancer.parse(entries, connect)) {
        case Result.Success<List<NamedBalancer>, String>(List<NamedBalancer> parsed) ->
            balancers = Optional.of(parsed);
        case Result.Error<List<NamedBalancer>, String>(String reason) ->
            reader.addProblem("watch.balancers: " + reason);
      }
    }
    if (!reader.problems().isEmpty() || balancers.isEmpty()) {
      return Result.error(reader.problems());
    }
    return Result.success(
        new HarnessSettings(
            label,
            URI.create(target),
            rate,
            warmup,
            duration,
            holdPercent,
            maxInFlight,
            connect,
            request,
            seed,
            balancers.get(),
            backend,
            watch,
            victim.equals(NO_VICTIM) ? "" : victim,
            step));
  }
```

`from` reads one value per key and collects problems. Look at a real settings file to see the keys. This is the pair lab:

```properties
# Lab 3: load to the floating address while the balancer that holds it is killed (scripts/lab.sh
# pair reads which one holds it first).
run.label=pair
target.url=http://10.210.10.100:8080
load.ratePerSecond=3000
# 5 s of warm-up first, left out of the report: JIT compilers and the client's connections.
load.warmupMillis=5000
load.durationMillis=40000
load.holdPercent=50
watch.balancers=lb1=10.210.10.21:9999,lb2=10.210.10.22:9999
watch.backend=seatwave
report.victim=none
# Client timeouts of a fan's app: 1 s to connect, 5 s for the whole request.
load.connectTimeoutMillis=1000
load.requestTimeoutMillis=5000
load.maxInFlight=4000
load.seed=41
watch.intervalMillis=100
report.timelineMillis=1000
```

- `run.label`, `target.url`: the name, and the address that requests go to. Here `10.210.10.100:8080` is the **floating address** that moves between the two balancers; the kill labs use `10.210.10.21` (`lb1`) with port 8080 (production backend) or 8082 (active checks only).
- `load.ratePerSecond=3000`, `load.warmupMillis=5000`, `load.durationMillis=40000`: 3,000 requests per second for 40 seconds after 5 seconds of warm-up. In total the run plans 135,000 requests.
- `load.holdPercent=50`: half of the requests are `POST /v1/holds` (make a hold), the other half read a hold made earlier.
- `watch.balancers=lb1=10.210.10.21:9999,lb2=10.210.10.22:9999`: the two runtime APIs, as the harness sees them from inside the lab network (the harness container runs there, so it uses port 9999 directly, not the published host ports used by the deploy).
- `watch.backend=seatwave`: which backend's servers to watch.
- `report.victim=none`: the pair lab kills a balancer, not an instance, so there is no victim instance. The first comment of the file says which balancer: the one that holds the floating address, which `scripts/lab.sh pair` reads before it kills. `kill-production.properties` has `report.victim=seatwave-2`.
- `load.connectTimeoutMillis=1000` and `load.requestTimeoutMillis=5000`: the timeouts of a fan's app: one second to connect, five for the whole request.
- `load.maxInFlight=4000`, `load.seed=41`, `watch.intervalMillis=100`, `report.timelineMillis=1000`: the in-flight limit, the seat sequence start, the watch interval and the width of one row of the timeline.

The code calls `reader.textMatching`, `reader.intBetween`, `reader.millisBetween` and `reader.listMatching` once per key. Each records a problem for a missing or invalid value instead of stopping, so the user sees every mistake at once.

After the reads come two cross-checks.

The first is the address. The pattern `TARGET` only checks the shape, so `isExactTarget` (explained below) checks the parsed address and the port. `!target.isEmpty()` skips the check when the key was missing or did not match the pattern: `textMatching` then returned an empty string and has already recorded one problem line, and a second line about the same key would only repeat it. The message names the expected form and gives an example.

The second is the timeouts: the request timeout must be longer than the connect timeout. The message says why: otherwise no request that needs a new connection can finish. This rule runs only when `timeoutsRead` is true. `problemsBeforeTimeouts` is the number of problems before the two timeout keys are read, and `timeoutsRead` compares it with the number after. If a timeout key was missing or out of range, the reader recorded a problem and returned a stand-in value (the minimum, 1 ms). Comparing two stand-in values would add a second, misleading line ("must be longer than"), so the rule is skipped and the user sees one line per real mistake. Version 1.0.0 printed that extra line.

The `switch` over the `Result` of `NamedBalancer.parse` is a **pattern-matching switch**: `case Result.Success<...>(List<NamedBalancer> parsed)` matches the success case and takes the record apart so that `parsed` is the list; `case Result.Error<...>(String reason)` matches the other case and adds a problem line. The compiler checks that both cases are handled. Note `NamedBalancer.parse(entries, connect)`: the runtime API client gets the same timeout as the load's connect timeout.

If there is any problem, or no balancers, the method returns `Result.error(reader.problems())`. Otherwise it builds the record. `URI.create(target)` turns the checked text into an address object, and `victim.equals(NO_VICTIM) ? "" : victim` turns the word `none` into an empty string.

```java
  private static boolean isExactTarget(String target) {
    Matcher parts = TARGET.matcher(target);
    if (!parts.matches() || Integer.parseInt(parts.group(2)) > HIGHEST_PORT) {
      return false;
    }
    // ofLiteral also reads short forms such as 127.1; only the form that prints back the same is
    // the address the reader meant.
    try {
      return InetAddress.ofLiteral(parts.group(1)).getHostAddress().equals(parts.group(1));
    } catch (IllegalArgumentException notAnAddress) {
      return false;
    }
  }
```

`isExactTarget` checks what the pattern alone cannot.

- `TARGET.matcher(target)` applies the pattern again and keeps its two **groups**: the parts of the pattern in round brackets. `parts.group(1)` is the address, such as `10.210.10.21`, and `parts.group(2)` is the port, such as `8080`.
- `Integer.parseInt(parts.group(2)) > HIGHEST_PORT` refuses a port above 65,535. The pattern allows up to five digits, so `99999` would pass it; a port is a 16-bit number, and 65,535 is the largest. Port 0 and a leading zero such as `09999` never reach this line: the pattern's `[1-9]` refuses them.
- `InetAddress.ofLiteral(...)` (new in Java 22) turns the text into an address without asking any name server. It throws `IllegalArgumentException` when the text is not an address literal, such as `999.1.1.1`, and the `catch` turns that into `false`.
- `ofLiteral` also accepts short forms from old network software: `127.1` means `127.0.0.1`, and `127.0.1` also reads as an address. The comment explains the fix: `getHostAddress()` prints the address back in full dotted form, and only text that prints back the same is the address the reader meant. So `127.0.1` is refused instead of quietly becoming a different address. Version 1.0.0 accepted these short and padded forms.

```java
  /**
   * Returns the instance the report analyses as removed.
   *
   * @return the name, or empty when the settings say {@code none}
   */
  public Optional<String> victim() {
    return victimName.isEmpty() ? Optional.empty() : Optional.of(victimName);
  }
```

`victim()` gives the name back as an `Optional`, empty when there is none.

```java
  /**
   * Returns the number of requests the run starts, warm-up included.
   *
   * @return rate times warm-up plus duration, rounded down
   */
  public long plannedRequests() {
    return Math.multiplyExact((long) ratePerSecond, warmup.plus(duration).toMillis())
        / Duration.ofSeconds(1).toMillis();
  }
}
```

`plannedRequests` is the number of requests the run starts, warm-up included: rate times (warm-up plus duration) in milliseconds, divided by 1,000. `Math.multiplyExact` throws if the product overflows a `long`, instead of silently wrapping around. For the pair lab: 3,000 times 45,000 divided by 1,000 is 135,000.

### `harness/BalancerWatch.java`

```java
package io.plainround.seatwave.balancer.harness;

import io.plainround.seatwave.balancer.haproxy.NamedBalancer;
import io.plainround.seatwave.balancer.haproxy.StatRow;
import io.plainround.seatwave.balancer.haproxy.StatTable;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
```

The imports include `CountDownLatch` and `TimeUnit`, which the class uses to wait.

```java
/**
 * Reads one balancer's {@code show stat} at a fixed interval during a run and records each change
 * of a watched server's status, with the time on the run's clock. It also keeps the first and the
 * last table it read, so the report can show how many retries the balancer made during the run. One
 * watch per balancer, each on its own thread, so a dead balancer's timeouts never delay the reading
 * of the live one.
 *
 * <p>Thread-safe: {@link #run} is the only writer and runs on one thread; the getters may be called
 * only after {@link #stop} has returned, which waits for that thread.
 */
final class BalancerWatch implements Runnable {

  private static final String UNREACHABLE = "UNREACHABLE";
  private static final String ANY_SERVER = "*";

  private final NamedBalancer balancer;
  private final String backend;
  private final Duration interval;
  private final long startNanos;
  private final CountDownLatch stopRequested = new CountDownLatch(1);
  private final CountDownLatch finished = new CountDownLatch(1);
  private final Map<String, String> lastStatus = new HashMap<>();
  private final List<StateChange> changes = new ArrayList<>();
  // The first table read, then the latest one: two at most, so the retry counters can be compared.
  private final List<StatTable> firstAndLast = new ArrayList<>();

  BalancerWatch(NamedBalancer balancer, String backend, Duration interval, long startNanos) {
    this.balancer = Objects.requireNonNull(balancer, "balancer");
    this.backend = Objects.requireNonNull(backend, "backend");
    this.interval = Objects.requireNonNull(interval, "interval");
    this.startNanos = startNanos;
  }
```

The class `implements Runnable`: it can be given to a thread. The Javadoc says: one watch per balancer, each on its own thread. It also says when it is safe to read the results: only after `stop()` has returned. Writes by the watch thread are seen by the reader because `stop()` waits on a latch that the thread counts down last, and a latch counted down by one thread and awaited by another gives a guarantee that everything written before the count-down is visible after the await.

The fields:

- `balancer` and `backend`: which balancer and which backend.
- `interval` and `startNanos`: how often to read, and time zero.
- `stopRequested` and `finished`: two **latches** with a count of 1. A `CountDownLatch` is a gate. `await()` blocks until `countDown()` has been called. `stopRequested` tells the thread to end. `finished` tells the caller the thread has ended.
- `lastStatus`: a map from `balancer/server` to the last status seen.
- `changes`: the list of `StateChange` records, in the order seen.
- `firstAndLast`: the first table read and the latest one, so the report can subtract the counters. A list holding at most two items.

The constructor stores its arguments after null checks.

```java
  @Override
  public void run() {
    try {
      do {
        readOnce();
      } while (!stopRequested.await(interval.toNanos(), TimeUnit.NANOSECONDS));
      // One last reading after the load ended, so the retry counters include its last requests.
      readOnce();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } finally {
      finished.countDown();
    }
  }
```

`run` is what the thread executes.

- A `do { ... } while (...)` loop runs the body at least once. The body is `readOnce()`.
- The condition `!stopRequested.await(interval.toNanos(), TimeUnit.NANOSECONDS)` is a trick. `await` with a time waits until the latch opens or the time passes. It returns `true` if the latch opened and `false` if it timed out. So, while the thread has not been asked to stop, the call waits one interval and returns `false`, and `!false` keeps the loop going. When `stop()` opens the latch, the wait ends at once and the loop ends. The watch thus sleeps in steps of one interval and still reacts at once to a stop request.
- After the loop, one more `readOnce()` runs. The comment says why: so the retry counters include the last requests of the load.
- `catch (InterruptedException ...)`: another thread may interrupt a waiting thread. The thread sets its interrupted flag again and ends.
- `finally { finished.countDown(); }` runs whatever happens, so `stop()` can never wait forever for a thread that has ended.

```java
  /**
   * Asks the watch to end and waits for its last reading.
   *
   * @throws InterruptedException if the wait is interrupted
   */
  void stop() throws InterruptedException {
    stopRequested.countDown();
    finished.await();
  }
```

`stop()` opens `stopRequested` and then waits on `finished`. When it returns, the thread has done its last reading.

```java
  /**
   * Returns the status changes in the order they were seen.
   *
   * @return the changes; call only after {@link #stop}
   */
  List<StateChange> changes() {
    return List.copyOf(changes);
  }

  /**
   * Returns the balancer's name.
   *
   * @return the name
   */
  String balancerName() {
    return balancer.name();
  }
```

`changes()` returns an unchangeable copy of the list, and `balancerName()` returns the name. Only call `changes()` after `stop()`.

```java
  /**
   * Returns the watched backend's summary row as first and last read; call only after {@link
   * #stop}.
   *
   * @return both rows, or empty if the balancer never answered
   */
  Optional<List<StatRow>> backendRows() {
    if (firstAndLast.isEmpty()) {
      return Optional.empty();
    }
    Optional<StatRow> before = firstAndLast.getFirst().backendSummary(backend);
    Optional<StatRow> after = firstAndLast.getLast().backendSummary(backend);
    return before.isPresent() && after.isPresent()
        ? Optional.of(List.of(before.get(), after.get()))
        : Optional.empty();
  }
```

`backendRows()` returns the summary row (`BACKEND`) of the watched backend as read first and as read last, or empty if the balancer never answered or did not list the backend. The report subtracts the two to say how many requests passed, how many were retried and how many were moved to another server during the run.

```java
  private void readOnce() {
    try {
      StatTable table = balancer.api().showStat();
      // Stamped when the answer arrived: a status read then was true at that moment, while the
      // moment the question was sent can be up to one read earlier than the change.
      long atNanos = System.nanoTime() - startNanos;
      if (firstAndLast.size() < 2) {
        firstAndLast.add(table);
      } else {
        firstAndLast.set(1, table);
      }
      record(atNanos, balancer.name(), ANY_SERVER, "ANSWERING");
      for (StatRow row : table.servers(backend)) {
        record(atNanos, balancer.name(), row.server(), row.status());
      }
    } catch (IOException silent) {
      // A balancer that does not answer is exactly what the pair lab kills; it is a status here,
      // stamped when the read gave up.
      record(System.nanoTime() - startNanos, balancer.name(), ANY_SERVER, UNREACHABLE);
    }
  }
```

`readOnce` takes one reading.

- `balancer.api().showStat()` asks the balancer for its table and waits for the answer.
- `atNanos` is computed only **after** the answer arrived, on the run's clock. The comment says why. A status in the answer was true at the moment the answer came back. The moment the question was sent can be up to one read earlier than the change, so a stamp taken then would put the change too early. (Version 1.0.0 stamped the reading when it started; a removal could then look up to one reading earlier than it was.)
- The table is kept: the first one is added to `firstAndLast`, and later ones replace the second slot with `set(1, table)`.
- `record(..., ANY_SERVER, "ANSWERING")` notes that the balancer itself answered. The server named `*` stands for the balancer.
- The loop notes the status of each server of the watched backend.
- If the balancer does not answer (`IOException`, which covers refused connections, timeouts and a malformed answer), the watch records `UNREACHABLE` for `*`. The comment says it is a status here, because killing a balancer is what the pair lab does. That reading is stamped with `System.nanoTime() - startNanos` at the moment the read gave up, which for a dead balancer is up to one connect timeout after the question was sent.

```java
  private void record(long atNanos, String balancer, String server, String status) {
    String key = balancer + "/" + server;
    String before = lastStatus.put(key, status);
    if (before == null) {
      // The first reading is the starting point, not a change, unless it already says unreachable.
      if (status.equals(UNREACHABLE)) {
        changes.add(new StateChange(atNanos, balancer, server, "NOT READ", status));
      }
      return;
    }
    if (!before.equals(status)) {
      changes.add(new StateChange(atNanos, balancer, server, before, status));
    }
  }
}
```

`record` decides whether something changed. It builds a key `balancer/server` and calls `lastStatus.put(key, status)`. The `put` method of a map returns the **previous** value, or `null` if there was none.

- If there was none, this is the first reading of that key. It is a starting point, not a change, unless the status is `UNREACHABLE`: a balancer that is already dead at the first reading is reported as a change from `NOT READ`.
- If the previous status differs from the new one, a `StateChange` is added.

This is why the report shows only changes (for example `UP -> UP 2/3 -> UP 1/3 -> DOWN`), not ten lines per second.

## Try it yourself

Run these in the repository folder. Nothing here needs Docker. Timings differ on every run.

**1. Run the tests of the package.** The harness tests start a stand-in web server and a stand-in runtime API on the loopback address. The package `harness` has 27 tests: 1 in `BalancerWatchTest`, 5 in `HarnessCliTest`, 7 in `LoadRunTest` and 14 in `RunReportTest`.

```bash
./gradlew test --tests 'io.plainround.seatwave.balancer.harness.*' --console=plain | grep -E 'PASSED|FAILED|BUILD'
```

```text
BalancerWatchTest > readsOnceMoreAfterTheLoadEndedSoTheCountersIncludeItsLastRequests() PASSED
HarnessCliTest > runsTheLoadWatchesTheBalancerAndReports() PASSED
HarnessCliTest > namesABadBalancerEntry() PASSED
HarnessCliTest > refusesATargetWithAShortAddressOrAPortOutOfRange() PASSED
HarnessCliTest > reportsAMissingTimeoutOnceWithoutTheRuleThatNeedsIt() PASSED
HarnessCliTest > refusesBadUsageAndInvalidSettings() PASSED
LoadRunTest > sendsExactlyTheHoldShareTheSettingsAskFor() PASSED
LoadRunTest > startsTheMeasuredPartAtTimeZeroAtARateThatDoesNotDivideASecond() PASSED
LoadRunTest > keepsOneOutcomePerPlannedRequestWhenAReplyHoldsNoUsableHoldId() PASSED
LoadRunTest > countsAFaultOfTheHarnessItselfAsAFailedRequest() PASSED
LoadRunTest > asksForEverySeatOnceInTwentyThousandHoldRequests() PASSED
LoadRunTest > countsEveryRequestToADeadTargetAsFailedWithItsReason() PASSED
LoadRunTest > readsTheHoldsItMade() PASSED
RunReportTest > showsTheBalancersOwnRetriesDuringTheRun() PASSED
RunReportTest > countsAFailureSentAtTheMomentOfRemovalAsSentInBetween() PASSED
RunReportTest > printsTheMixThatReallyRan() PASSED
RunReportTest > readsTheMedianAndTheP99ByNearestRank() PASSED
RunReportTest > leavesTheWarmUpOutOfEveryNumber() PASSED
RunReportTest > findsTheLongestGapBetweenAnswersAndTheFailuresInsideIt() PASSED
RunReportTest > ignoresARemovalBeforeTheLastAnswerAndSaysSo() PASSED
RunReportTest > saysNoFailuresAndAllFailuresDifferently() PASSED
RunReportTest > printsALittlesLawLineWhoseFactorsMultiplyOut() PASSED
RunReportTest > measuresDetectionFromTheVictimsLastAnswerToItsRemoval() PASSED
RunReportTest > takesOnlyAChangeIntoTheOutStatesAsARemoval() PASSED
RunReportTest > countsEveryStatusBelowFiveHundredAsAnAnswerAndFromFiveHundredAsAFailure() PASSED
RunReportTest > hasNoKillAnalysisWithoutAVictim() PASSED
RunReportTest > countsTrafficSharePerInstanceAndPerBalancer() PASSED
BUILD SUCCESSFUL in 3s
```

The time on the last line differs on every run.

**2. A settings file with a mistake.** Make a copy in a scratch folder (`build/` is ignored by git), remove two timeouts, and run the harness. The harness reads the file, prints every problem, and exits with status 2 before it sends anything. Gradle reports that as a failed build; `head` keeps the program's own lines.

```bash
mkdir -p build/try && grep -v 'TimeoutMillis' config/pair.properties > build/try/bad.properties
./gradlew -q load --args='build/try/bad.properties' 2>&1 | head -n 3
```

```text
the settings in build/try/bad.properties have 2 problem(s):
  load.connectTimeoutMillis is missing
  load.requestTimeoutMillis is missing
```

There are two lines, one per missing key. The cross-check between the timeouts did not run, because `timeoutsRead` was false (described above).

**3. An address in a short form.** `127.0.1` matches the pattern, since it is seven characters of digits and dots, but it is not written in full. `isExactTarget` refuses it.

```bash
sed 's#^target.url=.*#target.url=http://127.0.1:8080#' config/pair.properties > build/try/short.properties
./gradlew -q load --args='build/try/short.properties' 2>&1 | head -n 2
```

```text
the settings in build/try/short.properties have 1 problem(s):
  target.url must be http:// with a dotted IPv4 address and a port from 1 to 65535, such as http://10.210.10.21:8080, but was http://127.0.1:8080
```

Change the port to `09999` and the pattern itself refuses the line, with the message `target.url must match http://([0-9.]{7,15}):([1-9][0-9]{0,4}), but was http://127.0.1:09999`. Only one line is printed, because `isExactTarget` runs only for a target the pattern accepted.

**4. A whole run on your own computer.** The `measuring-a-balancer.md` note has a small stand-in that plays three SeatWave instances and a HAProxy runtime API on the loopback address. With it you can run `./gradlew -q load --args=build/try/toy.properties` and watch the report appear, with a kill at a known moment. Its output is shown and read there.

## Mistakes people make

- **Measuring with a closed loop and calling it load.** The tester slows down when the server stalls, so the stall hides. This harness is open-loop.
- **Counting the warm-up.** The first seconds of a Java program run slower. The harness sends a warm-up first and leaves it out of every number.
- **Measuring latency from the moment the client got round to it.** If the client is late, a stall is hidden. Here a request's latency counts from when it was due.
- **Treating every non-200 as a failure.** A 409 for a taken seat is the service doing its job. Here only status 500 and above, timeouts and broken connections are failures.
- **One thread watching all the balancers.** A dead balancer's timeouts delay the reading of the live one. Here each balancer has its own thread.
- **Trusting a number without checking its source.** Every harness setting passes a pattern or a range, and a missing key is an error.

## Check yourself

1. What is the difference between an open-loop and a closed-loop load, and which one is this? *Answer: Open-loop starts requests at planned times whatever the answers do; closed-loop sends the next request after the last one finishes. This is open-loop.*
2. Is a 409 response a failure in the report? *Answer: No. A response below 500 is an answer.*
3. Why is each balancer watched on its own thread? *Answer: A dead balancer makes each reading wait for the connect timeout; sharing a thread would slow the reading of the live one.*
4. What does the `put` method of a map return, and how does `record` use it? *Answer: The previous value or null. A null means this is the first reading, a starting point; a different previous value means a change.*
5. Why does `from` check that the request timeout is longer than the connect timeout? *Answer: A request that needs a new connection spends the connect time inside the request time; with the request timeout not longer, it could never finish.*

## Further reading

- Java SE 25 API: `java.util.concurrent.CountDownLatch`, `java.lang.System.nanoTime`, `java.time.Duration`: https://docs.oracle.com/en/java/javase/25/docs/api/
- JEP 440 (record patterns) and JEP 441 (pattern matching for `switch`): https://openjdk.org/jeps/440 and https://openjdk.org/jeps/441
- HAProxy 3.4 Management Guide, "Statistics and monitoring": https://docs.haproxy.org/3.4/management.html
- Java Language Specification, Java SE 25, "Records": https://docs.oracle.com/javase/specs/jls/se25/html/
