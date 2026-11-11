package io.plainround.seatwave.balancer.haproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuntimeApiTest {

  @Test
  void sendsOneCommandAndReadsTheWholeAnswer() throws Exception {
    String captured = StatTableTest.capturedAnswer();
    try (FakeBalancer balancer = new FakeBalancer(command -> captured)) {
      StatTable table = balancer.api().showStat();

      assertThat(table.servers("seatwave")).hasSize(3);
      assertThat(balancer.commands()).containsExactly("show stat");
    }
  }

  @Test
  void setsAServerStateAndAcceptsTheEmptyAnswer() throws Exception {
    try (FakeBalancer balancer = new FakeBalancer(command -> "\n")) {
      balancer.api().setState("seatwave", "seatwave-2", RuntimeCommand.AdminState.DRAIN);

      assertThat(balancer.commands()).containsExactly("set server seatwave/seatwave-2 state drain");
    }
  }

  @Test
  void reportsHaproxysRefusal() throws Exception {
    try (FakeBalancer balancer = new FakeBalancer(command -> "No such server.\n")) {
      assertThatThrownBy(
              () -> balancer.api().setState("seatwave", "nope", RuntimeCommand.AdminState.READY))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("No such server.");
    }
  }

  @Test
  void refusesAnythingThatWouldAddASecondCommand() {
    assertThatThrownBy(() -> new RuntimeCommand("show stat; shutdown sessions"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> RuntimeCommand.setState("seatwave\nshow", "s1", RuntimeCommand.AdminState.MAINT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("backend must match [A-Za-z0-9_-]{1,64}, but was \"seatwave\\u000ashow\"");
  }

  @Test
  void refusesAZeroTimeout() {
    assertThatThrownBy(() -> new RuntimeApi(new InetSocketAddress(1), Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void readsBalancersFromSettingsAndNamesABadEntry() {
    Result<List<NamedBalancer>, String> good =
        NamedBalancer.parse(
            List.of("lb1=10.210.10.21:9999", "lb2=127.0.0.1:21015"), Duration.ofSeconds(1));
    Result<List<NamedBalancer>, String> bad =
        NamedBalancer.parse(List.of("lb1=10.210.10.21:99999"), Duration.ofSeconds(1));
    Result<List<NamedBalancer>, String> notAddress =
        NamedBalancer.parse(List.of("lb1=999.210.10.21:9999"), Duration.ofSeconds(1));

    assertThat(good).isInstanceOf(Result.Success.class);
    assertThat(bad)
        .isEqualTo(
            Result.error(
                "'lb1=10.210.10.21:99999' is not name=address:port, such as lb1=10.210.10.21:9999"));
    assertThat(notAddress)
        .isEqualTo(Result.error("'lb1=999.210.10.21:9999' does not hold an IP address"));
  }

  @Test
  void refusesTheShortAndPaddedFormsOfAnEntry() {
    String shortForm = "lb1=127.0.1:9999";
    String paddedPort = "lb1=127.0.0.1:09999";
    String portZero = "lb1=127.0.0.1:0";

    assertThat(NamedBalancer.parse(List.of(shortForm), Duration.ofSeconds(1)))
        .isEqualTo(
            Result.error(
                "'lb1=127.0.1:9999' does not hold a dotted IPv4 address with four parts and no"
                    + " padding"));
    assertThat(NamedBalancer.parse(List.of(paddedPort), Duration.ofSeconds(1)))
        .isInstanceOf(Result.Error.class);
    assertThat(NamedBalancer.parse(List.of(portZero), Duration.ofSeconds(1)))
        .isInstanceOf(Result.Error.class);
  }
}
