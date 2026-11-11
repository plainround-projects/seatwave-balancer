package io.plainround.seatwave.balancer.deploy;

import java.io.IOException;

/**
 * Stops and starts one service of the Compose project. The real one runs {@code docker compose};
 * the tests use one that records the calls, because Docker is not something this project owns.
 */
interface Containers {

  /**
   * Stops a service gracefully: SIGTERM, then SIGKILL after the stop timeout.
   *
   * @param service the Compose service
   * @throws IOException if the command fails or does not finish in time
   */
  void stop(String service) throws IOException;

  /**
   * Starts a stopped service again.
   *
   * @param service the Compose service
   * @throws IOException if the command fails or does not finish in time
   */
  void start(String service) throws IOException;
}
