package io.plainround.seatwave.balancer.haproxy;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One command for HAProxy's runtime API, built only from checked parts, so no input can add a
 * second command (a newline or a semicolon) to the line.
 *
 * @param line the command text, without the newline
 */
public record RuntimeCommand(String line) {

  // HAProxy names of backends and servers in this project: letters, digits, - and _.
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  /** Checks the line. */
  public RuntimeCommand {
    Objects.requireNonNull(line, "line");
    if (line.contains("\n") || line.contains(";")) {
      throw new IllegalArgumentException("a runtime command is one line with one command");
    }
  }

  /**
   * Returns {@code show stat}, the CSV of every frontend, backend and server.
   *
   * @return the command
   */
  public static RuntimeCommand showStat() {
    return new RuntimeCommand("show stat");
  }

  /**
   * Returns {@code set server <backend>/<server> state <state>}.
   *
   * @param backend the backend's name
   * @param server the server's name
   * @param state the administrative state to set
   * @return the command
   * @throws IllegalArgumentException if a name has a character HAProxy names never have here
   */
  public static RuntimeCommand setState(String backend, String server, AdminState state) {
    Objects.requireNonNull(state, "state");
    return new RuntimeCommand(
        "set server "
            + checked(backend, "backend")
            + "/"
            + checked(server, "server")
            + " state "
            + state.name().toLowerCase(Locale.ROOT));
  }

  private static String checked(String name, String what) {
    Objects.requireNonNull(name, what);
    if (!NAME.matcher(name).matches()) {
      throw new IllegalArgumentException(what + " must match " + NAME.pattern() + ": " + name);
    }
    return name;
  }

  /** The administrative states the runtime API can set on a server. */
  public enum AdminState {
    /** Normal: the balancer may send new requests, if the health checks pass. */
    READY,
    /** No new requests, but checks continue and running requests finish. */
    DRAIN,
    /** No traffic and no checks. */
    MAINT
  }
}
