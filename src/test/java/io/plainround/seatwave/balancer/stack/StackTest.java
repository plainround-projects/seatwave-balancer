package io.plainround.seatwave.balancer.stack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.plainround.seatwave.balancer.haproxy.RuntimeApi;
import io.plainround.seatwave.balancer.haproxy.RuntimeCommand.AdminState;
import io.plainround.seatwave.balancer.haproxy.StatRow;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The module's claims against the running stack (scripts/stack.sh test): three stateless instances
 * behind both balancers, the database's one-owner rule across instances, the Idempotency-Key rule
 * on PostgreSQL itself, and a drain at the balancer that stops new requests to one instance. Never
 * part of ./gradlew check. Every port is read from compose.yaml, the file that publishes it.
 */
@Tag("stack")
class StackTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  // haproxy.cfg's slowstart: an instance carries its full share only this long after it came up.
  private static final long SLOW_START_SECONDS = 10;
  private static final Duration SETTLED_WITHIN = Duration.ofSeconds(60);
  private static final int ROUNDS = 30;
  // Module 1's sections hold 1,000 seats each.
  private static final int SEATS_PER_SECTION = 1_000;
  private static final List<String> BACKENDS = List.of("seatwave", "seatwave_active_only");
  private static final List<String> INSTANCES = List.of("seatwave-1", "seatwave-2", "seatwave-3");
  private static final Pattern HOLD_ID = Pattern.compile("\"holdId\":\"([0-9a-f-]{36})\"");
  private static String lb1;
  private static String lb2;
  private static RuntimeApi lb1Api;
  private static RuntimeApi lb2Api;
  private static HttpClient client;

  @BeforeAll
  static void connect() throws IOException {
    String compose = Files.readString(Path.of("compose.yaml"), StandardCharsets.UTF_8);
    lb1 = "http://127.0.0.1:" + publishedPort(compose, "lb1", 8080);
    lb2 = "http://127.0.0.1:" + publishedPort(compose, "lb2", 8080);
    lb1Api = api(publishedPort(compose, "lb1", 9999));
    lb2Api = api(publishedPort(compose, "lb2", 9999));
    client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  }

  @AfterAll
  static void disconnect() {
    client.close();
  }

  @Test
  void bothBalancersSeeThreeInstancesFullyUpInBothCheckedBackends() throws Exception {
    for (RuntimeApi api : List.of(lb1Api, lb2Api)) {
      for (String backend : BACKENDS) {
        assertThat(api.showStat().servers(backend))
            .as(backend)
            .hasSize(INSTANCES.size())
            .allMatch(StatRow::isFullyUp);
      }
    }
  }

  @Test
  void readsAHoldThroughEveryInstanceWhicheverMadeIt() throws Exception {
    // Round robin gives every instance its share only once slow start is over everywhere.
    await().atMost(SETTLED_WITHIN).until(() -> isSettled(lb2Api));
    String holdId = freeHold();

    Set<String> readers = new HashSet<>();
    for (int round = 0; round < ROUNDS; round++) {
      HttpResponse<String> read = get(lb2 + "/v1/holds/" + holdId);
      assertThat(read.statusCode()).isEqualTo(200);
      readers.add(read.headers().firstValue("X-Served-By").orElseThrow());
    }

    assertThat(readers).containsExactlyInAnyOrderElementsOf(INSTANCES);
  }

  @Test
  void letsOneOfTwoInstancesWinOneSeat() throws Exception {
    // lb1 may send only to seatwave-1, lb2 only to seatwave-2: the two requests race on two
    // different instances, and only the database can keep the seat to one owner.
    String seat = freeSeat("R");
    try {
      drainAllBut(lb1Api, "seatwave-1");
      drainAllBut(lb2Api, "seatwave-2");
      CompletableFuture<HttpResponse<String>> first =
          holdAsync(lb1, seat, "\"stack-" + UUID.randomUUID() + "\"");
      CompletableFuture<HttpResponse<String>> second =
          holdAsync(lb2, seat, "\"stack-" + UUID.randomUUID() + "\"");
      List<HttpResponse<String>> both = List.of(first.join(), second.join());

      assertThat(both)
          .extracting(response -> response.headers().firstValue("X-Served-By").orElseThrow())
          .containsExactly("seatwave-1", "seatwave-2");
      assertThat(both).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 409);
    } finally {
      readyEverywhere(lb1Api);
      readyEverywhere(lb2Api);
    }
  }

  @Test
  void answersTwoRacingCopiesOfOneRequestWithOneHoldOnPostgresql() throws Exception {
    // The same key and body through both balancers at once: one seat, so the second request waits
    // for the first one's row lock and then finds its hold (the re-read after a lost race).
    String seat = freeSeat("S");
    String key = "\"stack-" + UUID.randomUUID() + "\"";

    CompletableFuture<HttpResponse<String>> first = holdAsync(lb1, seat, key);
    CompletableFuture<HttpResponse<String>> second = holdAsync(lb2, seat, key);

    assertThat(List.of(first.join(), second.join()))
        .allMatch(response -> response.statusCode() == 201)
        .extracting(response -> holdIdOf(response.body()))
        .containsOnly(holdIdOf(first.join().body()));
  }

  @Test
  void refusesARacingKeyReusedForAnotherSeatAndKeepsThatSeatFreeOnPostgresql() throws Exception {
    // One key, two seats: no row lock orders the two, so the loser meets the unique key (SQLState
    // 23505) or the winner's row, and is refused; its seat is rolled back and stays free.
    int number = seatNumber();
    String seatOne = "P-" + number;
    // The next seat of the section, so the two always differ.
    String seatTwo = "P-" + (number % SEATS_PER_SECTION + 1);
    String key = "\"stack-" + UUID.randomUUID() + "\"";

    CompletableFuture<HttpResponse<String>> first = holdAsync(lb1, seatOne, key);
    CompletableFuture<HttpResponse<String>> second = holdAsync(lb2, seatTwo, key);
    HttpResponse<String> one = first.join();
    HttpResponse<String> two = second.join();

    assertThat(List.of(one.statusCode(), two.statusCode())).containsExactlyInAnyOrder(201, 422);
    String loserSeat = one.statusCode() == 422 ? seatOne : seatTwo;
    assertThat(hold(lb1, loserSeat, "\"stack-" + UUID.randomUUID() + "\"").statusCode())
        .isEqualTo(201);
  }

  @Test
  void sendsNothingNewToADrainedInstance() throws Exception {
    lb1Api.setState("seatwave", "seatwave-3", AdminState.DRAIN);
    try {
      Set<String> servers = new HashSet<>();
      for (int round = 0; round < ROUNDS; round++) {
        servers.add(
            hold(lb1, freeSeat("T"), "\"stack-" + UUID.randomUUID() + "\"")
                .headers()
                .firstValue("X-Served-By")
                .orElseThrow());
      }
      assertThat(servers).containsExactlyInAnyOrder("seatwave-1", "seatwave-2");
    } finally {
      lb1Api.setState("seatwave", "seatwave-3", AdminState.READY);
    }
  }

  private static boolean isSettled(RuntimeApi api) throws IOException {
    return api.showStat().servers("seatwave").stream()
        .allMatch(row -> row.isFullyUp() && row.secondsSinceChange() >= SLOW_START_SECONDS);
  }

  private static void drainAllBut(RuntimeApi api, String kept) throws IOException {
    for (String instance : INSTANCES) {
      if (!instance.equals(kept)) {
        api.setState("seatwave", instance, AdminState.DRAIN);
      }
    }
  }

  private static void readyEverywhere(RuntimeApi api) throws IOException {
    for (String instance : INSTANCES) {
      api.setState("seatwave", instance, AdminState.READY);
    }
  }

  private static String freeHold() throws Exception {
    // A seat of section Q that another test or a manual hold took is skipped: seats are tried until
    // one is free.
    for (int attempt = 0; attempt < ROUNDS; attempt++) {
      Matcher holdId =
          HOLD_ID.matcher(
              hold(lb1, "Q-" + seatNumber(), "\"stack-" + UUID.randomUUID() + "\"").body());
      if (holdId.find()) {
        return holdId.group(1);
      }
    }
    throw new AssertionError("no free seat in section Q within " + ROUNDS + " attempts");
  }

  private static String freeSeat(String section) {
    // scripts/lab.sh gives every seat back after each lab run, and each test here has a section of
    // its own (P to T), so a seat drawn at random is free.
    return section + "-" + seatNumber();
  }

  private static String holdIdOf(String body) {
    Matcher holdId = HOLD_ID.matcher(body);
    assertThat(holdId.find()).as("a hold id in %s", body).isTrue();
    return holdId.group(1);
  }

  private static HttpResponse<String> hold(String balancer, String seat, String key)
      throws Exception {
    return client.send(holdRequest(balancer, seat, key), HttpResponse.BodyHandlers.ofString());
  }

  private static CompletableFuture<HttpResponse<String>> holdAsync(
      String balancer, String seat, String key) {
    return client.sendAsync(holdRequest(balancer, seat, key), HttpResponse.BodyHandlers.ofString());
  }

  private static HttpRequest holdRequest(String balancer, String seat, String key) {
    return HttpRequest.newBuilder(URI.create(balancer + "/v1/holds"))
        .timeout(TIMEOUT)
        .header("Content-Type", "application/json")
        .header("Idempotency-Key", key)
        .POST(
            HttpRequest.BodyPublishers.ofString(
                "{\"eventId\":1,\"seat\":\"" + seat + "\",\"customer\":\"stack-test\"}"))
        .build();
  }

  private static HttpResponse<String> get(String url) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static int seatNumber() {
    // Any of a section's 1,000 seats.
    return 1 + Math.floorMod(UUID.randomUUID().hashCode(), SEATS_PER_SECTION);
  }

  private static RuntimeApi api(int port) {
    return new RuntimeApi(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), TIMEOUT);
  }

  private static int publishedPort(String compose, String service, int containerPort) {
    int start = compose.indexOf("\n  " + service + ":\n");
    int end = compose.indexOf("\n\n", start + 1);
    Matcher published =
        Pattern.compile("\"127\\.0\\.0\\.1:([0-9]+):" + containerPort + "\"")
            .matcher(compose.substring(start, end));
    assertThat(published.find()).as("%s publishes %d", service, containerPort).isTrue();
    return Integer.parseInt(published.group(1));
  }
}
