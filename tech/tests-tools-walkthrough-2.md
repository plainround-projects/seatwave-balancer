# The tests of the tools, part 2: the harness and its report

This is part 2 of 2. Part 1 (`tests-tools-walkthrough-1.md`) explained what a test is, what a fake is, and read the tests of the `haproxy` and `deploy` packages. Part 2 reads the two test classes of the `harness` package: `HarnessCliTest` (a whole run against stand-ins on your own computer) and `RunReportTest` (the arithmetic of the report on hand-made data). Together they have 11 tests.

## What it is

`HarnessCliTest` is an **integration test**: it runs a whole program, the real settings reader, the real load generator, the real watch thread, the real report, against two stand-ins on the loopback address. One stand-in plays SeatWave behind a balancer (a small web server). The other plays HAProxy's runtime API (`FakeBalancer`, explained in `tests-tools-walkthrough-1.md`). Nothing outside the computer is involved.

`RunReportTest` is a set of **unit tests**: each builds a few `Outcome` and `StateChange` objects by hand, with times in seconds that are easy to add up, and checks one number of the report. Because `RunReport` takes plain objects and returns plain text, it needs no network at all.

## Why this repository uses it

The README's numbers come from the report code, and the labs that produce them need Docker and take minutes. These tests check the same arithmetic in seconds, anywhere. They also protect two promises: the warm-up never counts, and a mistake in a settings file is explained and stops the run before any request is sent.

This note is also frank about what the tests do not prove. In a scratch copy I broke the code on purpose in several places, and some changes passed all tests. They are named below, because a reader who trusts the report should know which parts are checked by a test and which parts only by reading.

## Where it appears

| File | What it tests |
|---|---|
| `harness/HarnessCliTest.java` | `HarnessCli`, `HarnessSettings`, `LoadRun`, `BalancerWatch` and `RunReport` together. |
| `harness/RunReportTest.java` | `RunReport`: shares, detection, the gap, failures, the warm-up, retries. |
| `haproxy/FakeBalancer.java` | The stand-in runtime API (see `tests-tools-walkthrough-1.md`). |

## How it works underneath

**A stand-in web server inside the test.** The JDK includes a small web server, `com.sun.net.httpserver.HttpServer`. The test creates one on a free loopback port, gives it one handler for the path `/`, and starts it. The handler counts requests and always answers with a prepared JSON body. The load generator then sends real HTTP over a real socket to a program that is in the same JVM.

**A run takes real time.** `HarnessCliTest` uses 100 requests per second for 100 ms of warm-up and one second of measured load. So the test lasts a bit more than a second.

**Exact counts need an exact rate.** The load generator rounds the interval down to whole nanoseconds (see `java-harness-walkthrough-2.md`). At 100 per second, the interval is exactly 10,000,000 nanoseconds, so no request is lost to rounding and the test can expect exactly `sent 100`. At 3,000 per second a one-second measured part would print `sent 2999`.

**Times in tests.** `RunReportTest` uses `SECOND` and `MILLI`, constants of 1,000,000,000 and 1,000,000 nanoseconds, so "the answer at 2 seconds" is `2 * SECOND`. A reader can check the expected numbers by hand.

## Walkthrough of this repository's use

### `harness/HarnessCliTest.java`

```java
package io.plainround.seatwave.balancer.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.plainround.seatwave.balancer.config.SettingsReader;
import io.plainround.seatwave.balancer.haproxy.FakeBalancer;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
```

The imports. `HttpExchange` and `HttpServer` are the JDK's built-in web server types. `@BeforeEach` and `@AfterEach` are JUnit annotations for methods that run before and after every test. `Properties` is Java's class for a `key=value` file held in memory.

```java
class HarnessCliTest {

  private static final String HOLD =
      "{\"holdId\":\"11111111-2222-3333-4444-555555555555\",\"eventId\":1,\"seat\":\"A-7\","
          + "\"state\":\"held\",\"expiresAt\":\"2026-11-11T09:05:00Z\"}";
  private static final String STAT_HEADER = "# pxname,svname,scur,stot,wretr,wredis,status,\n";

  @TempDir Path folder;

  private HttpServer seatwave;
  private FakeBalancer balancer;
  private final AtomicInteger requests = new AtomicInteger();
  private final AtomicInteger reads = new AtomicInteger();
```

