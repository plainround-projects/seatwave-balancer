package io.plainround.seatwave.balancer.seatwave;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A clock the tests move by hand, so expiry is tested without waiting. Thread-safe. */
final class MutableClock extends Clock {

  private final AtomicReference<Instant> now;

  MutableClock(Instant start) {
    this.now = new AtomicReference<>(start);
  }

  void advance(Duration step) {
    now.updateAndGet(instant -> instant.plus(step));
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    throw new UnsupportedOperationException("the tests use UTC only");
  }

  @Override
  public Instant instant() {
    return now.get();
  }
}
