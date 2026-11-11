package io.plainround.seatwave.balancer.deploy;

import io.plainround.seatwave.balancer.haproxy.NamedBalancer;
import io.plainround.seatwave.balancer.haproxy.RuntimeCommand;
import io.plainround.seatwave.balancer.haproxy.StatTable;
import java.io.IOException;
import java.util.Objects;

/**
 * One HAProxy reached through its runtime API. Thread-safe: it holds only immutable fields.
 *
 * @param balancer the balancer and its API address
 */
record RuntimeBalancer(NamedBalancer balancer) implements BalancerControl {

  /** Checks the balancer. */
  RuntimeBalancer {
    Objects.requireNonNull(balancer, "balancer");
  }

  @Override
  public String name() {
    return balancer.name();
  }

  @Override
  public StatTable stat() throws IOException {
    return balancer.api().showStat();
  }

  @Override
  public void setState(String backend, String server, RuntimeCommand.AdminState state)
      throws IOException {
    balancer.api().setState(backend, server, state);
  }
}