`HOLD` is the JSON body of a hold, the exact shape the service sends (`holdId`, `eventId`, `seat`, `state`, `expiresAt`). `STAT_HEADER` is the header line of a small `show stat` table with the columns the watch needs. `folder` is a fresh temporary folder per test. The fields hold the stand-in server, the fake balancer, and two counters: all requests and the reads (the requests that are not `POST`). `AtomicInteger` is a counter that the server threads and the test thread can both use safely.

```java
  @BeforeEach
  void startStandIns() throws IOException {
    seatwave = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 50);
    seatwave.createContext("/", this::answer);
    seatwave.start();
    AtomicInteger reading = new AtomicInteger();
    // The second reading onwards shows seatwave-2 down, so the watch records one change.
    balancer =
        new FakeBalancer(command ->
            STAT_HEADER
                + "seatwave,BACKEND,0,"
                + reading.get()
                + ",0,0,UP,\nseatwave,seatwave-2,0,0,0,0,"
                + (reading.getAndIncrement() == 0 ? "UP" : "DOWN")
                + ",\n");
  }
```

`startStandIns` runs before each test.

- `HttpServer.create(new InetSocketAddress(loopback, 0), 50)` makes a server on a free port with a backlog of 50. `createContext("/", this::answer)` sends every path to the method `answer`. `start()` begins serving.
- The fake balancer is the `FakeBalancer` explained in `tests-tools-walkthrough-1.md`. Its answering function builds a small table from a counter named `reading`. The row `seatwave,BACKEND,0,N,0,0,UP,` is the backend summary, with the total sessions `N` equal to the number of readings so far. The row `seatwave,seatwave-2,0,0,0,0,STATUS,` is the server: `UP` for the very first reading and `DOWN` for every later one. The comment on the line above says it: "The second reading onwards shows seatwave-2 down, so the watch records one change." The expression `reading.getAndIncrement() == 0 ? "UP" : "DOWN"` returns the old value and increments in one step.

```java
  @AfterEach
  void stopStandIns() throws IOException {
    seatwave.stop(0);
    balancer.close();
  }
```

`stopStandIns` runs after each test and stops both stand-ins. `stop(0)` stops the server at once.

```java
  @Test
  void runsTheLoadWatchesTheBalancerAndReports() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    int status =
        HarnessCli.run(
            new String[] {settingsFile("seatwave-2").toString()},
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

    String report = out.toString(StandardCharsets.UTF_8);
    assertThat(status).isZero();
    assertThat(report)
        .contains("sent 100, answered 100, failed 0")
        .contains("statuses: 200 ")
        .contains("share by instance: stand-in 100.0% (100)")
        .contains("lb1 seatwave-2: UP -> DOWN")
        .contains("timeline:");
    assertThat(reads.get()).isPositive();
    assertThat(requests.get()).isEqualTo(110);
  }
```

The main test.

- It creates a `ByteArrayOutputStream`, an in-memory sink, and wraps it in a `PrintStream` so the program prints into memory.
- `HarnessCli.run(new String[] {settingsFile("seatwave-2").toString()}, ...)` runs the whole harness with a settings file that names `seatwave-2` as the victim. The second `PrintStream` collects error output and is not read.
- The checks: status 0; the report contains `sent 100, answered 100, failed 0`; contains `statuses: 200 `, which proves reads were sent and got 200; contains `share by instance: stand-in 100.0% (100)` (every answer named `stand-in` in `X-Served-By`); contains `lb1 seatwave-2: UP -> DOWN`, the change recorded by the watch; and contains `timeline:`.
- Then the counters: `reads.get()` is positive, and `requests.get()` is exactly 110, which is the 10 warm-up requests plus the 100 measured ones. The numbers follow from the settings: 100 per second times 1.1 seconds.

What the test does not check. The settings name `seatwave-2` as the victim, but the stand-in answers as `stand-in`, so the victim never answers and `killAnalysis()` returns empty: the lines about the removal of the victim are not tested here. It also does not look at the line about the balancer's own retries, or at the numbers of the timeline.

