package io.plainround.seatwave.balancer.haproxy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The answer of {@code show stat}: a CSV whose first line starts with {@code "# "} and names the
 * columns, then one line per frontend, backend and server. Columns are found by name, not by
 * position, so a version that adds columns does not break the parser. Immutable.
 *
 * @param rows every row, in HAProxy's order
 */
public record StatTable(List<StatRow> rows) {

  /** Copies the rows. */
  public StatTable {
    rows = List.copyOf(rows);
  }

  /**
   * Parses a {@code show stat} answer.
   *
   * @param csv the answer
   * @return the table
   * @throws IOException if the answer does not start with the header line, which is how the runtime
   *     API reports an unknown command or a permission problem
   */
  public static StatTable parse(String csv) throws IOException {
    Objects.requireNonNull(csv, "csv");
    List<String> lines = csv.lines().filter(line -> !line.isBlank()).toList();
    if (lines.isEmpty() || !lines.getFirst().startsWith("# ")) {
      String first = lines.isEmpty() ? "an empty answer" : lines.getFirst();
      throw new IOException("not a show stat answer: " + first);
    }
    // HAProxy ends every line with a comma, so split keeps a last empty column; -1 keeps empties.
    List<String> names = Arrays.asList(lines.getFirst().substring(2).split(",", -1));
    List<StatRow> rows = new ArrayList<>();
    for (String line : lines.subList(1, lines.size())) {
      String[] cells = line.split(",", -1);
      Map<String, String> values = new HashMap<>();
      for (int column = 0; column < names.size() && column < cells.length; column++) {
        values.put(names.get(column), cells[column]);
      }
      rows.add(new StatRow(values));
    }
    return new StatTable(rows);
  }

  /**
   * Returns the servers of one backend, without its BACKEND summary row.
   *
   * @param backend the backend's name
   * @return its server rows, in HAProxy's order; empty if there is no such backend
   */
  public List<StatRow> servers(String backend) {
    return rows.stream()
        .filter(row -> row.proxy().equals(backend))
        .filter(row -> !row.server().equals("BACKEND") && !row.server().equals("FRONTEND"))
        .toList();
  }

  /**
   * Returns one server's row.
   *
   * @param backend the backend
   * @param server the server
   * @return the row, or empty if there is no such server
   */
  public Optional<StatRow> server(String backend, String server) {
    return servers(backend).stream().filter(row -> row.server().equals(server)).findFirst();
  }

  /**
   * Returns a backend's summary row.
   *
   * @param backend the backend
   * @return the BACKEND row, or empty if there is no such backend
   */
  public Optional<StatRow> backendSummary(String backend) {
    return rows.stream()
        .filter(row -> row.proxy().equals(backend) && row.server().equals("BACKEND"))
        .findFirst();
  }
}
