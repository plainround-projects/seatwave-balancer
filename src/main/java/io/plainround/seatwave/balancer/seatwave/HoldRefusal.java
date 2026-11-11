package io.plainround.seatwave.balancer.seatwave;

/** Why a hold was not granted: the expected business failures of {@code POST /v1/holds}. */
public sealed interface HoldRefusal {

  /** Another active hold or a booking owns the seat. */
  record SeatTaken() implements HoldRefusal {}

  /** The event or the seat does not exist in the database. */
  record NoSuchSeat() implements HoldRefusal {}

  /** The Idempotency-Key was already used with a different request (Module 7). */
  record KeyReused() implements HoldRefusal {}
}