```java
  @Test
  void refusesBadUsageAndInvalidSettings() throws IOException {
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    PrintStream errors = new PrintStream(err, true, StandardCharsets.UTF_8);
    Properties broken = settings("none");
    broken.setProperty("load.requestTimeoutMillis", "500");

    assertThat(HarnessCli.run(new String[0], System.out, errors))
        .isEqualTo(HarnessCli.EXIT_BAD_USAGE);
    assertThat(HarnessCli.run(new String[] {write(broken).toString()}, System.out, errors))
        .isEqualTo(HarnessCli.EXIT_BAD_USAGE);
    assertThat(err.toString(StandardCharsets.UTF_8))
        .contains("usage: load")
        .contains("load.requestTimeoutMillis must be longer than load.connectTimeoutMillis");
  }
```

The second test calls `run` with no arguments, and expects exit status 2 (`EXIT_BAD_USAGE`). It then writes a settings file whose request timeout (500 ms) is shorter than its connect timeout (1,000 ms) and expects status 2 again. The error text must contain `usage: load` and `load.requestTimeoutMillis must be longer than load.connectTimeoutMillis`. `Properties broken = settings("none")` starts from a valid set and `setProperty` changes one key, so the test shows that exactly this one mistake is reported.

```java
  @Test
  void namesABadBalancerEntry() {
    Properties properties = settings("none");
    properties.setProperty("watch.balancers", "lb1=10.210.10.21");

    Result<HarnessSettings, List<String>> read =
        HarnessSettings.from(new SettingsReader(properties));

    assertThat(read)
        .isEqualTo(
            Result.error(
                List.of(
                    "watch.balancers: 'lb1=10.210.10.21' is not name=address:port, such as"
                        + " lb1=10.210.10.21:9999")));
  }
```

The third test sets `watch.balancers` to `lb1=10.210.10.21`, which has no port, and calls `HarnessSettings.from` directly. The result must equal a `Result.error` with exactly one line: `watch.balancers: 'lb1=10.210.10.21' is not name=address:port, such as lb1=10.210.10.21:9999`. `new SettingsReader(properties)` wraps the in-memory properties, so no file is needed.

```java
  private void answer(HttpExchange exchange) throws IOException {
    try (exchange) {
      requests.incrementAndGet();
      exchange.getRequestBody().readAllBytes();
      boolean isHold = exchange.getRequestMethod().equals("POST");
      if (!isHold) {
        reads.incrementAndGet();
      }
      byte[] body = HOLD.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("X-Served-By", "stand-in");
      exchange.getResponseHeaders().set("X-Balancer", "lb1");
      exchange.sendResponseHeaders(isHold ? 201 : 200, body.length);
      try (OutputStream stream = exchange.getResponseBody()) {
        stream.write(body);
      }
    }
  }
```

`answer` is the handler of the stand-in server. `try (exchange)` closes the exchange at the end. It counts the request, reads and discards the request body (a server should read the body before answering), and decides: a `POST` is a hold (answered with 201), any other method is a read (counted, and answered with 200). It sets the two headers, `X-Served-By: stand-in` and `X-Balancer: lb1`, and sends the headers with the length of the body, then writes the body. The body is always the same hold.

```java
  private Properties settings(String victim) {
    Properties properties = new Properties();
    properties.setProperty("run.label", "cli-test");
    properties.setProperty("target.url", "http://127.0.0.1:" + seatwave.getAddress().getPort());
    // 100 requests a second: 10 in the 100 ms warm-up, 100 in the measured second.
    properties.setProperty("load.ratePerSecond", "100");
    properties.setProperty("load.warmupMillis", "100");
    properties.setProperty("load.durationMillis", "1000");
    properties.setProperty("load.holdPercent", "50");
    properties.setProperty("load.maxInFlight", "100");
    properties.setProperty("load.connectTimeoutMillis", "1000");
    properties.setProperty("load.requestTimeoutMillis", "5000");
    properties.setProperty("load.seed", "1");
    properties.setProperty("watch.balancers", "lb1=127.0.0.1:" + balancer.port());
    properties.setProperty("watch.backend", "seatwave");
    properties.setProperty("watch.intervalMillis", "50");
    properties.setProperty("report.victim", victim);
    properties.setProperty("report.timelineMillis", "500");
    return properties;
  }
```

