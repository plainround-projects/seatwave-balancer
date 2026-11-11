# The tests of the tools, part 2: the harness, its report and the JSON codec

This is part 2 of 2. Part 1 (`tests-tools-walkthrough-1.md`) explained what a test is, what a fake is, and read the tests of the `haproxy` and `deploy` packages. Part 2 reads the four test classes of the `harness` package, `HarnessCliTest` (a whole run against stand-ins on your own computer), `LoadRunTest` (the load generator alone), `BalancerWatchTest` (the thread that reads the balancer) and `RunReportTest` (the arithmetic of the report on hand-made data), and the test of the shared `json` package, `JsonCodecTest`. Together they have 34 tests: 5, 7, 1, 14 and 7.

## What it is

`HarnessCliTest` is an **integration test**: it runs a whole program, the real settings reader, the real load generator, the real watch thread, the real report, against two stand-ins on the loopback address. One stand-in plays SeatWave behind a balancer (a small web server). The other plays HAProxy's runtime API (`FakeBalancer`, explained in `tests-tools-walkthrough-1.md`). Nothing outside the computer is involved.

`LoadRunTest` and `BalancerWatchTest` test one class each, with the same kinds of stand-ins, and look at one promise at a time: the exact start of the measured part, the exact share of holds, one outcome for every planned request, the last reading of the balancer.

`RunReportTest` and `JsonCodecTest` are **unit tests**: each builds a few objects or a short text by hand and checks one result. `RunReport` takes plain objects and returns plain text, and `JsonCodec` takes text and returns a record or a reason, so neither needs a network.

## Why this repository uses it

The module's numbers come from the report code, and the labs that produce them need Docker and take minutes. These tests check the same arithmetic in seconds, anywhere. They also protect promises the numbers depend on: the warm-up never counts, every planned request ends as exactly one outcome, the mix of holds and reads is what the settings say, and a mistake in a settings file is explained and stops the run before any request is sent.

The review of version 1.0.0 found that several of these promises were broken or untested: at 3,000 requests per second the first measured request fell into the warm-up (`sent 89999` in the old run instead of `sent 90000`), a fault of the harness could lose an outcome, the percentiles and the Little's Law line had no test. Version 1.1.0 fixed the code and added a test for each. The module's mutation check confirms that each of those one-line breaks now makes a test fail; this note names the test that catches each one.

## Where it appears

| File | What it tests |
|---|---|
| `harness/HarnessCliTest.java` | `HarnessCli`, `HarnessSettings`, `LoadRun`, `BalancerWatch` and `RunReport` together. |
| `harness/LoadRunTest.java` | `LoadRun`: the schedule, the mix, the reads of made holds, faults, a dead target, the seats. |
| `harness/BalancerWatchTest.java` | `BalancerWatch`: one more reading after the load ended. |
| `harness/RunReportTest.java` | `RunReport`: shares, detection, the gap, failures, the warm-up, retries, Little's Law, percentiles, the mix. |
| `json/JsonCodecTest.java` | `JsonCodec`: what a request body must look like, and the reason given when it does not. |
| `haproxy/FakeBalancer.java` | The stand-in runtime API (see `tests-tools-walkthrough-1.md`). |

## How it works underneath

**A stand-in web server inside the test.** The JDK includes a small web server, `com.sun.net.httpserver.HttpServer`. The test creates one on a free loopback port, gives it one handler for the path `/`, and starts it. The handler counts requests and answers with a prepared JSON body. The load generator then sends real HTTP over a real socket to a program that is in the same JVM.

**A run takes real time.** `HarnessCliTest` uses 100 requests per second for 100 ms of warm-up and one second of measured load, so the test lasts a bit more than a second. The `LoadRunTest` runs last 20 ms to 300 ms each.

**The schedule comes from the index.** The load generator computes when request number `i` is due as `first + i x 1,000,000,000 / rate` nanoseconds, in whole numbers, in one step. Version 1.0.0 added a rounded interval again and again instead; at 3,000 per second the interval is 333,333.3 ns, the rounding lost 0.3 ns per request, and request 15,000 (the first of the measured part) started 5 microseconds too early, inside the warm-up. `LoadRunTest` checks the new way at a rate that does not divide a second.

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
```

The imports. `HttpExchange` and `HttpServer` are the JDK's built-in web server types. `@BeforeEach` and `@AfterEach` are JUnit annotations for methods that run before and after every test. `Properties` is Java's class for a `key=value` file held in memory. `TimeUnit` converts between units of time. `AtomicInteger` and `AtomicLong` are a counter and a number that several threads can read and change safely.

```java
class HarnessCliTest {

  private static final String HOLD =
      "{\"holdId\":\"11111111-2222-3333-4444-555555555555\",\"eventId\":1,\"seat\":\"A-7\","
          + "\"state\":\"held\",\"expiresAt\":\"2026-11-11T09:05:00Z\"}";
  private static final String STAT_HEADER = "# pxname,svname,scur,stot,wretr,wredis,status,\n";
  // The victim answers the first 30 requests (10 of them in the warm-up).
  private static final int VICTIM_REQUESTS = 30;
  // Long enough that the victim's last answer has certainly arrived before it is shown down.
  private static final long VICTIM_DOWN_AFTER_NANOS = TimeUnit.MILLISECONDS.toNanos(200);

  @TempDir Path folder;

  private HttpServer seatwave;
  private FakeBalancer balancer;
  private final AtomicInteger requests = new AtomicInteger();
  private final AtomicInteger reads = new AtomicInteger();
  // The stand-in answers as seatwave-2 for its first requests, then as seatwave-1, and the fake
  // balancer shows seatwave-2 down only well after that: a kill, as the report analyses it.
  private final AtomicLong victimGoneNanos = new AtomicLong(Long.MAX_VALUE);
```

`HOLD` is the JSON body of a hold, the exact shape the service sends (`holdId`, `eventId`, `seat`, `state`, `expiresAt`). `STAT_HEADER` is the header line of a small `show stat` table with the columns the watch needs. `folder` is a fresh temporary folder per test.

The rest plays out a kill, new in 1.1.0 so that the report's removal lines get tested:

- `VICTIM_REQUESTS` is 30: the stand-in answers its first 30 requests as `seatwave-2`, the victim. At 100 per second with 100 ms of warm-up, 10 of those are warm-up requests and 20 are measured.
- `VICTIM_DOWN_AFTER_NANOS` is 200 ms: the fake balancer shows `seatwave-2` down only 200 ms after the victim's last answer. The comment says why: the removal must come after the last answer, as it does in a real kill, and 200 ms is long enough that the last answer has surely arrived.
- `victimGoneNanos` holds the moment the victim stopped answering. It starts at `Long.MAX_VALUE`, the largest possible `long`, which means "not yet".
- `requests` and `reads` count all requests and the reads (the requests that are not `POST`).

```java
  @BeforeEach
  void startStandIns() throws IOException {
    seatwave = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 50);
    seatwave.createContext("/", this::answer);
    seatwave.start();
    balancer =
        new FakeBalancer(command ->
            STAT_HEADER
                + "seatwave,BACKEND,0,"
                + requests.get()
                + ",0,0,UP,\nseatwave,seatwave-2,0,0,0,0,"
                + (System.nanoTime() - victimGoneNanos.get() > VICTIM_DOWN_AFTER_NANOS
                    ? "DOWN"
                    : "UP")
                + ",\n");
  }
