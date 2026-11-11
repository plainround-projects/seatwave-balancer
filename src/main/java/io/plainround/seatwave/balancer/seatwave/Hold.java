package io.plainround.seatwave.balancer.seatwave;

import java.time.Instant;
import java.util.Objects;

/**
 * One seat held by one customer until a moment, as the shared database recorded it.
 *
 * @param holdId the hold's identifier, a random UUID
 * @param eventId the event, Module 1's event 1
 * @param seat the seat label, for example {@code A-7}
 * @param customer the customer's identifier as the client sent it
 * @param expiresAt when the hold ends, Module 1's 300 seconds after it began
 */
public record Hold(String holdId, long eventId, String seat, String customer, Instant expiresAt) {

  /** Checks that no field is missing. */
  public Hold {
    Objects.requireNonNull(holdId, "holdId");
    Objects.requireNonNull(seat, "seat");
    Objects.requireNonNull(customer, "customer");
    Objects.requireNonNull(expiresAt, "expiresAt");
  }

  /**
   * Says whether the hold still blocks its seat.
   *
   * @param now the current time
   * @return true until the expiry moment
   */
  public boolean isActiveAt(Instant now) {
    return now.isBefore(expiresAt);
  }
}