`settings(victim)` builds the properties of a small run: the stand-in's address and port, the rate 100, a 100 ms warm-up, a 1,000 ms duration, 50 percent holds, a limit of 100 in flight, timeouts of 1 and 5 seconds, seed 1, one balancer on the fake's port, backend `seatwave`, a 50 ms watch interval, the victim, and a 500 ms timeline step. The comment before the rate states the plan: "10 in the 100 ms warm-up, 100 in the measured second".

```java
  private Path settingsFile(String victim) throws IOException {
    return write(settings(victim));
  }

  private Path write(Properties properties) throws IOException {
    Path file = folder.resolve("harness-" + System.nanoTime() + ".properties");
    try (FileOutputStream stream = new FileOutputStream(file.toFile())) {
      properties.store(stream, "test");
    }
    return file;
  }
}
```

`settingsFile` writes the properties to a file. `write` creates a file with a unique name in the temporary folder, using `System.nanoTime()` in the name, and stores the properties with `Properties.store`, which escapes special characters correctly. `FileOutputStream` is closed by the `try` block.

### `harness/RunReportTest.java`

```java
package io.plainround.seatwave.balancer.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.balancer.config.SettingsReader;
import io.plainround.seatwave.balancer.haproxy.StatRow;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RunReportTest {

  private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
  private static final long MILLI = TimeUnit.MILLISECONDS.toNanos(1);
```

The imports, and the two time constants.

```java
  @Test
  void countsTrafficSharePerInstanceAndPerBalancer() {
    RunReport report =
        new RunReport(
            settings("none"),
            List.of(answer(0, "seatwave-1"), answer(1, "seatwave-2"), answer(2, "seatwave-2")),
            List.of(),
            Map.of());

    assertThat(report.answersByInstance())
        .containsExactly(Map.entry("seatwave-1", 1L), Map.entry("seatwave-2", 2L));
    assertThat(report.answersByBalancer()).containsExactly(Map.entry("lb1", 3L));
    assertThat(report.render()).contains("seatwave-2 66.7% (2)");
  }
```

Test 1. Three answers, one from `seatwave-1` and two from `seatwave-2`, all through `lb1`. The checks: the counts by instance are 1 and 2 (`containsExactly` with `Map.entry`), the count by balancer is 3, and the rendered report contains `seatwave-2 66.7% (2)`. The helper `answer` is described below. Passing `Map.of()` for the backend rows and `List.of()` for the changes means "none".

```java
  @Test
  void measuresDetectionFromTheVictimsLastAnswerToItsRemoval() {
    List<Outcome> outcomes =
        List.of(
            answer(1 * SECOND, "seatwave-1"),
            answer(2 * SECOND, "seatwave-2"),
            Outcome.failure(3 * SECOND, 4 * SECOND, "HTTP 503"),
            Outcome.failure(9 * SECOND, 9 * SECOND + MILLI, "HTTP 503"));
    List<StateChange> changes =
        List.of(new StateChange(8 * SECOND, "lb1", "seatwave-2", "UP 1/3", "DOWN"));

    RunReport.KillAnalysis analysis =
        new RunReport(settings("seatwave-2"), outcomes, changes, Map.of())
            .killAnalysis()
            .orElseThrow();

    assertThat(analysis.detectionMillis()).contains(6_000L);
    assertThat(analysis.failedBetween()).isEqualTo(1);
    assertThat(analysis.failedAfter()).isEqualTo(1);
    assertThat(analysis.removedBy()).isEqualTo("lb1");
    assertThat(analysis.victimShare()).isEqualTo(0.5);
  }
```

Test 2 is the detection arithmetic with times a person can check.

- Answers at 1 s (`seatwave-1`) and 2 s (`seatwave-2`).
- A failed request sent at 3 s that ended at 4 s, and one sent at 9 s.
- One status change: at 8 s, `lb1` took `seatwave-2` from `UP 1/3` to `DOWN`.
- The victim is `seatwave-2`, so its last answer is at 2 s and the removal is at 8 s.
- Expected: the detection time is 6,000 ms; one failure was sent in between (the one at 3 s); one after (the one at 9 s); the removal was by `lb1`; and the victim's share of the answers up to its last answer is 0.5 (one of two).

