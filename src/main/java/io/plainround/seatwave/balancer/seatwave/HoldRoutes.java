package io.plainround.seatwave.balancer.seatwave;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.balancer.json.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SeatWave's routes behind the balancer: Module 7's {@code POST /v1/holds} with an {@code
 * Idempotency-Key} and {@code GET /v1/holds/{holdId}}, in Module 7's shapes. Every hold lives in
 * the shared store, so a hold made through one instance is read through any other.
 *
 * <p>Thread-safe: the store and the codec are, and nothing else changes after construction.
 */
final class HoldRoutes {

  /** Module 7's contract limit on a request body. */
  static final int MAX_BODY_BYTES = 1_024;

  private static final Logger LOG = LoggerFactory.getLogger(HoldRoutes.class);
  private static final int OK = 200;
  private static final int CREATED = 201;
  private static final int BAD_REQUEST = 400;
  private static final int NOT_FOUND = 404;
  private static final int METHOD_NOT_ALLOWED = 405;
  private static final int CONFLICT = 409;
  private static final int TOO_LARGE = 413;
  private static final int UNSUPPORTED_MEDIA_TYPE = 415;
  private static final int UNPROCESSABLE = 422;
  private static final int UNAVAILABLE = 503;
  // Seconds a client waits before retrying a 503, as Module 7's problem bodies say.
  private static final String RETRY_AFTER_SECONDS = "1";
  private static final String HOLDS = "/v1/holds";
  private static final Pattern HOLD_PATH =
      Pattern.compile("/v1/holds/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");

  private final HoldStore store;
  private final JsonCodec json;
  private final Problems problems;
  private final Clock clock;

  HoldRoutes(HoldStore store, JsonCodec json, Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.json = Objects.requireNonNull(json, "json");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.problems = new Problems(json);
  }

  /**
   * Answers one admitted request.
   *
   * @param request the request
   * @return the route's template, for the metrics, and the reply
   */
  Routed route(Request request) {
    String path = request.path();
    if (path.equals(HOLDS)) {
      return request.method().equals("POST")
          ? new Routed("POST /v1/holds", hold(request))
          : new Routed("other", notAllowed(request, "POST"));
    }
    Matcher holdPath = HOLD_PATH.matcher(path);
    if (holdPath.matches()) {
      return request.method().equals("GET")
          ? new Routed("GET /v1/holds/{holdId}", readHold(request, holdPath.group(1)))
          : new Routed("other", notAllowed(request, "GET"));
    }
    return new Routed("other", notFound(request));
  }

  /**
   * The answer of an instance that is not admitting requests: {@code 503} with {@code Connection:
   * close}, so the balancer moves to another instance, and a body that says nothing was done.
   *
   * @param state the instance's state
   * @param correlationId the request's id
   * @return the reply
   */
  Reply notAdmitted(LifecycleState state, String correlationId) {
    return problems
        .of(
            UNAVAILABLE,
            "instance-not-ready",
            "This SeatWave instance is not taking requests",
            "the instance is " + state.name().toLowerCase(Locale.ROOT) + "; nothing was done",
            correlationId)
        .withHeader("Retry-After", RETRY_AFTER_SECONDS)
        .withHeader("Connection", "close");
  }

  private Reply hold(Request request) {
    if (request.isBodyTooLarge()) {
      return problems.of(
          TOO_LARGE,
          "body-too-large",
          "The body is over 1,024 bytes",
          "send at most " + MAX_BODY_BYTES + " bytes",
          request.correlationId());
    }
    if (!isJson(request.contentType())) {
      return problems.of(
          UNSUPPORTED_MEDIA_TYPE,
          "unsupported-media-type",
          "The body must be application/json",
          "send Content-Type: application/json",
          request.correlationId());
    }
    if (!HoldCommand.IDEMPOTENCY_KEY.matcher(request.idempotencyKey()).matches()) {
      return invalid(
          request, List.of("Idempotency-Key must be one quoted string of 1 to 64 characters"));
    }
    String body = new String(request.body(), StandardCharsets.UTF_8);
    return switch (json.read(body, HoldRequestBody.class)) {
      case Result.Success<HoldRequestBody, JsonCodec.BindingProblem>(HoldRequestBody parsed) ->
          checked(request, parsed);
      case Result.Error<HoldRequestBody, JsonCodec.BindingProblem>(
              JsonCodec.BindingProblem problem) ->
          invalid(request, List.of(problem.pointer() + " " + problem.detail()));
    };
  }

  private Reply checked(Request request, HoldRequestBody body) {
    List<String> errors = new ArrayList<>();
    if (body.eventId() == null || body.eventId() < 1) {
      errors.add("/eventId must be 1 or more");
    }
    if (body.seat() == null || !HoldCommand.SEAT.matcher(body.seat()).matches()) {
      errors.add("/seat must be a section letter A to T, a hyphen and a number 1 to 1000");
    }
    if (body.customer() == null || !HoldCommand.CUSTOMER.matcher(body.customer()).matches()) {
      errors.add("/customer must be 1 to 64 letters, digits, - or _");
    }
    if (!errors.isEmpty()) {
      return invalid(request, errors);
    }
    HoldCommand command =
        new HoldCommand(
            request.idempotencyKey(),
            fingerprint(body.eventId() + "|" + body.seat() + "|" + body.customer()),
            body.eventId(),
            body.seat(),
            body.customer());
    try {
      return answer(request, store.hold(command));
    } catch (StoreUnavailableException failure) {
      return storeDown(request, failure);
    }
  }

