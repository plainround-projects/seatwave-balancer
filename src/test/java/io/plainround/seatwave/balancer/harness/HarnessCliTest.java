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

  @AfterEach
  void stopStandIns() throws IOException {
    seatwave.stop(0);
    balancer.close();
  }

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
