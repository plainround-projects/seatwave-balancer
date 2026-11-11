package io.plainround.seatwave.balancer.haproxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * A client for HAProxy's runtime API on a TCP socket ({@code stats socket ipv4@...} in
 * haproxy.cfg): one connection per command, the way the API works when it is not in interactive
 * mode. HAProxy answers and closes the connection, so the answer is everything read until the end.
 * Thread-safe: it holds only immutable fields.
 *
 * @param address the balancer's runtime API address
 * @param timeout the connect and read timeout of one command
 */
public record RuntimeApi(InetSocketAddress address, Duration timeout) {

  // An answer larger than this is not a show stat of this lab, so reading stops there.
  private static final int MAX_ANSWER_BYTES = 1_048_576;

  /** Checks the fields. */
  public RuntimeApi {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("the timeout must be positive: " + timeout);
    }
  }

  /**
   * Sends one command and returns HAProxy's answer.
   *
   * @param command the command
   * @return the answer text; HAProxy answers an accepted {@code set} command with an empty line
   * @throws IOException if the balancer does not accept the connection, does not answer within the
   *     timeout, or answers with more than a megabyte
   */
  public String send(RuntimeCommand command) throws IOException {
    Objects.requireNonNull(command, "command");
    int timeoutMillis = Math.toIntExact(timeout.toMillis());
    try (Socket socket = new Socket()) {
      socket.connect(address, timeoutMillis);
      socket.setSoTimeout(timeoutMillis);
      OutputStream out = socket.getOutputStream();
      out.write((command.line() + "\n").getBytes(StandardCharsets.US_ASCII));
      out.flush();
      InputStream in = socket.getInputStream();
      byte[] answer = in.readNBytes(MAX_ANSWER_BYTES + 1);
      if (answer.length > MAX_ANSWER_BYTES) {
        throw new IOException("the runtime API at " + address + " answered more than 1 MB");
      }
      return new String(answer, StandardCharsets.US_ASCII);
    }
  }

  /**
   * Reads every row of {@code show stat}.
   *
   * @return the table
   * @throws IOException if the command fails or the answer is not a stat table
   */
  public StatTable showStat() throws IOException {
    return StatTable.parse(send(RuntimeCommand.showStat()));
  }

  /**
   * Sets a server's administrative state and checks that HAProxy accepted it.
   *
   * @param backend the backend
   * @param server the server
   * @param state the state
   * @throws IOException if the command fails or HAProxy answers with an error message
   */
  public void setState(String backend, String server, RuntimeCommand.AdminState state)
      throws IOException {
    String answer = send(RuntimeCommand.setState(backend, server, state)).strip();
    // An accepted "set server" answers nothing; anything else is HAProxy explaining a refusal.
    if (!answer.isEmpty()) {
      throw new IOException(
          "the runtime API at "
              + address
              + " refused set server "
              + backend
              + "/"
              + server
              + ": "
              + answer);
    }
  }
}