```java
  @Test
  void ignoresARemovalBeforeTheLastAnswerAndSaysSo() {
    List<StateChange> changes =
        List.of(new StateChange(SECOND, "lb1", "seatwave-2", "UP", "DRAIN"));
    RunReport report =
        new RunReport(
            settings("seatwave-2"), List.of(answer(2 * SECOND, "seatwave-2")), changes, Map.of());

    assertThat(report.killAnalysis().orElseThrow().isRemoved()).isFalse();
    assertThat(report.render()).contains("no balancer took it out after its last answer");
  }
```

Test 3. The status change (`UP` to `DRAIN` at 1 s) happened **before** the victim's last answer (2 s). The analysis must say it was not removed (`isRemoved()` is false) and the rendered report must contain `no balancer took it out after its last answer`. This is the case of a drain that comes before the last answer, and the rule of `killAnalysis`: only a removal after the last answer counts.

```java
  @Test
  void hasNoKillAnalysisWithoutAVictim() {
    RunReport report =
        new RunReport(settings("none"), List.of(answer(0, "seatwave-1")), List.of(), Map.of());

    assertThat(report.killAnalysis()).isEmpty();
  }
```

Test 4. With the victim `none`, `killAnalysis()` is empty.

```java
  @Test
  void findsTheLongestGapBetweenAnswersAndTheFailuresInsideIt() {
    List<Outcome> outcomes =
        List.of(
            answer(1 * SECOND, "seatwave-1"),
            answer(2 * SECOND, "seatwave-1"),
            Outcome.failure(3 * SECOND, 4 * SECOND, "HttpConnectTimeoutException"),
            answer(6 * SECOND, "seatwave-1"));

    RunReport.Gap gap =
        new RunReport(settings("none"), outcomes, List.of(), Map.of()).longestGap().orElseThrow();

    assertThat(gap.endNanos() - gap.startNanos()).isEqualTo(4 * SECOND);
    assertThat(gap.failedInside()).isEqualTo(1);
  }
```

Test 5, the longest gap. Answers at 1, 2 and 6 seconds and a failed request sent at 3 s. The longest gap is the one from 2 to 6 s, 4 seconds, and one failed request was sent inside it.

```java
  @Test
  void saysNoFailuresAndAllFailuresDifferently() {
    String none =
        new RunReport(settings("none"), List.of(answer(0, "seatwave-1")), List.of(), Map.of())
            .render();
    String all =
        new RunReport(
                settings("none"),
                List.of(Outcome.failure(0, MILLI, "HTTP 502")),
                List.of(),
                Map.of())
            .render();

    assertThat(none).contains("failures: none", "failed 0 (0.000%)");
    assertThat(all)
        .contains("failures: HTTP 502 1", "failed 1 (100.000%)", "share by instance: no answers");
  }
```

Test 6. Two reports. One with one answer and no failure must say `failures: none` and `failed 0 (0.000%)`. One with a single failure and no answer must say `failures: HTTP 502 1`, `failed 1 (100.000%)` and `share by instance: no answers`. This guards the difference between "no failures" and "all failures", which a report must not confuse.

```java
  @Test
  void leavesTheWarmUpOutOfEveryNumber() {
    RunReport report =
        new RunReport(
            settings("none"),
            List.of(Outcome.failure(-SECOND, -SECOND + MILLI, "HTTP 503"), answer(0, "seatwave-1")),
            List.of(),
            Map.of());

    assertThat(report.render()).contains("sent 1, answered 1, failed 0");
  }
```

Test 7. A failure that began at minus one second (the warm-up) and one answer at time zero. The report must say `sent 1, answered 1, failed 0`: the warm-up failure is left out of every number. Without the filter in the constructor it would say `failed 1`. I removed the filter in a scratch copy and this test, and one in `HarnessCliTest`, failed.

```java
  @Test
  void showsTheBalancersOwnRetriesDuringTheRun() {
    StatRow before =
        new StatRow(
            Map.of(
                "pxname",
                "seatwave",
                "svname",
                "BACKEND",
                "stot",
                "10",
                "wretr",
                "1",
                "wredis",
                "0"));
    StatRow after =
        new StatRow(
            Map.of(
                "pxname",
                "seatwave",
                "svname",
                "BACKEND",
                "stot",
                "110",
                "wretr",
                "9",
                "wredis",
                "11"));

    String text =
        new RunReport(
                settings("none"),
                List.of(answer(0, "seatwave-1")),
                List.of(),
                Map.of("lb1", List.of(before, after)))
            .render();

    assertThat(text)
        .contains(
            "balancer lb1 during the run (backend seatwave): 100 requests passed, 8 retries, 11 redispatches");
  }
```

