package io.plainround.seatwave.balancer.seatwave;

import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;

/** Settings for a server under test: loopback, any free port, small pools. */
final class TestSettings {

  private static final int BACKLOG = 50;
  private static final int WORKERS = 8;
  private static final int QUEUE = 16;
  private static final int POOL = 2;

  private TestSettings() {}

  static ServiceSettings withDrainDeadline(Duration drainDeadline) {
    return new ServiceSettings(
        InetAddress.getLoopbackAddress(),
        0,
        0,
        BACKLOG,
        WORKERS,
        QUEUE,
        new ServiceSettings.DatabaseSettings(
            "jdbc:h2:mem:unused",
            "seatwave",
            Path.of("unused.password"),
            POOL,
            Duration.ofSeconds(1),
            Duration.ofSeconds(1)),
        drainDeadline);
  }
}
