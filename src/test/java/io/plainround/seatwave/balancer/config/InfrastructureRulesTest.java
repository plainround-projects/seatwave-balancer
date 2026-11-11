package io.plainround.seatwave.balancer.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The rules of haproxy.cfg and compose.yaml that the module's numbers depend on, checked on every
 * build without Docker. Each rule reads one backend's own block, so a value moved to another
 * backend or written in another unit fails it.
 */
class InfrastructureRulesTest {

  private static final Pattern PUBLISHED = Pattern.compile("- \"([0-9.]+):([0-9]+):([0-9]+)\"");
  private static final int FIRST_PORT = 21000;
  private static final int LAST_PORT = 21099;

  @Test
  void checksReadinessEveryTwoSecondsAndNeedsThreeFailures() throws IOException {
    for (String backend : List.of("seatwave", "seatwave_active_only")) {
      String block = backend(backend);

      assertThat(block).contains("http-check send meth GET uri /ready\n");
      assertThat(block)
          .containsPattern("default-server check port 8081 inter 2s fall 3 rise 2(\\s|$)");
    }
  }

  @Test
  void retriesOnlyWhatSeatWaveDidNotServe() throws IOException {
    String production = backend("seatwave");

    assertThat(production).contains("\n  retry-on conn-failure empty-response 503\n");
    assertThat(production).doesNotContain("response-timeout").doesNotContain("junk-response");
    assertThat(production)
        .contains("slowstart 10s")
        .contains("observe layer4 error-limit 3 on-error mark-down");
  }

  @Test
  void keepsTheActiveOnlyBackendFreeOfRetriesAndPassiveChecks() throws IOException {
    String simple = backend("seatwave_active_only");

    assertThat(simple).contains("\n  retries 0\n").contains("\n  retry-on none\n");
    assertThat(simple)
        .doesNotContain("redispatch")
        .doesNotContain("observe")
        .doesNotContain("slowstart");
  }

  @Test
  void letsTheAlgorithmBackendsFollowProductionsChecks() throws IOException {
    for (String backend : List.of("seatwave_leastconn", "seatwave_source", "seatwave_weighted")) {
      assertThat(backend(backend))
          .contains("track seatwave/seatwave-1")
          .contains("track seatwave/seatwave-2")
          .contains("track seatwave/seatwave-3")
          .doesNotContain(" check");
    }
  }

  @Test
  void publishesOnlyBalancerPortsOnLoopbackInsideTheModulesRange() throws IOException {
    String compose = Files.readString(Path.of("compose.yaml"), StandardCharsets.UTF_8);
    Matcher published = PUBLISHED.matcher(compose);
    int count = 0;
    while (published.find()) {
      count++;
      assertThat(published.group(1)).isEqualTo("127.0.0.1");
      assertThat(Integer.parseInt(published.group(2))).isBetween(FIRST_PORT, LAST_PORT);
      // The container side is a balancer port: requests 8080 to 8083, status 8404, runtime 9999.
      assertThat(published.group(3)).isIn("8080", "8081", "8082", "8083", "8404", "9999");
    }
    assertThat(count).isEqualTo(12);
    for (String service : List.of("postgres", "seatwave-1", "seatwave-2", "seatwave-3")) {
      assertThat(serviceBlock(compose, service)).doesNotContain("ports:");
    }
  }

  @Test
  void pinsEveryImageByTagAndDigest() throws IOException {
    String compose = Files.readString(Path.of("compose.yaml"), StandardCharsets.UTF_8);
    String dockerfile = Files.readString(Path.of("Dockerfile"), StandardCharsets.UTF_8);
    String keepalived = Files.readString(Path.of("keepalived/Dockerfile"), StandardCharsets.UTF_8);

    assertThat(compose).contains("haproxy:3.4.7@sha256:").contains("postgres:18.6@sha256:");
    assertThat(dockerfile)
        .containsPattern("FROM eclipse-temurin:25\\.0\\.5_8-jdk-noble@sha256:[0-9a-f]{64}");
    assertThat(keepalived).containsPattern("FROM alpine:3\\.24\\.4@sha256:[0-9a-f]{64}");
    assertThat(compose)
        .doesNotContain("privileged: true")
        .doesNotContain("network_mode: host")
        .doesNotContain("docker.sock");
  }

  private static String backend(String name) throws IOException {
    String config =
        Files.readString(Path.of(System.getProperty("seatwave.haproxy")), StandardCharsets.UTF_8);
    int start = config.indexOf("\nbackend " + name + "\n");
    assertThat(start).as("backend %s in haproxy.cfg", name).isNotNegative();
    int end = config.indexOf("\n\n", start + 1);
    return config.substring(start, end < 0 ? config.length() : end) + "\n";
  }

  private static String serviceBlock(String compose, String service) {
    int start = compose.indexOf("\n  " + service + ":\n");
    assertThat(start).as("service %s in compose.yaml", service).isNotNegative();
    int end = compose.indexOf("\n\n", start + 1);
    return compose.substring(start, end);
  }
}