```

`startStandIns` runs before each test.

- `HttpServer.create(new InetSocketAddress(loopback, 0), 50)` makes a server on a free port with a backlog of 50. `createContext("/", this::answer)` sends every path to the method `answer`. `start()` begins serving.
- The fake balancer builds a small table for every command. The row `seatwave,BACKEND,0,N,0,0,UP,` is the backend summary; its total sessions `N` is the number of requests so far, `requests.get()`, so the report's line about the balancer's own traffic has real numbers to subtract. The row `seatwave,seatwave-2,0,0,0,0,STATUS,` is the victim: `DOWN` once more than 200 ms have passed since `victimGoneNanos`, otherwise `UP`. Before the victim stops, `victimGoneNanos` is `Long.MAX_VALUE`, so `System.nanoTime() - victimGoneNanos.get()` is a large negative number and the status is `UP`.

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
        .contains("mix: 50 holds (POST), 50 reads (GET)")
        .contains("statuses: 200 ")
        .contains("lb1 seatwave-2: UP -> DOWN")
        .contains("removal of seatwave-2:")
        .contains("taken out by lb1 at ")
        .contains("failed requests sent in between: 0; after: 0; Little's Law without retries:")
        .contains("timeline:");
    assertThat(reads.get()).isPositive();
    assertThat(requests.get()).isEqualTo(110);
  }
```

The main test.

- It creates a `ByteArrayOutputStream`, an in-memory sink, and wraps it in a `PrintStream` so the program prints into memory.
- `HarnessCli.run(new String[] {settingsFile("seatwave-2").toString()}, ...)` runs the whole harness with a settings file that names `seatwave-2` as the victim. The second `PrintStream` collects error output and is not read.
- The checks on the report:
  - `sent 100, answered 100, failed 0`: exactly the 100 measured requests, all answered.
  - `mix: 50 holds (POST), 50 reads (GET)`: the new `mix:` line shows what really ran. 50 percent holds of 100 requests are 50 and 50.
  - `statuses: 200 `: reads were sent and got 200.
  - `lb1 seatwave-2: UP -> DOWN`: the status change recorded by the watch.
  - `removal of seatwave-2:`, `taken out by lb1 at ` and `failed requests sent in between: 0; after: 0; Little's Law without retries:`: the kill analysis ran, found the removal after the last answer, and counted no failure, because the stand-in kept answering (as `seatwave-1`).
  - `timeline:`.
- Then the counters: `reads.get()` is positive, and `requests.get()` is exactly 110, the 10 warm-up requests plus the 100 measured ones: 100 per second times 1.1 seconds.

In version 1.0.0 the stand-in answered as `stand-in`, so the victim never answered and the removal lines were never printed in this test. The test still does not check the numbers of the timeline or the exact times of the removal, which depend on the clock.

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

`refusesBadUsageAndInvalidSettings` calls `run` with no arguments, and expects exit status 2 (`EXIT_BAD_USAGE`). It then writes a settings file whose request timeout (500 ms) is shorter than its connect timeout (1,000 ms) and expects status 2 again. The error text must contain `usage: load` and `load.requestTimeoutMillis must be longer than load.connectTimeoutMillis`. `Properties broken = settings("none")` starts from a valid set and `setProperty` changes one key, so the test shows that exactly this one mistake is reported.

```java
  @Test
  void reportsAMissingTimeoutOnceWithoutTheRuleThatNeedsIt() {
    Properties properties = settings("none");
    properties.remove("load.connectTimeoutMillis");
    properties.remove("load.requestTimeoutMillis");

    Result<HarnessSettings, List<String>> read =
        HarnessSettings.from(new SettingsReader(properties));

    assertThat(read)
        .isEqualTo(
            Result.error(
                List.of(
                    "load.connectTimeoutMillis is missing",
                    "load.requestTimeoutMillis is missing")));
  }
```

**`reportsAMissingTimeoutOnceWithoutTheRuleThatNeedsIt`.** New in 1.1.0. Both timeouts are removed. The reader reports each as missing. The rule "request longer than connect" must then not run, because it would compare two values nobody wrote and add a confusing third line. The result must be exactly the two lines `load.connectTimeoutMillis is missing` and `load.requestTimeoutMillis is missing`. The code counts the problems before and after reading the timeouts and runs the rule only when none was added (`timeoutsRead`). The mutation check drops that condition, and this test fails.

```java
  @Test
  void refusesATargetWithAShortAddressOrAPortOutOfRange() {
    for (String target :
        List.of("http://127.0.1:21000", "http://127.0.0.1:99999", "http://127.0.0.1:021000")) {
      Properties properties = settings("none");
      properties.setProperty("target.url", target);

      Result<HarnessSettings, List<String>> read =
          HarnessSettings.from(new SettingsReader(properties));

      assertThat(read).as(target).isInstanceOf(Result.Error.class);
    }
  }
```

**`refusesATargetWithAShortAddressOrAPortOutOfRange`.** New in 1.1.0. Three target addresses that a looser check would accept:

- `http://127.0.1:21000`: a three-part address, which Java would read as `127.0.0.1`;
- `http://127.0.0.1:99999`: a port above the highest, 65,535;
- `http://127.0.0.1:021000`: a padded port.

Each, set alone on valid settings, must give a `Result.Error`. `as(target)` puts the target into the failure message, so a failure says which of the three passed. The mutation check removes the port limit, and this test fails on `99999`.

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

`namesABadBalancerEntry` sets `watch.balancers` to `lb1=10.210.10.21`, which has no port, and calls `HarnessSettings.from` directly. The result must equal a `Result.error` with exactly one line: `watch.balancers: 'lb1=10.210.10.21' is not name=address:port, such as lb1=10.210.10.21:9999`. `new SettingsReader(properties)` wraps the in-memory properties, so no file is needed.

```java
  private void answer(HttpExchange exchange) throws IOException {
    try (exchange) {
      // The number this request got, read once, so two overlapping requests never share one.
      int number = requests.incrementAndGet();
      exchange.getRequestBody().readAllBytes();
      boolean isHold = exchange.getRequestMethod().equals("POST");
      if (!isHold) {
        reads.incrementAndGet();
      }
      byte[] body = HOLD.getBytes(StandardCharsets.UTF_8);
      if (number == VICTIM_REQUESTS + 1) {
        victimGoneNanos.set(System.nanoTime());
      }
      exchange
          .getResponseHeaders()
          .set("X-Served-By", number <= VICTIM_REQUESTS ? "seatwave-2" : "seatwave-1");
      exchange.getResponseHeaders().set("X-Balancer", "lb1");
      exchange.sendResponseHeaders(isHold ? 201 : 200, body.length);
      try (OutputStream stream = exchange.getResponseBody()) {
        stream.write(body);
      }
    }
  }
```

`answer` is the handler of the stand-in server. `try (exchange)` closes the exchange at the end. It counts the request and keeps the number it got, reads and discards the request body (a server should read the body before answering), and decides: a `POST` is a hold (answered with 201), any other method is a read (counted, and answered with 200).

Then the kill. `number` is the value `requests.incrementAndGet()` returned for this request: the count of requests so far, this one included, read in the same step as the increment. The 31st request (`VICTIM_REQUESTS + 1`) is the first after the victim's last answer, so its arrival time is stored in `victimGoneNanos`. The header `X-Served-By` says `seatwave-2` for the first 30 requests and `seatwave-1` afterwards, so the report sees the victim answer, then stop. `X-Balancer: lb1` names the balancer. It sends the headers with the length of the body, then writes the body. The body is always the same hold.

