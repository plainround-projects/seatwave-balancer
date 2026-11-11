package io.plainround.seatwave.balancer.haproxy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;

/**
 * A stand-in for HAProxy's runtime API on a loopback port: one command per connection, answered by
 * a function the test sets, then the connection is closed, as HAProxy does. Thread-safe.
 */
public final class FakeBalancer implements AutoCloseable {

  private final ServerSocket socket;
  private final Thread acceptor;
  private final List<String> commands = new CopyOnWriteArrayList<>();
  private volatile UnaryOperator<String> answers;

  /**
   * Starts listening.
   *
   * @param answers turns a command line into the answer text
   * @throws IOException if no port can be bound
   */
  public FakeBalancer(UnaryOperator<String> answers) throws IOException {
    this.answers = answers;
    this.socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    this.acceptor = Thread.ofPlatform().daemon().name("fake-balancer").start(this::serve);
  }

  /**
   * Changes the answers from now on.
   *
   * @param next the new answering function
   */
  public void answerWith(UnaryOperator<String> next) {
    this.answers = next;
  }

  /**
   * Returns a client for this fake.
   *
   * @return the runtime API client
   */
  public RuntimeApi api() {
    return new RuntimeApi(
        new InetSocketAddress(InetAddress.getLoopbackAddress(), socket.getLocalPort()),
        Duration.ofSeconds(2));
  }

  /**
   * Returns the port, for a settings file.
   *
   * @return the port
   */
  public int port() {
    return socket.getLocalPort();
  }

  /**
   * Returns every command received, in order.
   *
   * @return the command lines
   */
  public List<String> commands() {
    return List.copyOf(commands);
  }

  @Override
  public void close() throws IOException {
    socket.close();
    try {
      acceptor.join(Duration.ofSeconds(2));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private void serve() {
    while (!socket.isClosed()) {
      try (Socket connection = socket.accept()) {
        BufferedReader in =
            new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
        String line = in.readLine();
        if (line == null) {
          continue;
        }
        commands.add(line);
        OutputStream out = connection.getOutputStream();
        out.write(answers.apply(line).getBytes(StandardCharsets.US_ASCII));
        out.flush();
      } catch (IOException closed) {
        // The socket was closed by close(), or a client went away; either way, stop or go on.
      }
    }
  }
}