Test 8. Two hand-made `StatRow`s for the `BACKEND` row of `seatwave`: before (`stot` 10, `wretr` 1, `wredis` 0) and after (110, 9, 11). A `Map.of(...)` with that many arguments is written one item per line by the formatter. The report must say `100 requests passed, 8 retries, 11 redispatches`: the differences. This is how the report gets the line `balancer lb1 during the run ...`.

```java
  private static Outcome answer(long doneNanos, String instance) {
    return Outcome.response(Math.max(0, doneNanos - MILLI), doneNanos, 201, instance, "lb1");
  }
```

`answer(doneNanos, instance)` makes an answered `Outcome`: sent one millisecond before it was done (but not before zero), status 201, the instance and the balancer `lb1`. A latency of one millisecond (or zero at time zero) makes the numbers simple.

```java
  static HarnessSettings settings(String victim) {
    Properties properties = new Properties();
    properties.setProperty("run.label", "test");
    properties.setProperty("target.url", "http://127.0.0.1:21000");
    properties.setProperty("load.ratePerSecond", "1000");
    properties.setProperty("load.warmupMillis", "0");
    properties.setProperty("load.durationMillis", "10000");
    properties.setProperty("load.holdPercent", "50");
    properties.setProperty("load.maxInFlight", "100");
    properties.setProperty("load.connectTimeoutMillis", "1000");
    properties.setProperty("load.requestTimeoutMillis", "5000");
    properties.setProperty("load.seed", "1");
    properties.setProperty("watch.balancers", "lb1=127.0.0.1:21005");
    properties.setProperty("watch.backend", "seatwave");
    properties.setProperty("watch.intervalMillis", "100");
    properties.setProperty("report.victim", victim);
    properties.setProperty("report.timelineMillis", "1000");
    return switch (HarnessSettings.from(new SettingsReader(properties))) {
      case Result.Success<HarnessSettings, List<String>>(HarnessSettings checked) -> checked;
      case Result.Error<HarnessSettings, List<String>>(List<String> problems) ->
          throw new AssertionError(problems.toString());
    };
  }
}
```

`settings(victim)` builds a `HarnessSettings` the same way the real code does, through `HarnessSettings.from(new SettingsReader(properties))`, so the tests use valid, checked settings. The `switch` takes the `Result` apart: `Success` returns the settings; `Error` throws an `AssertionError` with the problems, so a broken fixture fails loudly. The settings: 1,000 requests per second for 10 seconds, victim as given.

What `RunReportTest` does not check. The coverage report of my scratch copy shows that the lines of `killLines` that print the removal time and the Little's Law forecast (lines 266 to 287 of `RunReport.java`) never run in any unit test. Eleven more lines of `RunReport.java` have a branch that no test takes (lines 96, 99, 102, 105, 109, 147, 193, 202, 320, 360 and 440): for example the case of no sent request in the failure percentage and the case of an answer without a header in `countAnswers`. No assertion looks at the p50 and p99 numbers or at the timeline. I confirmed that this matters. In a scratch copy I made each of these changes alone, and all 31 tests of the three packages still passed each time: `Math.ceil` to `Math.floor` in `rank` (it shifts every percentile), and the bound `<= removedNanos` to `< removedNanos` in the count of failures sent in between.

## Try it yourself

Run these in the repository folder. Nothing needs Docker. Timings differ on every run.

**1. Run the harness tests.**

```bash
./gradlew test --tests 'io.plainround.seatwave.balancer.harness.*' --console=plain | grep -E 'PASSED|FAILED|BUILD'
```