The comment says why the number is taken from `incrementAndGet()` itself. Several server threads may run `answer` at once. Had the code read `requests.get()` a few lines after the increment, two overlapping requests could both read 32, the 31st would be skipped, `victimGoneNanos` would never be set and the victim never shown down. `incrementAndGet()` gives each request its own number in one atomic step, so that cannot happen.

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

### `harness/LoadRunTest.java`

New in 1.1.0. It runs the load generator, `LoadRun`, alone against a stand-in server, without the watch or the report.

```java
package io.plainround.seatwave.balancer.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.plainround.seatwave.balancer.config.SettingsReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
```

The imports. Most are for the stand-in server and for `FaultyClient` below, a hand-made HTTP client: `Authenticator`, `CookieHandler`, `ProxySelector`, `SSLContext`, `SSLParameters`, `Executor` and `CompletableFuture` are the types of the methods every `HttpClient` must have. `ConcurrentLinkedQueue` is a queue that many threads may add to at the same time. `LongStream` produces a run of `long` numbers. `HashSet` and `Set` hold distinct values.

```java
class LoadRunTest {

  private static final String VALID_ID = "11111111-2222-3333-4444-555555555555";
  // Module 1's arena.
  private static final int SEATS = 20_000;
  private static final int PERCENT = 100;

  private HttpServer seatwave;
  private HttpClient client;
  private final Queue<String> requestLines = new ConcurrentLinkedQueue<>();
  private volatile String holdIdToAnswer = VALID_ID;
  private final AtomicInteger holdsAnswered = new AtomicInteger();
```

- `VALID_ID` is a well-formed hold id (a UUID, the shape `8-4-4-4-12` hexadecimal digits).
- `SEATS` is 20,000, the arena of Module 1. `PERCENT` is 100.
- `requestLines` records the first line of every request the stand-in receives, such as `POST /v1/holds`.
- `holdIdToAnswer` is the hold id the stand-in puts in its answers; one test changes it. `volatile` makes a change visible to the server threads at once.
- `holdsAnswered` counts the holds the stand-in answered.

```java
  @BeforeEach
  void startStandIn() throws IOException {
    seatwave = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100);
    seatwave.createContext("/", this::answer);
    seatwave.start();
  }

  @AfterEach
  void stopStandIn() {
    if (client != null) {
      client.close();
    }
    seatwave.stop(0);
  }
```

Before each test, a stand-in server starts on a free loopback port with a backlog of 100. After each test, the HTTP client is closed if one was made (`HttpClient` has `close()` since Java 21), and the server stops.

```java
  @Test
  void startsTheMeasuredPartAtTimeZeroAtARateThatDoesNotDivideASecond() throws Exception {
    // 3,000 a second: 333,333.3 ns apart. 100 ms of warm-up are 300 requests, so request 300 is
    // due exactly at time zero and belongs to the measured part.
    List<Outcome> outcomes = run(settings(3_000, 100, 100, 50));

    assertThat(outcomes).hasSize(600);
    assertThat(outcomes).filteredOn(outcome -> outcome.sentNanos() >= 0).hasSize(300);
  }
```

**`startsTheMeasuredPartAtTimeZeroAtARateThatDoesNotDivideASecond`.** 3,000 requests per second, 100 ms of warm-up, 100 ms measured, 50 percent holds: 600 requests in all. The comment does the arithmetic: the requests are 333,333.3 ns apart, the warm-up holds requests 0 to 299, and request 300 is due exactly at time zero. The checks: 600 outcomes, and exactly 300 of them sent at or after time zero (`filteredOn(... >= 0)` keeps only those, then `hasSize(300)`).

The defect it guards against is the one in "How it works underneath". The mutation check puts back the rounded interval (`requestIndex * (NANOS_PER_SECOND / rate)`): request 300 is then due 100 ns before zero, only 299 count as measured, and this test fails. That defect is why the 1.0.0 runs printed `sent 89999` and the 1.1.0 runs print `sent 90000`.

```java
  @Test
  void sendsExactlyTheHoldShareTheSettingsAskFor() throws Exception {
    LoadRun load = load(settings(1_000, 0, 200, 30));

    load.run();

    long posts = requestLines.stream().filter(line -> line.startsWith("POST")).count();
    assertThat(posts).isEqualTo(60);
    assertThat(requestLines).hasSize(200);
    assertThat(load.mix().holds()).isEqualTo(60);
    assertThat(load.mix().reads()).isEqualTo(140);
  }
```

**`sendsExactlyTheHoldShareTheSettingsAskFor`.** 1,000 per second for 200 ms with 30 percent holds is 200 requests. A request is a hold when its index modulo 100 is below 30, so exactly 60 are holds. The checks: 60 lines start with `POST`, 200 lines in all, and `mix()` reports 60 holds and 140 reads.

In version 1.0.0 the choice depended on whether a hold had been made yet, so a run whose holds were all refused sent fewer reads than planned. The mutation check changes `<` to `<=` (31 holds in every 100), and this test fails.

```java
  @Test
  void readsTheHoldsItMade() throws Exception {
    LoadRun load = load(settings(1_000, 0, 200, 50));

    load.run();

    // Every hold is answered with VALID_ID, so every read once a hold is known asks for it.
    assertThat(requestLines)
        .filteredOn(line -> line.startsWith("GET"))
        .allMatch(line ->
            line.equals("GET /v1/holds/" + VALID_ID)
                || line.equals("GET /v1/holds/00000000-0000-0000-0000-000000000000"));
    assertThat(requestLines).contains("GET /v1/holds/" + VALID_ID);
    assertThat(load.mix().unreadableHoldReplies()).isZero();
  }
```

**`readsTheHoldsItMade`.** Every hold is answered with `VALID_ID`, so once the harness knows a hold, every read must ask for it: `GET /v1/holds/11111111-2222-3333-4444-555555555555`. A read that is due before the first hold answer has arrived asks for the **nil UUID**, `00000000-0000-0000-0000-000000000000`, a well-formed id that no run ever makes. `allMatch` allows exactly these two lines. `contains(...)` requires at least one real read of the made hold. `unreadableHoldReplies()` must be 0: every hold answer was understood.

```java
  @Test
  void keepsOneOutcomePerPlannedRequestWhenAReplyHoldsNoUsableHoldId() throws Exception {
    // "a b" is not a hold id; building a read of it would fail, and the first version of the
    // harness then lost that request's outcome without a word.
    holdIdToAnswer = "a b";
    LoadRun load = load(settings(1_000, 0, 300, 50));

    List<Outcome> outcomes = load.run();

    assertThat(outcomes).hasSize(300);
    assertThat(outcomes).allMatch(outcome -> outcome.kind() == Outcome.Kind.ANSWERED);
    assertThat(load.mix().unreadableHoldReplies()).isEqualTo(holdsAnswered.get()).isEqualTo(150);
    assertThat(load.mix().readsOfUnknownHold()).isEqualTo(150);
  }
```

**`keepsOneOutcomePerPlannedRequestWhenAReplyHoldsNoUsableHoldId`.** The stand-in now answers every hold with the id `a b`, which is not a UUID and holds a space. The comment tells the history: building a read of `/v1/holds/a b` throws, because a space is not allowed in a web address, and version 1.0.0 then lost that request's outcome without a word, so the report's counts no longer added up.

