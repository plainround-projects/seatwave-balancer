package io.plainround.seatwave.balancer.seatwave;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The module's invariant in one place: an instance admits no new request after it starts draining,
 * and it stops only when its in-flight count reaches zero or a stated deadline passes.
 *
 * <p>The state and the in-flight count change under one lock, so "is it still ready?" and "count
 * this request" are a single step: no request can be counted after draining began, and the drain
 * cannot miss a request that was counted just before. The server's one request handler asks {@link
 * #tryAdmit} before it reads a byte of the request, and no other code path reaches the routes.
 *
 * <p>Thread-safe. The lock is held for a few instructions only, never across I/O.
 */
public final class Lifecycle {

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition idle = lock.newCondition();
  private LifecycleState state = LifecycleState.STARTING;
  private long inFlight;

  /**
   * Admits one request if the instance is ready.
   *
   * @return an admission to close when the response has been written, or empty when the instance is
   *     starting, draining or stopped; the caller must then refuse the request without doing any of
   *     its work
   */
  public Optional<Admission> tryAdmit() {
    lock.lock();
    try {
      if (state != LifecycleState.READY) {
        return Optional.empty();
      }
      inFlight++;
      return Optional.of(new Admission(this));
    } finally {
      lock.unlock();
    }
  }

  /**
   * Moves from starting to ready.
   *
   * @return true if the instance is now ready; false if shutdown began first, which wins
   */
  public boolean markReady() {
    lock.lock();
    try {
      if (state == LifecycleState.STARTING) {
        state = LifecycleState.READY;
      }
      return state == LifecycleState.READY;
    } finally {
      lock.unlock();
    }
  }

  /** Starts draining: from now on {@link #tryAdmit} refuses. Calling it again changes nothing. */
  public void startDraining() {
    lock.lock();
    try {
      if (state == LifecycleState.STARTING || state == LifecycleState.READY) {
        state = LifecycleState.DRAINING;
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Waits until no admitted request is left, or the deadline passes, then marks the instance
   * stopped. Starts draining first if nobody did.
   *
   * @param deadline the longest wait; zero means do not wait
   * @return how many requests were still in flight when the wait ended, and how long it took
   * @throws InterruptedException if the waiting thread is interrupted; the state is then stopped
   *     too, because a shutdown that was interrupted is still a shutdown
   */
  public DrainResult awaitDrained(Duration deadline) throws InterruptedException {
    Objects.requireNonNull(deadline, "deadline");
    if (deadline.isNegative()) {
      throw new IllegalArgumentException("the drain deadline cannot be negative: " + deadline);
    }
    long startNanos = System.nanoTime();
    long remainingNanos = deadline.toNanos();
    lock.lock();
    try {
      if (state == LifecycleState.STARTING || state == LifecycleState.READY) {
        state = LifecycleState.DRAINING;
      }
      // A loop, because a condition can wake up without a signal; awaitNanos returns what is left.
      while (inFlight > 0 && remainingNanos > 0) {
        remainingNanos = idle.awaitNanos(remainingNanos);
      }
      return new DrainResult(inFlight, Duration.ofNanos(System.nanoTime() - startNanos));
    } finally {
      state = LifecycleState.STOPPED;
      lock.unlock();
    }
  }

  /**
   * Returns the current state.
   *
   * @return the state at the moment of the call
   */
  public LifecycleState state() {
    lock.lock();
    try {
      return state;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns the number of admitted requests whose response is not written yet.
   *
   * @return the in-flight count at the moment of the call
   */
  public long inFlight() {
    lock.lock();
    try {
      return inFlight;
    } finally {
      lock.unlock();
    }
  }

  private void release() {
    lock.lock();
    try {
      inFlight--;
      if (inFlight == 0) {
        idle.signalAll();
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * One admitted request. Closing it ends the request's part of the in-flight count; closing it
   * twice counts once, so a handler's {@code finally} and a try-with-resources cannot both count.
   */
  public static final class Admission implements AutoCloseable {

    private final Lifecycle lifecycle;
    private final AtomicBoolean isClosed = new AtomicBoolean();

    private Admission(Lifecycle lifecycle) {
      this.lifecycle = lifecycle;
    }

    @Override
    public void close() {
      if (isClosed.compareAndSet(false, true)) {
        lifecycle.release();
      }
    }
  }

  /**
   * How a drain ended.
   *
   * @param inFlightLeft requests still running when the wait ended: zero after a clean drain, more
   *     when the deadline passed first
   * @param waited how long the wait took
   */
  public record DrainResult(long inFlightLeft, Duration waited) {

    /** Checks the fields. */
    public DrainResult {
      if (inFlightLeft < 0) {
        throw new IllegalArgumentException("inFlightLeft cannot be negative: " + inFlightLeft);
      }
      Objects.requireNonNull(waited, "waited");
    }

    /**
     * Says whether every admitted request finished before the deadline.
     *
     * @return true when nothing was left in flight
     */
    public boolean isClean() {
      return inFlightLeft == 0;
    }

    /**
     * Returns the wait in whole milliseconds, for a log line.
     *
     * @return the wait, rounded down
     */
    public long waitedMillis() {
      return TimeUnit.NANOSECONDS.toMillis(waited.toNanos());
    }
  }
}
