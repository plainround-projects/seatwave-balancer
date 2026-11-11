package io.plainround.seatwave.balancer.deploy;

import io.plainround.seatwave.balancer.haproxy.RuntimeCommand;
import io.plainround.seatwave.balancer.haproxy.StatTable;
import java.io.IOException;

/**
 * What the deploy needs from one balancer: read its servers, and set a server's administrative
 * state. The real one talks to HAProxy's runtime API; the tests use one they script, because
 * HAProxy is not something this project owns.
 */
interface BalancerControl {

  /**
   * Returns the balancer's name.
   *
   * @return the name, such as {@code lb1}
   */
  String name();

  /**
   * Reads every row of {@code show stat}.
   *
   * @return the table
   * @throws IOException if the balancer does not answer
   */
  StatTable stat() throws IOException;

  /**
   * Sets a server's administrative state.
   *
   * @param backend the backend
   * @param server the server
   * @param state the state
   * @throws IOException if the balancer does not answer or refuses
   */
  void setState(String backend, String server, RuntimeCommand.AdminState state) throws IOException;
}
