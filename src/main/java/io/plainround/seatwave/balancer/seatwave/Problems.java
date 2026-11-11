package io.plainround.seatwave.balancer.seatwave;

import io.plainround.seatwave.balancer.json.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The one error shape of SeatWave version 1: problem details (RFC 9457), with the {@code type} URIs
 * of Module 7, so a client acts on the type and the status and never on the wording. Thread-safe:
 * the codec is.
 */
final class Problems {

  // Documentation names under the reserved .example domain (RFC 2606), as Module 7 uses them.
  private static final String TYPE_BASE = "https://seatwave.example/problems/";

  private final JsonCodec json;

  Problems(JsonCodec json) {
    this.json = Objects.requireNonNull(json, "json");
  }

  Reply of(int status, String slug, String title, String detail, String correlationId) {
    ProblemBody problem =
        new ProblemBody(
            TYPE_BASE + slug, title, status, detail, "urn:seatwave:request:" + correlationId);
    return new Reply(
        status,
        "application/problem+json",
        json.write(problem).getBytes(StandardCharsets.UTF_8),
        List.of(Map.entry("Cache-Control", "no-store")));
  }

  /** The JSON body; record components are written in this order. */
  record ProblemBody(String type, String title, int status, String detail, String instance) {}
}
