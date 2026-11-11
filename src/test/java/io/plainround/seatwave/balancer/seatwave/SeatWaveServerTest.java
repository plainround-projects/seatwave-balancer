package io.plainround.seatwave.balancer.seatwave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SeatWaveServerTest {

  private static final Duration DEADLINE = Duration.ofSeconds(10);
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  // Short, so the deadline test ends in a fraction of a second instead of the server's 5 s.
  private static final Duration WORKER_STOP_WAIT = Duration.ofMillis(100);
  private static final String BODY = "{\"eventId\":1,\"seat\":\"A-7\",\"customer\":\"fan-1\"}";

  private final ScriptedHoldStore store = new ScriptedHoldStore();
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  private SeatWaveServer server;

  @AfterEach
  void stop() {
    store.releaseHolds();
    if (server != null) {
      server.drainAndStop(Duration.ZERO);
    }
    client.close();
  }

  @Test
  void refusesRequestsUntilMarkedReady() throws Exception {
    start(DEADLINE);

    HttpResponse<String> response = hold("\"k-1\"");

    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.body()).contains("starting");
    assertThat(admin("/ready").statusCode()).isEqualTo(503);
  }

  @Test
  void servesAndNamesTheInstanceOnceReady() throws Exception {
    start(DEADLINE);
    server.markReady();

    HttpResponse<String> response = hold("\"k-1\"");

    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.headers().firstValue("X-Served-By")).contains("seatwave-test");
  }

  @Test
  void answersHealthAndReadinessAsTwoQuestions() throws Exception {
    start(DEADLINE);
    server.markReady();
    store.setReachable(false);

    assertThat(admin("/health").statusCode()).isEqualTo(200);
    assertThat(admin("/ready").statusCode()).isEqualTo(503);
    assertThat(admin("/ready").body()).contains("database-unreachable");
    store.setReachable(true);
    assertThat(admin("/ready").statusCode()).isEqualTo(200);
    assertThat(admin("/other").statusCode()).isEqualTo(404);
  }

  @Test
  void answersAHeadRequestLikeAGetWithoutABody() throws Exception {
    start(DEADLINE);
    server.markReady();

    HttpResponse<String> health =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.adminPort() + "/health"))
                .timeout(TIMEOUT)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(health.statusCode()).isEqualTo(200);
    assertThat(health.body()).isEmpty();
  }

  @Test
  void countsRequestsInItsMetrics() throws Exception {
    start(DEADLINE);
    server.markReady();
    hold("\"k-1\"");

    // The server counts a request after it has written the answer, so the client can read the
    // metrics a moment before the count; wait for it instead of racing it.
    await()
        .atMost(TIMEOUT)
        .until(() -> admin("/metrics").body().contains("seatwave_request_seconds_count 1"));
    String metrics = admin("/metrics").body();

    assertThat(metrics)
        .contains("seatwave_requests_total{route=\"POST /v1/holds\",status=\"201\"} 1")
        .contains("seatwave_state{state=\"ready\"} 1")
        .contains("seatwave_request_seconds_count 1");
  }

  @Test
  void finishesTheRequestInFlightAndRefusesNewOnesWhileDraining() throws Exception {
    start(DEADLINE);
    server.markReady();
    store.blockHolds();
    CompletableFuture<HttpResponse<String>> inFlight = holdAsync("\"in-flight\"");
    assertThat(store.awaitHoldEntered()).isTrue();

    CompletableFuture<Lifecycle.DrainResult> drained =
        CompletableFuture.supplyAsync(() -> server.drainAndStop(DEADLINE));
    await().atMost(TIMEOUT).until(() -> server.state() == LifecycleState.DRAINING);
    HttpResponse<String> refused = hold("\"new\"");
    store.releaseHolds();

    assertThat(refused.statusCode()).isEqualTo(503);
    assertThat(refused.headers().firstValue("Connection")).contains("close");
    assertThat(refused.body()).contains("nothing was done");
    assertThat(inFlight.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).statusCode()).isEqualTo(201);
    Lifecycle.DrainResult result = drained.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    assertThat(result.isClean()).isTrue();
    // Woken by the last request's release, not by the 10 s deadline (a lost wake-up is also clean).
    assertThat(result.waited()).isLessThan(DEADLINE.dividedBy(2));
    assertThat(server.notAdmittedCount()).isEqualTo(1);
  }

  @Test
  void stopsAtTheDeadlineWhenARequestNeverFinishes() throws Exception {
    server =
        SeatWaveServer.start(
            TestSettings.withDrainDeadline(Duration.ofMillis(200)),
            "seatwave-test",
            store,
            new MutableClock(Instant.parse("2026-11-11T09:00:00Z")),
            WORKER_STOP_WAIT);
    server.markReady();
    store.blockHolds();
    holdAsync("\"stuck\"");
    assertThat(store.awaitHoldEntered()).isTrue();

    Lifecycle.DrainResult result = server.drainAndStop(Duration.ofMillis(200));

    assertThat(result.inFlightLeft()).isEqualTo(1);
    assertThat(result.waited()).isGreaterThanOrEqualTo(Duration.ofMillis(200));
    assertThat(server.state()).isEqualTo(LifecycleState.STOPPED);
  }

  private void start(Duration deadline) throws IOException {
    server =
        SeatWaveServer.start(
            TestSettings.withDrainDeadline(deadline),
            "seatwave-test",
            store,
            new MutableClock(Instant.parse("2026-11-11T09:00:00Z")));
  }

  private HttpResponse<String> hold(String key) throws Exception {
    return client.send(holdRequest(key), HttpResponse.BodyHandlers.ofString());
  }

  private CompletableFuture<HttpResponse<String>> holdAsync(String key) {
    return client.sendAsync(holdRequest(key), HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest holdRequest(String key) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/holds"))
        .timeout(TIMEOUT)
        .header("Content-Type", "application/json")
        .header("Idempotency-Key", key)
        .POST(HttpRequest.BodyPublishers.ofString(BODY))
        .build();
  }

  private HttpResponse<String> admin(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.adminPort() + path))
            .timeout(TIMEOUT)
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
