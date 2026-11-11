package io.plainround.seatwave.balancer.seatwave;

import java.util.Objects;

/**
 * One request as the routes see it: the parts of the HTTP exchange they need, already copied out of
 * the JDK's server types, so no route can touch the connection.
 *
 * @param method the HTTP method
 * @param path the raw path, without the query
 * @param rawQuery the raw query, or an empty string
 * @param contentType the {@code Content-Type} header, or an empty string
 * @param idempotencyKey the {@code Idempotency-Key} header, or an empty string
 * @param body the body, at most {@link HoldRoutes#MAX_BODY_BYTES} bytes, copied on the way in and
 *     out
 * @param isBodyTooLarge true when the client sent more than the limit; the body is then empty
 * @param correlationId the id written in every log line of this request
 */
public record Request(
    String method,
    String path,
    String rawQuery,
    String contentType,
    String idempotencyKey,
    byte[] body,
    boolean isBodyTooLarge,
    String correlationId) {

  /** Checks and copies the fields. */
  public Request {
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(rawQuery, "rawQuery");
    Objects.requireNonNull(contentType, "contentType");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    body = Objects.requireNonNull(body, "body").clone();
    Objects.requireNonNull(correlationId, "correlationId");
  }

  @Override
  public byte[] body() {
    return body.clone();
  }
}
