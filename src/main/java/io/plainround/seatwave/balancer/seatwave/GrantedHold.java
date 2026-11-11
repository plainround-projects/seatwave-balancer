package io.plainround.seatwave.balancer.seatwave;

import java.util.Objects;

/**
 * A hold the store granted.
 *
 * @param hold the hold
 * @param isReplay true when this request's Idempotency-Key had already made the hold, possibly on
 *     another instance, so nothing new was written
 */
public record GrantedHold(Hold hold, boolean isReplay) {

  /** Checks the hold. */
  public GrantedHold {
    Objects.requireNonNull(hold, "hold");
  }
}
