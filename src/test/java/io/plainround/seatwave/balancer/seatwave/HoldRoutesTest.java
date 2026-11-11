package io.plainround.seatwave.balancer.seatwave;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.balancer.json.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class HoldRoutesTest {

  private static final String KEY = "\"key-1\"";
  private static final String BODY = "{\"eventId\":1,\"seat\":\"A-7\",\"customer\":\"fan-1\"}";
  private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-11-11T09:00:00Z"));

  private final ScriptedHoldStore store = new ScriptedHoldStore();
  private final HoldRoutes routes = new HoldRoutes(store, new JsonCodec(), CLOCK);

  @Test
  void createsAHoldWithItsLocation() {
    Reply reply = post("application/json; charset=utf-8", KEY, BODY);

    assertThat(reply.status()).isEqualTo(201);
    assertThat(reply.header("Location"))
        .isEqualTo("/v1/holds/11111111-2222-3333-4444-555555555555");
    assertThat(reply.bodyText()).contains("\"state\":\"held\"", "\"seat\":\"A-7\"");
  }

  @Test
  void refusesAMediaTypeThatOnlyStartsLikeJson() {
    assertThat(post("application/jsonx", KEY, BODY).status()).isEqualTo(415);
  }

  @Test
  void refusesAMissingIdempotencyKey() {
    Reply reply = post("application/json", "", BODY);

    assertThat(reply.status()).isEqualTo(400);
    assertThat(reply.bodyText()).contains("Idempotency-Key");
  }

  @Test
  void refusesABodyOverTheLimit() {
    Request large =
        new Request("POST", "/v1/holds", "", "application/json", KEY, new byte[0], true, "c1");

    assertThat(routes.route(large).reply().status()).isEqualTo(413);
  }

  @Test
  void namesEveryInvalidFieldTogether() {
    Reply reply =
        post("application/json", KEY, "{\"eventId\":0,\"seat\":\"Z-1\",\"customer\":\"a b\"}");

    assertThat(reply.status()).isEqualTo(400);
    assertThat(reply.bodyText()).contains("/eventId", "/seat", "/customer");
  }

  @Test
  void refusesAnUnknownField() {
    Reply reply =
        post(
            "application/json", KEY, "{\"eventId\":1,\"seat\":\"A-7\",\"customer\":\"f\",\"x\":1}");

    assertThat(reply.status()).isEqualTo(400);
    assertThat(reply.bodyText()).contains("/x is not a field");
  }

  @Test
  void answersSeatTakenWithConflict() {
    assertThat(post("application/json", KEY, body("T-1000")).status()).isEqualTo(409);
  }

  @Test
  void answersAReusedKeyWithUnprocessable() {
    assertThat(post("application/json", KEY, body("T-999")).status()).isEqualTo(422);
  }

  @Test
  void answersAMissingSeatWithNotFound() {
    assertThat(post("application/json", KEY, body("T-998")).status()).isEqualTo(404);
  }

  @Test
  void failsSafeWithServiceUnavailableWhenTheStoreIsDown() {
    store.goDown();

    Reply hold = post("application/json", KEY, BODY);
    Reply read = get("/v1/holds/11111111-2222-3333-4444-555555555555", "");

    assertThat(hold.status()).isEqualTo(503);
    assertThat(hold.header("Retry-After")).isEqualTo("1");
    assertThat(read.status()).isEqualTo(503);
  }

  @Test
  void readsAHoldAsPrivate() {
    Reply reply = get("/v1/holds/11111111-2222-3333-4444-555555555555", "");

    assertThat(reply.status()).isEqualTo(200);
    assertThat(reply.header("Cache-Control")).startsWith("private");
  }

  @Test
  void answersNotFoundForAnUnknownHold() {
    assertThat(get("/v1/holds/99999999-2222-3333-4444-555555555555", "").status()).isEqualTo(404);
  }

  @Test
  void refusesAQueryOnAHold() {
    assertThat(get("/v1/holds/11111111-2222-3333-4444-555555555555", "x=1").status())
        .isEqualTo(400);
  }

  @Test
  void answersNotFoundForAnUnknownPathWhateverTheMethod() {
    Request unknown =
        new Request("POST", "/v1/nothing", "", "application/json", KEY, new byte[0], false, "c1");

    assertThat(routes.route(unknown).reply().status()).isEqualTo(404);
  }

  @Test
  void answersMethodNotAllowedWithTheAllowedMethod() {
    Reply onHolds =
        routes.route(new Request("GET", "/v1/holds", "", "", "", new byte[0], false, "c1")).reply();
    Reply onHold =
        routes
            .route(
                new Request(
                    "DELETE",
                    "/v1/holds/11111111-2222-3333-4444-555555555555",
                    "",
                    "",
                    "",
                    new byte[0],
                    false,
                    "c1"))
            .reply();

    assertThat(onHolds.status()).isEqualTo(405);
    assertThat(onHolds.header("Allow")).isEqualTo("POST");
    assertThat(onHold.header("Allow")).isEqualTo("GET");
  }

  @Test
  void refusesWhileNotReadyAndSaysNothingWasDone() {
    Reply reply = routes.notAdmitted(LifecycleState.DRAINING, "c1");

    assertThat(reply.status()).isEqualTo(503);
    assertThat(reply.header("Connection")).isEqualTo("close");
    assertThat(reply.bodyText()).contains("draining; nothing was done");
  }

  private Reply post(String contentType, String key, String body) {
    return routes
        .route(
            new Request(
                "POST",
                "/v1/holds",
                "",
                contentType,
                key,
                body.getBytes(StandardCharsets.UTF_8),
                false,
                "c1"))
        .reply();
  }

  private Reply get(String path, String query) {
    return routes.route(new Request("GET", path, query, "", "", new byte[0], false, "c1")).reply();
  }

  private static String body(String seat) {
    return "{\"eventId\":1,\"seat\":\"" + seat + "\",\"customer\":\"fan-1\"}";
  }
}
