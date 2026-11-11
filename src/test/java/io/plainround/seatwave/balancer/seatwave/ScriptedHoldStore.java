package io.plainround.seatwave.balancer.seatwave;

import com.github.sviperll.result4j.Result;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A hold store the tests steer: it can keep a hold request open until released (to have a request
 * in flight during a drain), be "down", and answer readiness as told. Thread-safe.
 */
final class ScriptedHoldStore implements HoldStore {

  private static final long LONGEST_HOLD_SECONDS = 30;
  static final Instant EXPIRES = Instant.parse("2026-11-11T09:05:00Z");

  private final CountDownLatch release = new CountDownLatch(1);
  private final CountDownLatch entered = new CountDownLatch(1);
  private final AtomicBoolean isBlocking = new AtomicBoolean();
  private final AtomicBoolean isDown = new AtomicBoolean();
  private final AtomicBoolean isReachable = new AtomicBoolean(true);

  void blockHolds() {
    isBlocking.set(true);
  }

  void releaseHolds() {
    release.countDown();
  }

  boolean awaitHoldEntered() throws InterruptedException {
    return entered.await(LONGEST_HOLD_SECONDS, TimeUnit.SECONDS);
  }

  void goDown() {
    isDown.set(true);
  }

  void setReachable(boolean reachable) {
    isReachable.set(reachable);
  }

  @Override
  public Result<GrantedHold, HoldRefusal> hold(HoldCommand command)
      throws StoreUnavailableException {
    if (isDown.get()) {
      throw new StoreUnavailableException("the test store is down", new IllegalStateException());
    }
    entered.countDown();
    if (isBlocking.get()) {
      try {
        if (!release.await(LONGEST_HOLD_SECONDS, TimeUnit.SECONDS)) {
          throw new StoreUnavailableException("never released", new IllegalStateException());
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new StoreUnavailableException("interrupted", interrupted);
      }
    }
    if (command.seat().equals("T-1000")) {
      return Result.error(new HoldRefusal.SeatTaken());
    }
    if (command.seat().equals("T-999")) {
      return Result.error(new HoldRefusal.KeyReused());
    }
    if (command.seat().equals("T-998")) {
      return Result.error(new HoldRefusal.NoSuchSeat());
    }
    return Result.success(
        new GrantedHold(
            new Hold("11111111-2222-3333-4444-555555555555", 1, command.seat(), "fan-1", EXPIRES),
            false));
  }

  @Override
  public Optional<Hold> find(String holdId) throws StoreUnavailableException {
    if (isDown.get()) {
      throw new StoreUnavailableException("the test store is down", new IllegalStateException());
    }
    return holdId.startsWith("11111111")
        ? Optional.of(new Hold(holdId, 1, "A-7", "fan-1", EXPIRES))
        : Optional.empty();
  }

  @Override
  public boolean isReachable() {
    return isReachable.get();
  }
}