The checks:

- 300 outcomes for 300 planned requests, and all of them answered.
- `unreadableHoldReplies()` equals the number of holds the stand-in answered, and both equal 150 (half of 300). `isEqualTo(...).isEqualTo(150)` chains two checks on the same value.
- `readsOfUnknownHold()` is 150: no hold id was usable, so every read asked for the nil UUID.

The harness now accepts a hold id only if it matches the UUID pattern. The mutation check removes that check, and this test fails (the reads of `a b` become harness faults). A second mutation stops counting unreadable replies, and this test fails too.

```java
  @Test
  void countsAFaultOfTheHarnessItselfAsAFailedRequest() throws Exception {
    HarnessSettings settings = settings(1_000, 0, 20, 50);
    LoadRun load = new LoadRun(settings, new FaultyClient(), System.nanoTime(), "load-test");

    List<Outcome> outcomes = load.run();

    assertThat(outcomes)
        .hasSize(20)
        .allMatch(outcome -> outcome.failure().equals("harness fault IllegalArgumentException"));
  }
```

**`countsAFaultOfTheHarnessItselfAsAFailedRequest`.** `FaultyClient` (below) throws `IllegalArgumentException` on every send, as a bug in the harness would. The `LoadRun` is built directly with that client, time zero now and no warm-up. All 20 planned requests must still come back, each a failure named `harness fault IllegalArgumentException`. `LoadRun` catches every `RuntimeException` around one request and turns it into an outcome. The mutation check narrows that catch to an exception that is never thrown here, the outcomes go missing, and this test fails.

```java
  @Test
  void countsEveryRequestToADeadTargetAsFailedWithItsReason() throws Exception {
    HarnessSettings settings = settings(1_000, 0, 50, 50);
    seatwave.stop(0);

    List<Outcome> outcomes = run(settings);

    assertThat(outcomes)
        .hasSize(50)
        .allMatch(outcome -> outcome.kind() == Outcome.Kind.FAILED)
        .allMatch(outcome -> outcome.failure().startsWith("ConnectException"));
  }
```

**`countsEveryRequestToADeadTargetAsFailedWithItsReason`.** The stand-in is stopped before the run, so nothing listens on its port. All 50 requests must be failures, and each failure text must start with `ConnectException`, the Java exception for a refused connection. This is the path a killed instance takes in the labs.

```java
  @Test
  void asksForEverySeatOnceInTwentyThousandHoldRequests() {
    for (int holdPercent : new int[] {1, 50, 100}) {
      Set<String> seats = new HashSet<>();
      LongStream.range(0, (long) SEATS * PERCENT / holdPercent)
          .filter(index -> index % PERCENT < holdPercent)
          .forEach(index -> seats.add(LoadRun.seatFor(7, index, holdPercent)));

      assertThat(seats).as("hold share %d%%", holdPercent).hasSize(SEATS);
    }
  }
```

**`asksForEverySeatOnceInTwentyThousandHoldRequests`.** The k-th hold asks for seat `(seed + k x 7,919) mod 20,000`. Because 7,919 is a prime that does not divide 20,000, 20,000 holds in a row visit every seat once. The test checks this at three hold shares, 1, 50 and 100 percent.

- `LongStream.range(0, SEATS * PERCENT / holdPercent)` produces the request indexes of a run long enough to hold 20,000 holds: 2,000,000 indexes at 1 percent, 40,000 at 50, 20,000 at 100. `(long)` makes the multiplication use 64-bit numbers.
- `filter(index -> index % PERCENT < holdPercent)` keeps the hold requests, the same rule `LoadRun` uses.
- `LoadRun.seatFor(7, index, holdPercent)` names the seat for each, with seed 7, and adds it to the set.
- The set must hold 20,000 distinct seats. `as("hold share %d%%", holdPercent)` names the share in a failure (`%%` prints one `%`).

In version 1.0.0 the seat came from the request index, not from the hold's number, so at 50 percent holds only some of the seats were ever asked for. The mutation check puts that back, and this test fails.

```java
  private List<Outcome> run(HarnessSettings settings) throws InterruptedException {
    return load(settings).run();
  }

  private LoadRun load(HarnessSettings settings) {
    client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(settings.connectTimeout())
            .build();
    return new LoadRun(
        settings, client, System.nanoTime() + settings.warmup().toNanos(), "load-test");
  }
```

Two helpers. `run(settings)` builds a `LoadRun` and runs it. `load(settings)` builds the JDK's HTTP client for HTTP/1.1 with the settings' connect timeout, keeps it in the field so `@AfterEach` closes it, and makes a `LoadRun` whose time zero is now plus the warm-up, with the key prefix `load-test`.

```java
  private void answer(HttpExchange exchange) throws IOException {
    try (exchange) {
      exchange.getRequestBody().readAllBytes();
      requestLines.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath());
      boolean isHold = exchange.getRequestMethod().equals("POST");
      String body =
          "{\"holdId\":\""
              + holdIdToAnswer
              + "\",\"eventId\":1,\"seat\":\"A-7\",\"state\":\"held\","
              + "\"expiresAt\":\"2026-11-11T09:05:00Z\"}";
      if (isHold) {
        holdsAnswered.incrementAndGet();
      }
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(isHold ? 201 : 200, bytes.length);
      try (OutputStream stream = exchange.getResponseBody()) {
        stream.write(bytes);
      }
    }
  }
```

`answer` is the stand-in's handler. It reads the request body, records the method and the raw path (`getRawPath()` keeps the text as sent), builds a hold body around `holdIdToAnswer`, counts holds, and answers 201 for a `POST` and 200 for anything else.

```java
  /** A client whose every send fails with a programming error, as a harness bug would. */
  private static final class FaultyClient extends HttpClient {
    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      throw new IllegalArgumentException("a stand-in fault");
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      throw new UnsupportedOperationException("sendAsync");
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> handler,
        HttpResponse.PushPromiseHandler<T> promises) {
      throw new UnsupportedOperationException("sendAsync");
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
      throw new UnsupportedOperationException("sslContext");
    }

    @Override
    public SSLParameters sslParameters() {
      throw new UnsupportedOperationException("sslParameters");
    }

    @Override
    public Optional<Authenticator> authenticator() {
      return Optional.empty();
    }

    @Override
    public Version version() {
      return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
      return Optional.empty();
    }
  }
```

`FaultyClient` extends the abstract class `HttpClient`. An **abstract class** declares methods without a body; a class that extends it must supply every one. Only `send` matters: it throws `IllegalArgumentException("a stand-in fault")`, the kind of programming error a harness bug would raise. The others are there because Java requires them: the two `sendAsync` methods throw `UnsupportedOperationException` (the harness never calls them), the getters return "nothing set" (`Optional.empty()`, `Redirect.NEVER`, `Version.HTTP_1_1`), and the two TLS getters throw. `<T>` before a method's return type makes the method **generic**: it works for any body type `T`.

