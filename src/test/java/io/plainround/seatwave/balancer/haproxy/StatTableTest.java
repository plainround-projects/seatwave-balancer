package io.plainround.seatwave.balancer.haproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class StatTableTest {

  /**
   * A real answer of lb2 in the lab stack, captured with show stat on 11 November 2026; a check
   * that was running at that moment shows as "* L7OK".
   */
  static String capturedAnswer() throws IOException {
    try (InputStream in = StatTableTest.class.getResourceAsStream("/show-stat-lb2.csv")) {
      return new String(in.readAllBytes(), StandardCharsets.US_ASCII);
    }
  }

  @Test
  void readsTheServersOfABackendByColumnName() throws IOException {
    StatTable table = StatTable.parse(capturedAnswer());

    assertThat(table.servers("seatwave"))
        .extracting(StatRow::server)
        .containsExactly("seatwave-1", "seatwave-2", "seatwave-3");
    assertThat(table.server("seatwave", "seatwave-2").orElseThrow().isUp()).isTrue();
    assertThat(table.server("seatwave", "seatwave-2").orElseThrow().checkStatus()).endsWith("L7OK");
  }

  @Test
  void findsTheBackendSummaryRow() throws IOException {
    StatTable table = StatTable.parse(capturedAnswer());

    assertThat(table.backendSummary("seatwave")).isPresent();
    assertThat(table.backendSummary("nothing")).isEmpty();
    assertThat(table.server("seatwave", "nothing")).isEmpty();
  }

  @Test
  void readsAnEmptyNumberAsZero() throws IOException {
    StatTable table = StatTable.parse("# pxname,svname,scur,status,\nb,s1,,UP 1/3,\n");

    StatRow row = table.server("b", "s1").orElseThrow();
    assertThat(row.currentSessions()).isZero();
    assertThat(row.isUp()).isTrue();
  }

  @Test
  void callsOnlyAPlainUpFullyUp() throws IOException {
    StatTable table =
        StatTable.parse("# pxname,svname,status,\nb,s1,UP,\nb,s2,UP 2/3,\nb,s3,DOWN 1/2,\n");

    assertThat(table.server("b", "s1").orElseThrow().isFullyUp()).isTrue();
    assertThat(table.server("b", "s2").orElseThrow().isFullyUp()).isFalse();
    assertThat(table.server("b", "s2").orElseThrow().isUp()).isTrue();
    assertThat(table.server("b", "s3").orElseThrow().isFullyUp()).isFalse();
  }

  @Test
  void refusesAnAnswerThatIsNotAStatTable() {
    assertThatThrownBy(() -> StatTable.parse("Unknown command: 'show stats'\n"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Unknown command");
    assertThatThrownBy(() -> StatTable.parse(""))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("an empty answer");
  }

  @Test
  void reportsAColumnThatIsNotANumber() throws IOException {
    StatRow row =
        StatTable.parse("# pxname,svname,scur,\nb,s1,many,\n").server("b", "s1").orElseThrow();

    assertThatThrownBy(row::currentSessions)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("scur");
  }
}
