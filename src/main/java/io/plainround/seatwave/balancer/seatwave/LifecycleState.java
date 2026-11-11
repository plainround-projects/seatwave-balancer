package io.plainround.seatwave.balancer.seatwave;

/**
 * Where one SeatWave instance is in its life, in the only order it can move: {@code STARTING} to
 * {@code READY} to {@code DRAINING} to {@code STOPPED}. A shutdown signal during start goes from
 * {@code STARTING} straight to {@code DRAINING}.
 */
public enum LifecycleState {
  /**
   * The listeners are open, but the instance has not said it can serve; every request is refused.
   */
  STARTING,
  /** The instance admits new requests and its readiness check can pass. */
  READY,
  /** Shutdown has begun: no new request is admitted, the ones in flight are finishing. */
  DRAINING,
  /** In-flight requests reached zero, or the drain deadline passed; the listeners are closing. */
  STOPPED
}
