package io.plainround.seatwave.balancer.stack;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.balancer.haproxy.RuntimeApi;
import io.plainround.seatwave.balancer.haproxy.RuntimeCommand.AdminState;
import io.plainround.seatwave.balancer.haproxy.StatRow;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The module's claims against the running stack (scripts/stack.sh test): three stateless instances
 * behind both balancers, the database's one-owner rule across instances, and a drain at the
 * balancer that stops new requests to one instance. Never part of ./gradlew check.
 */
@Tag("stack")
class StackTest {

  private static final String LB1 = "http://127.0.0.1:21000";
  private static final String LB2 = "http://127.0.0.1:21010";
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final int ROUNDS = 30;
  // Module 1's sections hold 1,000 seats each.
  private static final int SEATS_PER_SECTION = 1_000;
  private static final Pattern HOLD_ID = Pattern.compile("\"holdId\":\"([0-9a-f-]{36})\"");
  private static final RuntimeApi LB1_API =
      new RuntimeApi(new InetSocketAddress(InetAddress.getLoopbackAddress(), 21005), TIMEOUT);
  private static final RuntimeApi LB2_API =
      new RuntimeApi(new InetSocketAddress(InetAddress.getLoopbackAddress(), 21015), TIMEOUT);
  private static HttpClient client;

  @BeforeAll
  static void connect() {
    client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  }

  @AfterAll
  static void disconnect() {
    client.close();
  }

  @Test
  void bothBalancersSeeThreeInstancesUp() throws Exception {
    for (RuntimeApi api : new RuntimeApi[] {LB1_API, LB2_API}) {
      assertThat(api.showStat().servers("seatwave")).hasSize(3).allMatch(StatRow::isUp);
    }
  }

  @Test
  void readsAHoldThroughEveryInstanceWhicheverMadeIt() throws Exception {
    String holdId = freeHold();

    Set<String> readers = new HashSet<>();
    for (int round = 0; round < ROUNDS; round++) {
      HttpResponse<String> read = get(LB2 + "/v1/holds/" + holdId);
      assertThat(read.statusCode()).isEqualTo(200);
      readers.add(read.headers().firstValue("X-Served-By").orElseThrow());
    }

    assertThat(readers).containsExactlyInAnyOrder("seatwave-1", "seatwave-2", "seatwave-3");
  }

  @Test
  void letsOneOfTwoInstancesWinOneSeat() throws Exception {
    String seat = "R-" + seatNumber();

    HttpResponse<String> first = hold(LB1, seat, "\"stack-" + UUID.randomUUID() + "\"");
    HttpResponse<String> second = hold(LB2, seat, "\"stack-" + UUID.randomUUID() + "\"");

    // The seat may already be held by a lab run; either way the second request must lose, and two
    // 201s for one seat would be the double booking the database's conditional UPDATE forbids.
    assertThat(first.statusCode()).isIn(201, 409);
    assertThat(second.statusCode()).isEqualTo(409);
  }

  @Test
  void sendsNothingNewToADrainedInstance() throws Exception {
    LB1_API.setState("seatwave", "seatwave-3", AdminState.DRAIN);
    try {
      Set<String> servers = new HashSet<>();
      for (int round = 0; round < ROUNDS; round++) {
        servers.add(
            hold(LB1, "S-" + seatNumber(), "\"stack-" + UUID.randomUUID() + "\"")
                .headers()
                .firstValue("X-Served-By")
                .orElseThrow());
      }
      assertThat(servers).containsExactlyInAnyOrder("seatwave-1", "seatwave-2");
    } finally {
      LB1_API.setState("seatwave", "seatwave-3", AdminState.READY);
    }
  }

  private static String freeHold() throws Exception {
    // A lab run may have held many seats for 300 s, so seats are tried until one is free.
    for (int attempt = 0; attempt < ROUNDS; attempt++) {
      Matcher holdId =
          HOLD_ID.matcher(
              hold(LB1, "Q-" + seatNumber(), "\"stack-" + UUID.randomUUID() + "\"").body());
      if (holdId.find()) {
        return holdId.group(1);
      }
    }
    throw new AssertionError("no free seat in section Q within " + ROUNDS + " attempts");
  }

  private static HttpResponse<String> hold(String balancer, String seat, String key)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(balancer + "/v1/holds"))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", key)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"eventId\":1,\"seat\":\"" + seat + "\",\"customer\":\"stack-test\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> get(String url) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static int seatNumber() {
    // A seat the labs are unlikely to hold: sections Q, R and S, any of their 1,000 seats.
    return 1 + Math.floorMod(UUID.randomUUID().hashCode(), SEATS_PER_SECTION);
  }
}