```java
  private HarnessSettings settings(
      int ratePerSecond, int warmupMillis, int durationMillis, int holdPercent) {
    Properties properties = new Properties();
    properties.setProperty("run.label", "load-test");
    properties.setProperty("target.url", "http://127.0.0.1:" + seatwave.getAddress().getPort());
    properties.setProperty("load.ratePerSecond", Integer.toString(ratePerSecond));
    properties.setProperty("load.warmupMillis", Integer.toString(warmupMillis));
    properties.setProperty("load.durationMillis", Integer.toString(durationMillis));
    properties.setProperty("load.holdPercent", Integer.toString(holdPercent));
    // Every request may be in flight at once: none is "not sent", whatever the machine's load.
    properties.setProperty("load.maxInFlight", "10000");
    properties.setProperty("load.connectTimeoutMillis", "5000");
    properties.setProperty("load.requestTimeoutMillis", "30000");
    properties.setProperty("load.seed", "1");
    properties.setProperty("watch.balancers", "lb1=127.0.0.1:21005");
    properties.setProperty("watch.backend", "seatwave");
    properties.setProperty("watch.intervalMillis", "100");
    properties.setProperty("report.victim", "none");
    properties.setProperty("report.timelineMillis", "1000");
    return switch (HarnessSettings.from(new SettingsReader(properties))) {
      case Result.Success<HarnessSettings, List<String>>(HarnessSettings checked) -> checked;
      case Result.Error<HarnessSettings, List<String>>(List<String> problems) ->
          throw new AssertionError(problems.toString());
    };
  }
}
```

`settings(rate, warmup, duration, holdPercent)` builds checked settings through the real `HarnessSettings.from`, like `RunReportTest.settings` below. The comment on `load.maxInFlight=10000` explains it: every request may be in flight at once, so no request is ever "not sent", whatever the machine's load, and the counts stay exact. The watch keys are required by the reader but unused here.

### `harness/BalancerWatchTest.java`

New in 1.1.0. `BalancerWatch` reads the balancer's table at intervals during a run. It keeps the first table and the last one, so the report can subtract the balancer's own counters (requests, retries).

```java
package io.plainround.seatwave.balancer.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.balancer.haproxy.FakeBalancer;
import io.plainround.seatwave.balancer.haproxy.NamedBalancer;
import io.plainround.seatwave.balancer.haproxy.StatRow;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BalancerWatchTest {

  // Longer than the test: the watch's loop reads once, and only stop() can end its wait.
  private static final Duration NEVER_AGAIN = Duration.ofSeconds(60);
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
```

The imports and two durations. `await` comes from Awaitility (`awaitility.md`), a library for waiting until a condition holds. `NEVER_AGAIN` is 60 s, longer than the test: the comment explains that the watch reads once, then waits for its interval, and only `stop()` ends that wait. `TIMEOUT` is 10 s, the most any wait in the test may take.

```java
  @Test
  void readsOnceMoreAfterTheLoadEndedSoTheCountersIncludeItsLastRequests() throws Exception {
    AtomicInteger readings = new AtomicInteger();
    // The backend's request counter grows with every reading, as a balancer under load does.
    try (FakeBalancer balancer =
        new FakeBalancer(command ->
            "# pxname,svname,stot,status,\nseatwave,BACKEND,"
                + readings.incrementAndGet()
                + ",UP,\n")) {
      BalancerWatch watch = new BalancerWatch(named(balancer), "seatwave", NEVER_AGAIN, 0);
      Thread thread = Thread.ofPlatform().start(watch);
      await().atMost(TIMEOUT).until(() -> readings.get() == 1);

      watch.stop();
      thread.join(TIMEOUT);

      List<StatRow> rows = watch.backendRows().orElseThrow();
      assertThat(rows.getFirst().totalSessions()).isEqualTo(1);
      assertThat(rows.getLast().totalSessions()).isEqualTo(2);
    }
  }
```

**`readsOnceMoreAfterTheLoadEndedSoTheCountersIncludeItsLastRequests`.**

1. The fake balancer answers with a table whose `BACKEND` row has `stot` (total requests) equal to the number of readings so far: 1, then 2. The comment compares it to a balancer under load, whose counter grows.
2. The watch runs on its own thread (`Thread.ofPlatform().start(watch)`). `await().atMost(TIMEOUT).until(() -> readings.get() == 1)` waits until the first reading has happened.
3. `watch.stop()` asks it to end and waits for its last reading. `thread.join(TIMEOUT)` waits for the thread to finish.
4. `backendRows()` returns the first and last backend rows. The first must show 1 request, the last 2.

The last row can show 2 only if the watch read once more after `stop()`. That last reading is what lets the report's retry counters include the last requests of the load. The mutation check removes it, the last row equals the first, and this test fails.

```java
  private static NamedBalancer named(FakeBalancer balancer) {
    return switch (NamedBalancer.parse(
        List.of("lb1=127.0.0.1:" + balancer.port()), Duration.ofSeconds(2))) {
      case Result.Success<List<NamedBalancer>, String>(List<NamedBalancer> parsed) ->
          parsed.getFirst();
      case Result.Error<List<NamedBalancer>, String>(String reason) ->
          throw new AssertionError(reason);
    };
  }
}
```

`named(balancer)` turns the fake's port into a `NamedBalancer` through the real parser, with a 2-second timeout. The `switch` takes the `Result` apart; an error throws `AssertionError`, so a broken fixture fails loudly.

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
  private static final LoadRun.Mix NO_MIX = new LoadRun.Mix(0, 0, 0, 0);
