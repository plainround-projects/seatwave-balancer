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

  @Test
  void startsTheMeasuredPartAtTimeZeroAtARateThatDoesNotDivideASecond() throws Exception {
    // 3,000 a second: 333,333.3 ns apart. 100 ms of warm-up are 300 requests, so request 300 is
    // due exactly at time zero and belongs to the measured part.
    List<Outcome> outcomes = run(settings(3_000, 100, 100, 50));

    assertThat(outcomes).hasSize(600);
    assertThat(outcomes).filteredOn(outcome -> outcome.sentNanos() >= 0).hasSize(300);
  }

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

  @Test
  void countsAFaultOfTheHarnessItselfAsAFailedRequest() throws Exception {
    HarnessSettings settings = settings(1_000, 0, 20, 50);
    LoadRun load = new LoadRun(settings, new FaultyClient(), System.nanoTime(), "load-test");

    List<Outcome> outcomes = load.run();

    assertThat(outcomes)
        .hasSize(20)
        .allMatch(outcome -> outcome.failure().equals("harness fault IllegalArgumentException"));
  }

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
