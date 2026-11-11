package io.plainround.seatwave.balancer.seatwave;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A checked request to hold one seat: every field has passed the contract's rules, so a store never
 * sees an invalid value.
 *
 * @param idempotencyKey Module 7's {@code Idempotency-Key}, an RFC 9651 string with its quotes
 * @param fingerprint the SHA-256 of the request's meaning, so a key reused for another seat is
 *     caught
 * @param eventId the event, 1 or more
 * @param seat the seat label: a section letter A to T, a hyphen and a number 1 to 1000
 * @param customer the customer: 1 to 64 letters, digits, {@code -} or {@code _}
 */
public record HoldCommand(
    String idempotencyKey, String fingerprint, long eventId, String seat, String customer) {

  // Module 7: an Idempotency-Key is an RFC 9651 String, a quoted value of printable characters.
  static final Pattern IDEMPOTENCY_KEY = Pattern.compile("\"[\\x21\\x23-\\x5B\\x5D-\\x7E]{1,64}\"");
  // Module 1's seat labels: A-1 to T-1000.
  static final Pattern SEAT = Pattern.compile("[A-T]-([1-9][0-9]{0,2}|1000)");
  static final Pattern CUSTOMER = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

  /**
   * Checks every field.
   *
   * @throws IllegalArgumentException if a field breaks its rule; the routes check first, so this is
   *     a programming error
   */
  public HoldCommand {
    require(IDEMPOTENCY_KEY, idempotencyKey, "idempotencyKey");
    require(SHA256_HEX, fingerprint, "fingerprint");
    require(SEAT, seat, "seat");
    require(CUSTOMER, customer, "customer");
    if (eventId < 1) {
      throw new IllegalArgumentException("eventId must be 1 or more, but was " + eventId);
    }
  }

  private static void require(Pattern rule, String value, String name) {
    Objects.requireNonNull(value, name);
    if (!rule.matcher(value).matches()) {
      throw new IllegalArgumentException(name + " must match " + rule.pattern());
    }
  }
}