```text
HarnessCliTest > runsTheLoadWatchesTheBalancerAndReports() PASSED
HarnessCliTest > namesABadBalancerEntry() PASSED
HarnessCliTest > refusesBadUsageAndInvalidSettings() PASSED
RunReportTest > showsTheBalancersOwnRetriesDuringTheRun() PASSED
RunReportTest > leavesTheWarmUpOutOfEveryNumber() PASSED
RunReportTest > findsTheLongestGapBetweenAnswersAndTheFailuresInsideIt() PASSED
RunReportTest > ignoresARemovalBeforeTheLastAnswerAndSaysSo() PASSED
RunReportTest > saysNoFailuresAndAllFailuresDifferently() PASSED
RunReportTest > measuresDetectionFromTheVictimsLastAnswerToItsRemoval() PASSED
RunReportTest > hasNoKillAnalysisWithoutAVictim() PASSED
RunReportTest > countsTrafficSharePerInstanceAndPerBalancer() PASSED
BUILD SUCCESSFUL in 2s
```

**2. Break the warm-up rule.** In a scratch copy, in `RunReport.java`, change `outcome.sentNanos() >= 0).toList();` to `true).toList();` and run command 1. Two tests fail: `leavesTheWarmUpOutOfEveryNumber` and `runsTheLoadWatchesTheBalancerAndReports` (I ran this change). Undo it.

**3. Find a change no test notices.** In the same copy, in `rank`, change `Math.ceil` to `Math.floor` and run command 1. All tests pass (I ran this change). The percentiles of the report have no test. Undo it.

**4. Watch the integration test take its time.** Run the single test and look at the time Gradle reports.

```bash
./gradlew test --tests '*HarnessCliTest.runsTheLoadWatchesTheBalancerAndReports' --console=plain | grep -E 'PASSED|BUILD'
```

```text
HarnessCliTest > runsTheLoadWatchesTheBalancerAndReports() PASSED
BUILD SUCCESSFUL in 2s
```

The test takes just over a second of real time, because it really sends load for 1.1 seconds. On my run the test report file recorded 1.212 seconds. The build time Gradle prints is longer, because Gradle starts first.

## Mistakes people make

- **A test that depends on how fast the computer is.** These tests use exact counts only where the program makes the counts exact (a rate that divides a second evenly), and use bounded waits.
- **Asserting on a rounded number that comes from the clock.** `RunReportTest` uses times chosen by hand, so the expected values can be worked out on paper.
- **A stand-in that cannot fail.** The stand-in server always answers `200` or `201`, so the failure paths of `LoadRun` (timeouts, refused connections, 5xx) are not exercised here. Lines 143 to 147 of `LoadRun.java` (the `IOException` branch) and the not-sent branch never ran in any test.
- **Naming a victim that never appears.** `HarnessCliTest` sets a victim, but the stand-in answers under another name, so the removal analysis is empty. If you want to test the analysis, make the stand-in answer as the victim.
- **Fixtures that hide mistakes.** `RunReportTest.settings` goes through the real settings reader, so an invalid fixture would fail the test instead of silently building an impossible object.
- **Leaving servers running.** Both stand-ins are stopped in `@AfterEach`, even when a test fails.

## Check yourself

1. Why does `HarnessCliTest` use a rate of 100 per second? *Answer: 100 divides a second into exactly 10,000,000 ns, so the schedule does not drift and the test can expect exactly `sent 100`.*
2. Why is `requests.get()` expected to be exactly 110? *Answer: 10 warm-up requests (100 ms at 100 per second) plus 100 measured requests.*
3. What does test 3 of `RunReportTest` prove? *Answer: A removal before the victim's last answer is ignored, and the report says that no balancer took it out after its last answer.*
4. Which two numbers does test 8 subtract to get "100 requests passed"? *Answer: `stot` of the last backend row (110) minus `stot` of the first (10).*
5. Name one thing no test in this part checks. *Answer: Any of: the p50 and p99 values, the timeline, the lines about the removal time and the forecast, the failure branches of `LoadRun`.*

## Further reading

- JUnit User Guide (this repository pins JUnit 6.1.3), "Writing tests", `@BeforeEach`, `@AfterEach`, `@TempDir`: https://docs.junit.org/current/user-guide/
- AssertJ Core documentation, 3.27: https://assertj.github.io/doc/
- Java SE 25 API, the built-in HTTP server `com.sun.net.httpserver` (module `jdk.httpserver`): https://docs.oracle.com/en/java/javase/25/docs/api/jdk.httpserver/com/sun/net/httpserver/package-summary.html
- Java SE 25 API: `java.util.Properties`, `java.util.concurrent.atomic.AtomicInteger`: https://docs.oracle.com/en/java/javase/25/docs/api/