```

The imports, the two time constants, and `NO_MIX`, a `LoadRun.Mix` of four zeros. `RunReport` now takes the mix as its second argument (for the `mix:` line); tests that do not look at that line pass `NO_MIX`.

```java
  @Test
  void countsTrafficSharePerInstanceAndPerBalancer() {
    RunReport report =
        new RunReport(
            settings("none"),
            NO_MIX,
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
        new RunReport(settings("seatwave-2"), NO_MIX, outcomes, changes, Map.of())
            .killAnalysis()
            .orElseThrow();

    assertThat(analysis.removedNanos() - analysis.lastAnswerNanos()).isEqualTo(6 * SECOND);
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
- Expected: removal time minus last answer is exactly 6 seconds (`removedNanos() - lastAnswerNanos()`, in nanoseconds; version 1.0.0 compared a rounded `detectionMillis` instead); one failure was sent in between (the one at 3 s); one after (the one at 9 s); the removal was by `lb1`; and the victim's share of the answers up to its last answer is 0.5 (one of two).

```java
  @Test
  void ignoresARemovalBeforeTheLastAnswerAndSaysSo() {
    List<StateChange> changes =
        List.of(new StateChange(SECOND, "lb1", "seatwave-2", "UP", "DRAIN"));
    RunReport report =
        new RunReport(
            settings("seatwave-2"),
            NO_MIX,
            List.of(answer(2 * SECOND, "seatwave-2")),
            changes,
            Map.of());

    assertThat(report.killAnalysis().orElseThrow().isRemoved()).isFalse();
    assertThat(report.render()).contains("no balancer took it out after its last answer");
  }
```

Test 3. The status change (`UP` to `DRAIN` at 1 s) happened **before** the victim's last answer (2 s). The analysis must say it was not removed (`isRemoved()` is false) and the rendered report must contain `no balancer took it out after its last answer`. Only a removal after the last answer counts.

```java
  @Test
  void hasNoKillAnalysisWithoutAVictim() {
    RunReport report =
        new RunReport(
            settings("none"), NO_MIX, List.of(answer(0, "seatwave-1")), List.of(), Map.of());

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
        new RunReport(settings("none"), NO_MIX, outcomes, List.of(), Map.of())
            .longestGap()
            .orElseThrow();

    assertThat(gap.endNanos() - gap.startNanos()).isEqualTo(4 * SECOND);
    assertThat(gap.failedInside()).isEqualTo(1);
  }
```

Test 5, the longest gap. Answers at 1, 2 and 6 seconds and a failed request sent at 3 s. The longest gap is the one from 2 to 6 s, 4 seconds, and one failed request was sent inside it.

```java
  @Test
  void saysNoFailuresAndAllFailuresDifferently() {
    String none =
        new RunReport(
                settings("none"), NO_MIX, List.of(answer(0, "seatwave-1")), List.of(), Map.of())
            .render();
    String all =
        new RunReport(
                settings("none"),
                NO_MIX,
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
            NO_MIX,
            List.of(Outcome.failure(-SECOND, -SECOND + MILLI, "HTTP 503"), answer(0, "seatwave-1")),
            List.of(),
            Map.of());

    assertThat(report.render()).contains("sent 1, answered 1, failed 0");
  }
```

Test 7. A failure that began at minus one second (the warm-up) and one answer at time zero. The report must say `sent 1, answered 1, failed 0`: the warm-up failure is left out of every number. Without the filter in the constructor it would say `failed 1`.

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
                NO_MIX,
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
  @Test
  void printsALittlesLawLineWhoseFactorsMultiplyOut() {
    // One answer in three from the victim, then 100 s until the removal: the unrounded factors
    // would give 33,333, which the printed 0.3333 and 100.000 do not multiply to.
    List<Outcome> outcomes =
        List.of(
            answer(SECOND, "seatwave-1"),
            answer(SECOND + MILLI, "seatwave-3"),
            answer(SECOND + 2 * MILLI, "seatwave-2"));
    long lastNanos = SECOND + 2 * MILLI;
    List<StateChange> changes =
        List.of(new StateChange(lastNanos + 100 * SECOND, "lb1", "seatwave-2", "UP 1/3", "DOWN"));

    String text = report("seatwave-2", outcomes, changes).render();

    assertThat(text)
        .contains("taken out by lb1 at +101.002 s: 100000.0 ms after its last answer")
        .contains("1000 requests/s x 0.3333 share x 100.000 s = 33330");
  }
```

Test 9, **`printsALittlesLawLineWhoseFactorsMultiplyOut`**, new in 1.1.0. **Little's Law** says that the number of items inside a system equals the arrival rate times the time each one spends there. The report uses it as a forecast: requests per second, times the victim's share, times the seconds until its removal, is the number of requests that went to the victim while it was dead.

The data: one answer each from `seatwave-1`, `seatwave-3` and `seatwave-2`, a millisecond apart, so the victim's share is one third; then the removal exactly 100 s after the victim's last answer. The rate of the test settings is 1,000.

- `taken out by lb1 at +101.002 s: 100000.0 ms after its last answer` pins the removal line.
- `1000 requests/s x 0.3333 share x 100.000 s = 33330` pins the forecast. The comment explains the trap: with the unrounded share, one third, the product is 33,333, which a reader cannot get by multiplying the printed 0.3333 and 100.000. The report now multiplies the rounded factors it prints. The mutation check puts back the unrounded factors, and this test fails.

```java
  @Test
  void countsAFailureSentAtTheMomentOfRemovalAsSentInBetween() {
    List<Outcome> outcomes =
        List.of(
            answer(2 * SECOND, "seatwave-2"),
            Outcome.failure(5 * SECOND, 5 * SECOND + MILLI, "HTTP 503"),
            Outcome.failure(5 * SECOND + 1, 5 * SECOND + MILLI, "HTTP 503"));
    List<StateChange> changes =
        List.of(new StateChange(5 * SECOND, "lb1", "seatwave-2", "UP 1/3", "DOWN"));

    RunReport.KillAnalysis analysis =
        report("seatwave-2", outcomes, changes).killAnalysis().orElseThrow();

    assertThat(analysis.failedBetween()).isEqualTo(1);
    assertThat(analysis.failedAfter()).isEqualTo(1);
  }
```

Test 10, **`countsAFailureSentAtTheMomentOfRemovalAsSentInBetween`**, new in 1.1.0. The victim's last answer is at 2 s and its removal at exactly 5 s. One failure was sent at exactly 5 s, one a nanosecond later (`5 * SECOND + 1`). The first counts as sent in between (the bound includes the moment of removal), the second as after. This is a **boundary test**: it sits exactly on the edge where `<=` and `<` differ. The mutation check changes `<=` to `<`, and this test fails.

```java
  @Test
  void takesOnlyAChangeIntoTheOutStatesAsARemoval() {
    // DRAIN to DOWN is a change between two out-of-rotation states: the instance was already out.
    List<StateChange> changes =
        List.of(
            new StateChange(SECOND, "lb1", "seatwave-2", "UP", "DRAIN"),
            new StateChange(5 * SECOND, "lb1", "seatwave-2", "DRAIN", "DOWN"));

    RunReport.KillAnalysis analysis =
        report("seatwave-2", List.of(answer(2 * SECOND, "seatwave-2")), changes)
            .killAnalysis()
            .orElseThrow();

    assertThat(analysis.isRemoved()).isFalse();
  }
```

Test 11, **`takesOnlyAChangeIntoTheOutStatesAsARemoval`**, new in 1.1.0. `DRAIN`, `DOWN` and `MAINT` are the **out** states: the balancer sends no new request to the server. The changes here are `UP` to `DRAIN` at 1 s (before the last answer at 2 s, so it does not count) and `DRAIN` to `DOWN` at 5 s. The second one comes after the last answer, but it moves between two out states: the instance was already out, so it is not a removal. `isRemoved()` must be false. The mutation check makes any change into an out state a removal, ignoring where it came from, and this test fails.

```java
  @Test
  void readsTheMedianAndTheP99ByNearestRank() {
    // Latencies of 1, 2 and 3 ms: the nearest rank of 50% is the 2nd value, of 99% the 3rd.
    List<Outcome> outcomes =
        List.of(
            Outcome.response(0, MILLI, 200, "seatwave-1", "lb1"),
            Outcome.response(0, 2 * MILLI, 200, "seatwave-1", "lb1"),
            Outcome.response(0, 3 * MILLI, 200, "seatwave-1", "lb1"));

    String text = report("none", outcomes, List.of()).render();

    assertThat(text).contains("latency of answered requests: p50 2.0 ms, p99 3.0 ms, max 3.0 ms");
  }
```

Test 12, **`readsTheMedianAndTheP99ByNearestRank`**, new in 1.1.0. A **percentile** answers "how slow was the slowest p percent?". The **median** (p50) is the middle value; the **p99** is the value that 99 percent of answers did not exceed. The **nearest-rank** method sorts the values and takes the one at position `ceil(p x n)`, counting from 1. With the latencies 1, 2 and 3 ms: p50 is position `ceil(1.5) = 2`, the value 2 ms; p99 is position `ceil(2.97) = 3`, the value 3 ms. The report must print `p50 2.0 ms, p99 3.0 ms, max 3.0 ms`. The mutation check uses `floor` in place of `ceil`, the median becomes 1 ms, and this test fails.

```java
  @Test
  void printsTheMixThatReallyRan() {
    RunReport report =
        new RunReport(
            settings("none"),
            new LoadRun.Mix(45, 55, 3, 2),
            List.of(answer(0, "seatwave-1")),
            List.of(),
            Map.of());

    assertThat(report.render())
        .contains(
            "mix: 45 holds (POST), 55 reads (GET), of which 3 of a hold not made yet; 2 hold"
                + " replies the harness could not read");
  }
```

Test 13, **`printsTheMixThatReallyRan`**, new in 1.1.0. A mix of 45 holds, 55 reads, 3 reads of a hold not made yet and 2 unreadable hold replies must appear as the line `mix: 45 holds (POST), 55 reads (GET), of which 3 of a hold not made yet; 2 hold replies the harness could not read`. The line lets a reader of a run labelled "50% holds" see whether it was.

```java
  @Test
  void countsEveryStatusBelowFiveHundredAsAnAnswerAndFromFiveHundredAsAFailure() {
    assertThat(Outcome.response(0, MILLI, 409, "seatwave-1", "lb1").kind())
        .isEqualTo(Outcome.Kind.ANSWERED);
    assertThat(Outcome.response(0, MILLI, 499, "seatwave-1", "lb1").kind())
        .isEqualTo(Outcome.Kind.ANSWERED);
    assertThat(Outcome.response(0, MILLI, 500, "", "lb1").kind()).isEqualTo(Outcome.Kind.FAILED);
    assertThat(Outcome.response(0, MILLI, 503, "", "lb1").failure()).isEqualTo("HTTP 503");
  }
```

Test 14, **`countsEveryStatusBelowFiveHundredAsAnAnswerAndFromFiveHundredAsAFailure`**, new in 1.1.0. A status from 400 to 499 means "your request was wrong" (409 is "this seat is taken"): the service worked and answered. A status of 500 or more means the service failed. So 409 and 499 are `ANSWERED`, 500 is `FAILED`, and a 503 failure is named `HTTP 503`. The mutation check counts every status from 400 as a failure, and this test fails. That matters because the labs get thousands of 409s for taken seats; counted as failures, they would hide the real failures of a kill.

```java
  private static RunReport report(
      String victim, List<Outcome> outcomes, List<StateChange> changes) {
    return new RunReport(settings(victim), NO_MIX, outcomes, changes, Map.of());
  }

  private static Outcome answer(long doneNanos, String instance) {
    return Outcome.response(Math.max(0, doneNanos - MILLI), doneNanos, 201, instance, "lb1");
  }
```

`report(victim, outcomes, changes)` is a short form of the constructor with `NO_MIX` and no backend rows. `answer(doneNanos, instance)` makes an answered `Outcome`: sent one millisecond before it was done (but not before zero), status 201, the instance and the balancer `lb1`. A latency of one millisecond (or zero at time zero) makes the numbers simple.

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

What `RunReportTest` still does not check: the timeline's numbers, and the failure percentage when no request was sent at all.

### `json/JsonCodecTest.java`

New in 1.1.0, with `JsonCodec` itself, which came from the Module 9 project's review revision. `JsonCodec` reads a request body (text in **JSON**, JavaScript Object Notation, a format of `{"name": value}` pairs) into a Java **record**, a class that only holds named values. It is strict: a body either matches the record exactly or is refused with a reason that names the field at fault. Both the instance (`HoldRoutes`) and the harness (`LoadRun`) use it.

```java
package io.plainround.seatwave.balancer.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import org.junit.jupiter.api.Test;

class JsonCodecTest {

  private final JsonCodec json = new JsonCodec();
```

The imports and one codec shared by the tests of the class. `JsonCodec` keeps no state between calls, so sharing it is safe.

```java
  @Test
  void bindsABodyThatMatchesTheRecordExactly() {
    Result<Ticket, JsonCodec.BindingProblem> read =
        json.read("{\"count\":2,\"name\":\"arena\"}", Ticket.class);

    assertThat(read.discardError()).contains(new Ticket(2, "arena"));
  }
```

**`bindsABodyThatMatchesTheRecordExactly`.** `{"count":2,"name":"arena"}` matches the record `Ticket(int count, String name)` defined at the end of the file. `discardError()` turns the `Result` into an `Optional` holding the record on success, and `contains(new Ticket(2, "arena"))` checks it. Records compare by their values, so a new `Ticket` with the same values is equal.

```java
  @Test
  void refusesAnEmptyBodyAndABodyThatIsNotAnObject() {
    assertThat(problem("")).isEqualTo(whole("the body is empty; send a JSON object"));
    assertThat(problem("[1]")).isEqualTo(whole("the body must be a JSON object"));
  }
```

**`refusesAnEmptyBodyAndABodyThatIsNotAnObject`.** An empty body gives `the body is empty; send a JSON object`. `[1]` is valid JSON but a list, not an object: `the body must be a JSON object`. Both problems are about the whole body; the helper `whole` builds a `BindingProblem` with the empty pointer.

```java
  @Test
  void refusesDataAfterTheObjectWhetherOrNotItIsJson() {
    assertThat(problem("{\"count\":2,\"name\":\"a\"} {}"))
        .isEqualTo(whole("the body has data after the JSON object"));
    assertThat(problem("{\"count\":2,\"name\":\"a\"} x"))
        .isEqualTo(whole("the body has data after the JSON object"));
  }
```

**`refusesDataAfterTheObjectWhetherOrNotItIsJson`.** A valid object followed by a second one (`{} `) or by text that is not JSON at all (` x`) must both give `the body has data after the JSON object`. A lenient reader would stop after the first object and ignore the rest, so two clients could mean different things by the same body. The second case matters because a naive check would call `x` "not valid JSON", which hides the real reason.

```java
  @Test
  void namesAFieldThatAppearsTwice() {
    assertThat(problem("{\"count\":1,\"count\":2,\"name\":\"a\"}"))
        .isEqualTo(new JsonCodec.BindingProblem("/count", "appears more than once"));
  }
```

**`namesAFieldThatAppearsTwice`.** `{"count":1,"count":2,...}` names `count` twice. Some readers take the first value, some the last; a balancer and a service could disagree. It must be refused with the pointer `/count` and the reason `appears more than once`. A **JSON pointer** (RFC 6901) names a place in a document: `/count` is the field `count` of the top object, and the empty pointer is the whole document.

```java
  @Test
  void namesAnUnknownFieldAWrongTypeANullNumberAndANumberOutOfRange() {
    assertThat(problem("{\"count\":1,\"name\":\"a\",\"a/b\":1}"))
        .isEqualTo(new JsonCodec.BindingProblem("/a~1b", "is not a field of this request"));
    assertThat(problem("{\"count\":\"1\",\"name\":\"a\"}"))
        .isEqualTo(new JsonCodec.BindingProblem("/count", "has the wrong type"));
    assertThat(problem("{\"count\":1,\"name\":5}"))
        .isEqualTo(new JsonCodec.BindingProblem("/name", "has the wrong type"));
    assertThat(problem("{\"count\":null,\"name\":\"a\"}"))
        .isEqualTo(new JsonCodec.BindingProblem("/count", "has the wrong type"));
    assertThat(problem("{\"count\":99999999999999999999,\"name\":\"a\"}"))
        .isEqualTo(new JsonCodec.BindingProblem("/count", "is out of range for this field"));
  }
```

**`namesAnUnknownFieldAWrongTypeANullNumberAndANumberOutOfRange`.** Five bodies, five precise reasons.

1. An extra field named `a/b`: `is not a field of this request`, at the pointer `/a~1b`. Inside a pointer, `/` separates steps, so a `/` inside a name is written `~1` (and a `~` is written `~0`).
2. `"count":"1"`, a string where a number is expected: `has the wrong type`. No silent conversion of `"1"` to 1.
3. `"name":5`, a number where text is expected: `has the wrong type`.
4. `"count":null`: `has the wrong type`. An `int` cannot hold "nothing".
5. `"count":99999999999999999999`: valid JSON, but far above the largest `int` (2,147,483,647): `is out of range for this field`.

```java
  @Test
  void callsMalformedTextInvalidJson() {
    assertThat(problem("{\"count\":")).isEqualTo(whole("the body is not valid JSON"));
    assertThat(problem("{count:1}")).isEqualTo(whole("the body is not valid JSON"));
  }
```

**`callsMalformedTextInvalidJson`.** A body cut off after `"count":`, and `{count:1}` (a field name without quotes, which JSON does not allow), both give `the body is not valid JSON` for the whole body.

```java
  @Test
  void writesARecordAndRefusesAValueThatIsNotWritable() {
    assertThat(json.write(new Ticket(3, "x"))).isEqualTo("{\"count\":3,\"name\":\"x\"}");
    assertThatThrownBy(() -> json.write(new Object()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("not writable as JSON");
  }
```

**`writesARecordAndRefusesAValueThatIsNotWritable`.** `write(new Ticket(3, "x"))` must give exactly `{"count":3,"name":"x"}`. A plain `new Object()` has nothing to write, and the codec throws `IllegalArgumentException` with the message `not writable as JSON`: writing an unwritable value is a bug in the program, not a problem of a request.

```java
  private JsonCodec.BindingProblem problem(String body) {
    return switch (json.read(body, Ticket.class)) {
      case Result.Success<Ticket, JsonCodec.BindingProblem>(Ticket bound) ->
          throw new AssertionError("bound " + bound + " from " + body);
      case Result.Error<Ticket, JsonCodec.BindingProblem>(JsonCodec.BindingProblem refused) ->
          refused;
    };
  }

  private static JsonCodec.BindingProblem whole(String detail) {
    return new JsonCodec.BindingProblem("", detail);
  }

  /** A record with one primitive and one text field, the two shapes the codec checks. */
  record Ticket(int count, String name) {}
}
```

Two helpers and the record. `problem(body)` reads the body and returns the problem; if the body was bound instead, it throws `AssertionError` naming what was bound, so a test that expected a refusal fails clearly. `whole(detail)` builds a problem about the whole body (pointer `""`). `Ticket` has one primitive field (`int`) and one text field (`String`), the two shapes the codec checks, as its comment says.

## Try it yourself

Run these in the repository folder. Nothing needs Docker. Timings differ on every run.

**1. Run the harness tests.**

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

That is 27 tests. Add `cleanTest` before `test` if Gradle says the task is up to date. The order of the lines can differ. `--tests 'io.plainround.seatwave.balancer.json.*'` runs the 7 tests of `JsonCodecTest`.

**2. Break the warm-up rule.** In a scratch copy, in `RunReport.java`, change `outcome.sentNanos() >= 0).toList();` to `true).toList();` and run command 1. Two tests fail: `leavesTheWarmUpOutOfEveryNumber` and `runsTheLoadWatchesTheBalancerAndReports` (I ran this change). Undo it.

**3. Break the percentile.** In the same copy, in `rank`, change `Math.ceil` to `Math.floor` and run `--tests '*RunReportTest'`. `readsTheMedianAndTheP99ByNearestRank() FAILED` appears (I ran this change; in version 1.0.0 every test passed with it). Undo it.

**4. Watch the integration test take its time.** Run the single test and look at its time in the test report.

```bash
./gradlew test --tests '*HarnessCliTest.runsTheLoadWatchesTheBalancerAndReports' --console=plain | grep -E 'PASSED|BUILD'
grep -o 'name="runsTheLoadWatchesTheBalancerAndReports()" classname="[^"]*" time="[^"]*"' build/test-results/test/*HarnessCliTest.xml
```

The test takes just over a second of real time, because it really sends load for 1.1 seconds. On my run the test report recorded `time="1.142"`, 1.142 seconds. The build time Gradle prints is longer, because Gradle starts first.

## Mistakes people make

- **A test that depends on how fast the computer is.** These tests use exact counts only where the program makes the counts exact (a schedule computed from the index, every request allowed in flight), and use bounded waits.
- **Asserting on a rounded number that comes from the clock.** `RunReportTest` uses times chosen by hand, so the expected values can be worked out on paper.
- **Testing next to an edge, not on it.** `countsAFailureSentAtTheMomentOfRemovalAsSentInBetween` puts one failure exactly on the moment of removal and one a nanosecond after; only that tells `<=` from `<`.
- **A stand-in that can only succeed.** `LoadRunTest` adds a stopped target, a client that throws, and a reply with an unusable hold id, so the failure paths of `LoadRun` run.
- **Naming a victim that never appears.** In version 1.0.0 `HarnessCliTest` set a victim that never answered, so the removal analysis was empty. The stand-in now answers as the victim, then stops.
- **Fixtures that hide mistakes.** The `settings` helpers go through the real settings reader, so an invalid fixture fails the test instead of silently building an impossible object.
- **Leaving servers and clients running.** Every stand-in is stopped in `@AfterEach`, and `LoadRunTest` closes its HTTP client, even when a test fails.

## Check yourself

1. Why does `startsTheMeasuredPartAtTimeZeroAtARateThatDoesNotDivideASecond` use 3,000 per second? *Answer: 3,000 does not divide a second evenly (333,333.3 ns), so a schedule built from a rounded interval drifts and request 300 falls before time zero; computed from the index, it is due exactly at zero.*
2. Why is `requests.get()` in `HarnessCliTest` expected to be exactly 110? *Answer: 10 warm-up requests (100 ms at 100 per second) plus 100 measured requests.*
3. What does `BalancerWatchTest` prove? *Answer: The watch reads the balancer once more after it is stopped, so the last row includes the requests at the end of the load.*
4. Why must the Little's Law line multiply its rounded factors? *Answer: So that a reader who multiplies the printed numbers (1000 x 0.3333 x 100.000) gets the printed result, 33330.*
5. What pointer does `JsonCodec` give for an unknown field named `a/b`, and why? *Answer: `/a~1b`: in a JSON pointer `/` separates steps, so a `/` inside a name is written `~1`.*

## Further reading

- JUnit User Guide (this repository pins JUnit 6.1.3), "Writing tests", `@BeforeEach`, `@AfterEach`, `@TempDir`: https://docs.junit.org/current/user-guide/
- AssertJ Core documentation, 3.27: https://assertj.github.io/doc/
- Awaitility documentation: https://github.com/awaitility/awaitility/wiki/Usage
- Java SE 25 API, the built-in HTTP server `com.sun.net.httpserver` (module `jdk.httpserver`): https://docs.oracle.com/en/java/javase/25/docs/api/jdk.httpserver/com/sun/net/httpserver/package-summary.html
- Java SE 25 API, `java.net.http.HttpClient`: https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpClient.html
- Java SE 25 API: `java.util.Properties`, `java.util.concurrent.atomic.AtomicInteger`, `java.util.stream.LongStream`: https://docs.oracle.com/en/java/javase/25/docs/api/
- RFC 6901, JSON Pointer: https://www.rfc-editor.org/rfc/rfc6901
- RFC 8259, the JSON format: https://www.rfc-editor.org/rfc/rfc8259
- RFC 9110, HTTP semantics (status codes 4xx and 5xx): https://www.rfc-editor.org/rfc/rfc9110#name-status-codes
