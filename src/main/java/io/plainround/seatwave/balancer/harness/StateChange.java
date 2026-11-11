package io.plainround.seatwave.balancer.harness;

import java.util.Objects;

/**
 * A server's status changed in one balancer's view, as the watch saw it.
 *
 * @param atNanos when the watch read the new status, in nanoseconds since the run began
 * @param balancer the balancer's name
 * @param server the server's name, or {@code *} when the balancer itself did not answer
 * @param from the status before
 * @param to the status now
 */
public record StateChange(long atNanos, String balancer, String server, String from, String to) {

  /** Checks the fields. */
  public StateChange {
    Objects.requireNonNull(balancer, "balancer");
    Objects.requireNonNull(server, "server");
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
  }

  /**
   * Says whether this change took the server out of the balancer's rotation.
   *
   * @return true for a change to DOWN, MAINT or DRAIN from a status that was not one of those
   */
  boolean isRemoval() {
    return isOut(to) && !isOut(from);
  }

  private static boolean isOut(String status) {
    return status.startsWith("DOWN") || status.startsWith("MAINT") || status.startsWith("DRAIN");
  }
}
