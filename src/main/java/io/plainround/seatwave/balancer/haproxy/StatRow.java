package io.plainround.seatwave.balancer.haproxy;

import java.util.Map;

/**
 * One row of {@code show stat}, read by the column names HAProxy's management guide lists. A
 * missing or empty number reads as zero, which is how HAProxy writes "not counted here". Immutable.
 *
 * @param values the row's cells by column name
 */
public record StatRow(Map<String, String> values) {

  /** Copies the cells. */
  public StatRow {
    values = Map.copyOf(values);
  }

  /**
   * Returns the frontend or backend name ({@code pxname}).
   *
   * @return the name, or an empty string
   */
  public String proxy() {
    return text("pxname");
  }

  /**
   * Returns the server name, or FRONTEND or BACKEND for a summary row ({@code svname}).
   *
   * @return the name, or an empty string
   */
  public String server() {
    return text("svname");
  }

  /**
   * Returns the status: UP, DOWN, DRAIN, MAINT, NOLB, or a transition such as "UP 1/3" ({@code
   * status}).
   *
   * @return the status text
   */
  public String status() {
    return text("status");
  }

  /**
   * Says whether the balancer may send new requests to this server now.
   *
   * @return true for UP, including "UP 1/3" while a falling server has not reached its threshold
   */
  public boolean isUp() {
    return status().startsWith("UP");
  }

  /**
   * Says whether the server is up and passing its checks, with no failed check counted against it.
   *
   * @return true for exactly UP; false for "UP 1/3" and "UP 2/3", which are falling
   */
  public boolean isFullyUp() {
    return status().equals("UP");
  }

  /**
   * Returns the current sessions, the requests the server is working on now ({@code scur}).
   *
   * @return the count
   */
  public long currentSessions() {
    return number("scur");
  }

  /**
   * Returns how many times a connection to this server or backend was retried ({@code wretr}).
   *
   * @return the count since start
   */
  public long retries() {
    return number("wretr");
  }

  /**
   * Returns how many requests were moved to another server after a failure ({@code wredis}).
   *
   * @return the count since start
   */
  public long redispatches() {
    return number("wredis");
  }

  /**
   * Returns the requests sent to this server or backend ({@code stot}, total sessions).
   *
   * @return the count since start
   */
  public long totalSessions() {
    return number("stot");
  }

  /**
   * Returns the last health check's result, for example L7OK or L4TOUT ({@code check_status}).
   *
   * @return the text, or an empty string for a row without checks
   */
  public String checkStatus() {
    return text("check_status");
  }

  /**
   * Returns the seconds since the server's status last changed ({@code lastchg}).
   *
   * @return the seconds, or zero for a row without a status
   */
  public long secondsSinceChange() {
    return number("lastchg");
  }

  /**
   * Returns the server's current weight ({@code weight}), which slow start raises step by step.
   *
   * @return the weight
   */
  public long weight() {
    return number("weight");
  }

  private String text(String column) {
    return values.getOrDefault(column, "");
  }

  private long number(String column) {
    String cell = text(column);
    if (cell.isEmpty()) {
      return 0;
    }
    try {
      return Long.parseLong(cell);
    } catch (NumberFormatException notANumber) {
      throw new IllegalStateException(
          "the show stat column " + column + " is not a number: " + cell, notANumber);
    }
  }
}