  private Reply answer(Request request, Result<GrantedHold, HoldRefusal> outcome) {
    return switch (outcome) {
      case Result.Success<GrantedHold, HoldRefusal>(GrantedHold granted) -> {
        // A business event, logged once per hold with the request's correlation id.
        LOG.debug(
            "held {} until {}{}",
            granted.hold().seat(),
            granted.hold().expiresAt(),
            granted.isReplay() ? " (replayed for a retried request)" : "");
        yield Reply.json(CREATED, json.write(HoldView.of(granted.hold(), clock)))
            .withHeader("Location", HOLDS + "/" + granted.hold().holdId())
            .withHeader("Cache-Control", "no-store");
      }
      case Result.Error<GrantedHold, HoldRefusal>(HoldRefusal refusal) -> refused(request, refusal);
    };
  }

  private Reply refused(Request request, HoldRefusal refusal) {
    return switch (refusal) {
      case HoldRefusal.SeatTaken taken ->
          problems.of(
              CONFLICT,
              "seat-unavailable",
              "The seat is held or sold",
              "the seat is not available",
              request.correlationId());
      case HoldRefusal.NoSuchSeat missing -> notFound(request);
      case HoldRefusal.KeyReused reused ->
          problems.of(
              UNPROCESSABLE,
              "idempotency-key-reused",
              "This Idempotency-Key was used with another body",
              "use a new key for a new request",
              request.correlationId());
    };
  }

  private Reply readHold(Request request, String holdId) {
    if (!request.rawQuery().isEmpty()) {
      return invalid(request, List.of("this path takes no query parameters"));
    }
    try {
      Optional<Hold> hold = store.find(holdId);
      return hold.map(found ->
              Reply.json(OK, json.write(HoldView.of(found, clock)))
                  // private: one fan's hold, never kept by a shared cache (RFC 9111).
                  .withHeader("Cache-Control", "private, max-age=60"))
          .orElseGet(() -> notFound(request));
    } catch (StoreUnavailableException failure) {
      return storeDown(request, failure);
    }
  }

  private Reply storeDown(Request request, StoreUnavailableException failure) {
    // Fail safe: without the database nobody can say who owns a seat, so refuse with 503.
    LOG.warn("the shared database is unavailable: {}", failure.getMessage());
    return problems
        .of(
            UNAVAILABLE,
            "service-busy",
            "SeatWave cannot reach its database; retry after Retry-After",
            "the shared database did not answer",
            request.correlationId())
        .withHeader("Retry-After", RETRY_AFTER_SECONDS);
  }

  private Reply invalid(Request request, List<String> errors) {
    return problems.of(
        BAD_REQUEST,
        "invalid-request",
        "The request does not match the contract",
        String.join("; ", errors),
        request.correlationId());
  }

  private Reply notFound(Request request) {
    return problems.of(
        NOT_FOUND, "not-found", "No such resource", "no such resource", request.correlationId());
  }

  private Reply notAllowed(Request request, String allowed) {
    return problems
        .of(
            METHOD_NOT_ALLOWED,
            "method-not-allowed",
            "This path does not take this method",
            "use " + allowed,
            request.correlationId())
        .withHeader("Allow", allowed);
  }

  private static boolean isJson(String contentType) {
    // The media type is the part before any parameter (RFC 9110, Section 8.3.1), compared without
    // regard to case: "application/json; charset=utf-8" is JSON, "application/jsonx" is not.
    int semicolon = contentType.indexOf(';');
    String mediaType = semicolon < 0 ? contentType : contentType.substring(0, semicolon);
    return mediaType.strip().equalsIgnoreCase("application/json");
  }

  private static String fingerprint(String meaning) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(meaning.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException missing) {
      // Every Java platform must provide SHA-256, so this is a broken runtime, not bad input.
      throw new IllegalStateException("the JDK has no SHA-256", missing);
    }
  }

  /** The reply of one route, with the route template the metrics count it under. */
  record Routed(String route, Reply reply) {}

  /** The body of POST /v1/holds as sent; boxed, so a missing field reaches the validation step. */
  record HoldRequestBody(Long eventId, String seat, String customer) {}

  /** Module 7's hold as JSON. */
  record HoldView(String holdId, long eventId, String seat, String state, String expiresAt) {
    static HoldView of(Hold hold, Clock clock) {
      return new HoldView(
          hold.holdId(),
          hold.eventId(),
          hold.seat(),
          hold.isActiveAt(clock.instant()) ? "held" : "expired",
          hold.expiresAt().toString());
    }
  }
}
