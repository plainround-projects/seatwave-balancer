package io.plainround.seatwave.balancer.seatwave;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a route answers. Immutable: {@link #withHeader} returns a new reply.
 *
 * @param status the HTTP status
 * @param contentType the media type of the body
 * @param body the bytes, copied on the way in and out
 * @param headers further headers, in order
 */
public record Reply(
    int status, String contentType, byte[] body, List<Map.Entry<String, String>> headers) {

  /** Copies the body and the headers. */
  public Reply {
    Objects.requireNonNull(contentType, "contentType");
    body = Objects.requireNonNull(body, "body").clone();
    headers = List.copyOf(headers);
  }

  /**
   * Returns a JSON reply.
   *
   * @param status the HTTP status
   * @param json the body
   * @return the reply, with {@code application/json}
   */
  public static Reply json(int status, String json) {
    return new Reply(status, "application/json", json.getBytes(StandardCharsets.UTF_8), List.of());
  }

  @Override
  public byte[] body() {
    return body.clone();
  }

  /**
   * Returns the body as text, for tests and logs.
   *
   * @return the body decoded as UTF-8
   */
  public String bodyText() {
    return new String(body, StandardCharsets.UTF_8);
  }

  /**
   * Returns the first value of a header.
   *
   * @param name the header name, matched without regard to case
   * @return the value, or an empty string when the reply has no such header
   */
  public String header(String name) {
    return headers.stream()
        .filter(header -> header.getKey().equalsIgnoreCase(name))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse("");
  }

  /**
   * Adds one header.
   *
   * @param name the header name
   * @param value the header value
   * @return a new reply with the header last
   */
  public Reply withHeader(String name, String value) {
    List<Map.Entry<String, String>> more = new ArrayList<>(headers);
    more.add(Map.entry(name, value));
    return new Reply(status, contentType, body, more);
  }
}
