# The tests of the tools, part 1: the runtime API client and the rolling deploy

This is part 1 of 2. Part 1 reads the tests of the `haproxy` package (`FakeBalancer`, `RuntimeApiTest`, `StatTableTest`) and of the `deploy` package (`RollingDeployTest`, `DeployCliTest`, `DeploySettingsTest`, `DockerComposeTest`). Part 2 (`tests-tools-walkthrough-2.md`) reads the tests of the `harness` package (`HarnessCliTest`, `RunReportTest`, `LoadRunTest`, `BalancerWatchTest`) and of the `json` package (`JsonCodecTest`).

## What it is

A **test** is a small program that checks that another program does what it should. It runs the code, compares the result with what you expect, and fails with a message if they differ. This repository runs its tests with **JUnit**, which finds methods marked `@Test` and runs them, and **AssertJ**, which gives readable checks such as `assertThat(x).isEqualTo(y)`. One command, `./gradlew check`, runs all tests of the project: 152 of them in version 1.1.0. This note covers 35: 7 in `RuntimeApiTest`, 6 in `StatTableTest`, 8 in `RollingDeployTest`, 7 in `DeployCliTest`, 4 in `DeploySettingsTest` and 3 in `DockerComposeTest`. Part 2 covers 34 more: 5 in `HarnessCliTest`, 14 in `RunReportTest`, 7 in `LoadRunTest`, 1 in `BalancerWatchTest` and 7 in `JsonCodecTest`.

The tools in this note control two things the project does not own: HAProxy and Docker. A test cannot start a real HAProxy or a real Docker container without a full stack. So the tests use **fakes**: small stand-ins that behave like the real thing in the one way the test needs. A fake that records what it was asked, and answers with prepared text, can check that the code asks the right questions in the right order.

## Why this repository uses it

The deploy stops production servers. Its logic has to be right before it touches anything. The order of steps, the refusal to start when an instance is down, the refusal to stop an instance that still has sessions, the dry run that changes nothing, the question before it acts: these are rules, and each one is a test. They run in about a second and need no Docker.

The tests have a second purpose. A test is only worth something if it fails when the code is wrong. **Mutation testing** checks that: break one line on purpose (a **mutation**) and see whether a test notices. The review of version 1.0.0 found nine such changes in the tools that no test caught, for example removing the deploy's wait for "seen down". Version 1.1.0 added or strengthened a test for each, and the module's mutation check now applies 45 one-line changes across the project, each of which makes a test fail. The walkthrough says, for each new or strengthened test, which mutation it catches.

## Where it appears

| File | What it tests or provides |
|---|---|
| `haproxy/FakeBalancer.java` | A stand-in for HAProxy's runtime API on a loopback port. Used by three test classes. |
| `haproxy/RuntimeApiTest.java` | `RuntimeApi`, `RuntimeCommand` and `NamedBalancer`. |
| `haproxy/StatTableTest.java` | `StatTable` and `StatRow`, with a real captured answer. |
| `deploy/RollingDeployTest.java` | The deploy algorithm, with a scripted balancer and scripted containers. |
| `deploy/DeployCliTest.java` | The command line: dry run, confirmation, `--yes`, audit, an audit file that cannot be written, bad usage. |
| `deploy/DeploySettingsTest.java` | `DeploySettings.from`: the shipped settings, the whole-seconds stop timeout, a long audit path. |
| `deploy/DockerComposeTest.java` | `DockerCompose`, with a small shell script standing in for `docker compose`. |
| `src/test/resources/show-stat-lb2.csv` | The real `show stat` answer the tests read. |

## How it works underneath

**A test is a method.** JUnit looks for methods with `@Test`, creates a fresh object of the test class for each one, runs the method, and reports `PASSED` or `FAILED`. An exception that escapes the method, including a failed AssertJ check, is a failure. Method names here are sentences, such as `changesNothingWithoutAYes`, so a failure line reads like a broken promise.

**Arrange, act, assert.** Each test has three parts. Arrange: build the fakes and the input. Act: call the code. Assert: check the result. In this repository the parts are separated by blank lines.

**Fakes at the edges.** The deploy has two edges. One is `BalancerControl`, an interface with three methods. The other is `Containers`, with two. Because the deploy depends on the interfaces and not on HAProxy or Docker, a test can hand it an object it controls. The runtime API client (`RuntimeApi`) has a different edge: a real network socket. For that, `FakeBalancer` opens a real socket on the loopback address, so the real client code runs against a fake server.

**Loopback.** The address `127.0.0.1` always means "this computer". A program that listens on it can be reached only from the same machine. The port number 0 means "any free port, you choose", so tests do not collide.

**What the tests do not do.** They never start Docker, never start HAProxy, and never call a real clock for the audit file (the audit test uses a fixed clock). The class `DockerCompose`, which runs `docker compose` as a separate program, is tested with a stand-in: a two-line shell script that `DockerComposeTest` writes into a temporary folder and that `DockerCompose` runs in place of `docker compose`. The script records its arguments, or fails, or prints a lot, as each test needs.

**Two kinds of check that matter here.** A check of *what* happened (the result is `Success(2)`) and a check of *the order* (`containsSubsequence`: these items occur in this order, with anything allowed between them).

## Walkthrough of this repository's use

### `haproxy/FakeBalancer.java`

```java
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
```

The imports. The fake uses plain sockets and threads. `CopyOnWriteArrayList` is a list that is safe to add to from one thread and read from another. `UnaryOperator<String>` is a function from a text to a text.

```java
/**
 * A stand-in for HAProxy's runtime API on a loopback port: one command per connection, answered by
 * a function the test sets, then the connection is closed, as HAProxy does. Thread-safe.
 */
public final class FakeBalancer implements AutoCloseable {

  private final ServerSocket socket;
  private final Thread acceptor;
  private final List<String> commands = new CopyOnWriteArrayList<>();
  private volatile UnaryOperator<String> answers;
```

The class is `public` (so tests in other packages can use it: `DeployCliTest` and `HarnessCliTest` do) and `AutoCloseable`, so a test can open it in a `try ( ... )` block and have it closed at the end. It has four fields:

- `socket`: the server socket that listens.
- `acceptor`: the thread that accepts connections.
- `commands`: every command line received, in order.
- `answers`: the function that turns a command into an answer. It is `volatile`, a keyword that makes a write by one thread visible to the others at once, so a test can change the answers while the server thread is running.

```java
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
```

The constructor takes the function. `new ServerSocket(0, 50, InetAddress.getLoopbackAddress())` listens on a free port (0) of the loopback address with a backlog of 50 waiting connections. Then it starts a **daemon** thread running `serve`. A daemon thread does not keep the program alive: when the test ends, the thread does not hold the JVM open.

```java
  /**
   * Changes the answers from now on.
   *
   * @param next the new answering function
   */
  public void answerWith(UnaryOperator<String> next) {
    this.answers = next;
  }
```

`answerWith` swaps the function. A test can use it to simulate a balancer that starts failing.

```java
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
```

`api()` returns a real `RuntimeApi` pointed at this fake, with a 2-second timeout. The port is `socket.getLocalPort()`, the port the system chose.

```java
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
```

`port()` gives the port, for a settings file that has to name the balancer. `commands()` gives a copy of the recorded commands.

```java
  @Override
  public void close() throws IOException {
    socket.close();
    try {
      acceptor.join(Duration.ofSeconds(2));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
```

`close()` closes the server socket and waits up to 2 seconds for the thread to end. The `serve` loop ends when `accept()` throws because the socket was closed. If the wait is interrupted, the code sets the interrupted flag again.

```java
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
```

`serve` is the server loop.

- `while (!socket.isClosed())` keeps accepting until `close()`.
- `try (Socket connection = socket.accept())` waits for a client and closes the connection at the end of the block, as HAProxy does after each command.
- It reads one line with `readLine()`. A client that connects and sends nothing gives `null`, and the loop continues.
- It records the line, calls the function, and writes the answer in ASCII. `flush()` sends it.
- `catch (IOException closed)` covers both the closing of the server and a client that went away. The comment says "either way, stop or go on": when the server socket is closed the `while` condition ends the loop; otherwise the loop goes on to the next client.

One weakness: if the answer function throws a runtime exception (for example, a test bug), the exception is not an `IOException`, so the thread ends silently and the client waits until its read timeout. A test bug shows up as a timeout, not as a clear message.

### `haproxy/RuntimeApiTest.java`

```java
package io.plainround.seatwave.balancer.haproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
```

`assertThat` and `assertThatThrownBy` are the two AssertJ entry points. `assertThat(x)` starts a check on a value; `assertThatThrownBy(() -> code)` runs the code and checks the exception it throws. `Test` marks a test method.

```java
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
```

`class RuntimeApiTest` has no `public`: JUnit does not require a test class to be public.

The first test sends `show stat` to a fake that always answers with the captured CSV. `new FakeBalancer(command -> captured)` is the fake with a **lambda** for the answer: whatever the command, answer with the captured text. The `try` block closes the fake at the end. The check has two parts: the parsed table has three servers in the `seatwave` backend, and the fake received exactly one command, `show stat`. `containsExactly` means this list, in this order, nothing more.

```java
  @Test
  void setsAServerStateAndAcceptsTheEmptyAnswer() throws Exception {
    try (FakeBalancer balancer = new FakeBalancer(command -> "\n")) {
      balancer.api().setState("seatwave", "seatwave-2", RuntimeCommand.AdminState.DRAIN);

      assertThat(balancer.commands()).containsExactly("set server seatwave/seatwave-2 state drain");
    }
  }
```

The second test answers every command with an empty line (`"\n"`), as HAProxy does for an accepted `set server`. It calls `setState(... DRAIN)` and checks the command that arrived on the wire: `set server seatwave/seatwave-2 state drain`. So the test proves the exact text that HAProxy would receive, including the lower-case state.

```java
  @Test
  void reportsHaproxysRefusal() throws Exception {
    try (FakeBalancer balancer = new FakeBalancer(command -> "No such server.\n")) {
      assertThatThrownBy(
              () -> balancer.api().setState("seatwave", "nope", RuntimeCommand.AdminState.READY))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("No such server.");
    }
  }
```

The third test makes the fake answer `No such server.` and checks that `setState` throws an `IOException` whose message contains those words.

```java
  @Test
  void refusesAnythingThatWouldAddASecondCommand() {
    assertThatThrownBy(() -> new RuntimeCommand("show stat; shutdown sessions"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> RuntimeCommand.setState("seatwave\nshow", "s1", RuntimeCommand.AdminState.MAINT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("backend must match [A-Za-z0-9_-]{1,64}, but was \"seatwave\\u000ashow\"");
  }
```

The fourth test is the security rule. A command with a semicolon is refused by the constructor: HAProxy's runtime API reads `;` as "a second command follows", so `show stat; shutdown sessions` would run two commands. A backend name with a newline in it is refused by `setState`. Both must throw `IllegalArgumentException`.

Since 1.1.0 the second check pins the whole message: `backend must match [A-Za-z0-9_-]{1,64}, but was "seatwave\u000ashow"`. The allowed shape of a name is 1 to 64 letters, digits, underscores or hyphens. The interesting part is `\u000a`: the newline inside the name is printed as its code (hexadecimal 0a is the newline character) instead of as a real line break. A real line break would split the error message into two lines, and the second line, `show"`, could be mistaken for a message of its own. In the Java text of the test, `\\u000a` is written with two backslashes so that the expected string holds a backslash and the letters, not a newline. In a scratch copy I removed the name check, and I removed the semicolon check; this test failed each time.

```java
  @Test
  void refusesAZeroTimeout() {
    assertThatThrownBy(() -> new RuntimeApi(new InetSocketAddress(1), Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

The fifth test checks that a zero timeout is refused. `new InetSocketAddress(1)` is an address for port 1 on the wildcard address; it is never connected to.

```java
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
```

The sixth test is about `NamedBalancer.parse`. It parses a good list, a port that is too large (99999), and an address that is not an address (999.210.10.21), and checks the result of each. `isInstanceOf(Result.Success.class)` checks the type of the good result. `isEqualTo(Result.error("..."))` compares with the exact error: a record has `equals`, so two errors with the same text are equal. The exact messages are part of the contract: the user reads them.

```java
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
```

The seventh test, new in 1.1.0, covers three entries that look almost right.

- `lb1=127.0.1:9999` has an address with three parts. Java's `InetAddress.ofLiteral` accepts it: an old convention reads `127.0.1` as `127.0.0.1`. So the address the program would use is not the one the reader of the settings file sees. `NamedBalancer.parse` now prints the parsed address back with `getHostAddress()` and refuses the entry when the text differs. The exact message is pinned: `'lb1=127.0.1:9999' does not hold a dotted IPv4 address with four parts and no padding`. The message is split over two Java strings joined with `+`, only to keep the source line short.
- `lb1=127.0.0.1:09999` has a **padded** port: a leading zero. The entry pattern now requires a port that starts with a digit from 1 to 9 (`[1-9][0-9]{0,4}`), so this is an error.
- `lb1=127.0.0.1:0` has port 0, which no server can listen on for a client to reach (0 only asks the system to pick a free port). The same pattern refuses it.

For the last two only the type of the result (`Result.Error`) is checked, not the message. The mutation check confirms both halves: accepting a short address (`if (false)` in place of the comparison) and accepting port 0 (the old pattern `[0-9]{1,5}`) each make this test fail.

What the file does not test: an answer larger than a megabyte, a read timeout, a refused connection, and the states `READY` and `MAINT`.

### `haproxy/StatTableTest.java`

```java
package io.plainround.seatwave.balancer.haproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class StatTableTest {
```

The imports. `getResourceAsStream` reads a file that was packaged with the test code.

```java
  /**
   * A real answer of lb2 in the lab stack, captured with show stat on 11 November 2026; a check
   * that was running at that moment shows as "* L7OK".
   */
  static String capturedAnswer() throws IOException {
    try (InputStream in = StatTableTest.class.getResourceAsStream("/show-stat-lb2.csv")) {
      return new String(in.readAllBytes(), StandardCharsets.US_ASCII);
    }
  }
```

`capturedAnswer` reads `/show-stat-lb2.csv` from the test resources. The Javadoc says it is a real answer of `lb2` in the lab stack, captured on 11 November 2026, and that a check that was running at that moment shows as `* L7OK`. That is the real thing, not a hand-written sample. The method is `static` and package-private so that `RuntimeApiTest` can use it too.

```java
  @Test
  void readsTheServersOfABackendByColumnName() throws IOException {
    StatTable table = StatTable.parse(capturedAnswer());

    assertThat(table.servers("seatwave"))
        .extracting(StatRow::server)
        .containsExactly("seatwave-1", "seatwave-2", "seatwave-3");
    assertThat(table.server("seatwave", "seatwave-2").orElseThrow().isUp()).isTrue();
    assertThat(table.server("seatwave", "seatwave-2").orElseThrow().checkStatus()).endsWith("L7OK");
  }
```

The first test parses the capture and checks the servers of `seatwave` by name and order (`extracting(StatRow::server)` takes the `server()` of each row), then that `seatwave-2` is up and that its `checkStatus()` ends with `L7OK`. The `endsWith` is there because that row's check was in progress.

```java
  @Test
  void findsTheBackendSummaryRow() throws IOException {
    StatTable table = StatTable.parse(capturedAnswer());

    assertThat(table.backendSummary("seatwave")).isPresent();
    assertThat(table.backendSummary("nothing")).isEmpty();
    assertThat(table.server("seatwave", "nothing")).isEmpty();
  }
```

The second test finds the `BACKEND` summary row of a backend that exists, and checks that a backend and a server that do not exist give empty `Optional`s.

```java
  @Test
  void readsAnEmptyNumberAsZero() throws IOException {
    StatTable table = StatTable.parse("# pxname,svname,scur,status,\nb,s1,,UP 1/3,\n");

    StatRow row = table.server("b", "s1").orElseThrow();
    assertThat(row.currentSessions()).isZero();
    assertThat(row.isUp()).isTrue();
  }
```

The third test parses a tiny hand-made table. `"# pxname,svname,scur,status,\nb,s1,,UP 1/3,\n"` is a header and one row with an empty `scur`. The test checks that an empty number reads as zero and that `UP 1/3` counts as up.

```java
  @Test
  void callsOnlyAPlainUpFullyUp() throws IOException {
    StatTable table =
        StatTable.parse("# pxname,svname,status,\nb,s1,UP,\nb,s2,UP 2/3,\nb,s3,DOWN 1/2,\n");

    assertThat(table.server("b", "s1").orElseThrow().isFullyUp()).isTrue();
    assertThat(table.server("b", "s2").orElseThrow().isFullyUp()).isFalse();
    assertThat(table.server("b", "s2").orElseThrow().isUp()).isTrue();
    assertThat(table.server("b", "s3").orElseThrow().isFullyUp()).isFalse();
  }
```

The fourth test, new in 1.1.0, is about the difference between "up" and "fully up". HAProxy writes a server's status with a counter while checks are changing it: `UP 2/3` means "up, but one check has just failed; two more failures and it goes down". `DOWN 1/2` means "down, one good check of the two needed to come back". The tiny table has three rows: `s1` plain `UP`, `s2` `UP 2/3`, `s3` `DOWN 1/2`.

- `isFullyUp()` is true only for the plain `UP` of `s1`.
- `s2` is not fully up, but `isUp()` is still true for it: `isUp` only looks at the first word.
- `s3` is not fully up.

The deploy uses `isFullyUp` before it changes anything (see `changesNothingWhenAnInstanceIsAlreadyFailingItsChecks` below), because an instance at `UP 1/3` is already failing.

```java
  @Test
  void refusesAnAnswerThatIsNotAStatTable() {
    assertThatThrownBy(() -> StatTable.parse("Unknown command: 'show stats'\n"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Unknown command");
    assertThatThrownBy(() -> StatTable.parse(""))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("an empty answer");
  }
```

The fifth test passes text that is not a table. `Unknown command: 'show stats'` gives an `IOException` mentioning the text; an empty string gives one mentioning `an empty answer`.

```java
  @Test
  void reportsAColumnThatIsNotANumber() throws IOException {
    StatRow row =
        StatTable.parse("# pxname,svname,scur,\nb,s1,many,\n").server("b", "s1").orElseThrow();

    assertThatThrownBy(row::currentSessions)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("scur");
  }
}
```

The sixth test builds a row whose `scur` is `many`. Reading `currentSessions` must throw `IllegalStateException` with the column name. `row::currentSessions` is a method reference passed to `assertThatThrownBy`, so the call happens inside the check.

### `deploy/RollingDeployTest.java`

```java
package io.plainround.seatwave.balancer.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.balancer.haproxy.NamedBalancer;
import io.plainround.seatwave.balancer.haproxy.RuntimeCommand.AdminState;
import io.plainround.seatwave.balancer.haproxy.StatRow;
import io.plainround.seatwave.balancer.haproxy.StatTable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class RollingDeployTest {
```

The imports. `Map`, `ConcurrentHashMap` and `CopyOnWriteArrayList` hold the state of the scripted world.

```java
  private static final List<String> INSTANCES = List.of("seatwave-1", "seatwave-2");
  private static final List<String> BACKENDS = List.of("seatwave", "seatwave_active_only");

  private final List<String> calls = new CopyOnWriteArrayList<>();
  private final World world = new World();
```

Two instances and two backends are used, not three, to keep the tests short. `calls` records, in order, everything the deploy asks of the balancers and the containers: one text per call. `world` holds the scripted state. Both are fields: JUnit makes a fresh test object for each test, so each test starts clean.

```java
  @Test
  void restartsEachInstanceInOrderOnEveryBalancer() throws InterruptedException {
    Result<Integer, String> result = deploy(List.of(world.balancer("lb1"), world.balancer("lb2")));

    assertThat(result).isEqualTo(Result.success(2));
    assertThat(calls)
        .containsSubsequence(
            "lb1 seatwave/seatwave-1 DRAIN",
            "lb2 seatwave_active_only/seatwave-1 DRAIN",
            "stop seatwave-1",
            "start seatwave-1",
            "lb1 seatwave/seatwave-1 READY",
            "lb2 seatwave_active_only/seatwave-1 READY",
            "lb1 seatwave/seatwave-2 DRAIN",
            "stop seatwave-2");
  }
```

**`restartsEachInstanceInOrderOnEveryBalancer`** restarts both instances with two balancers. It checks that the result is `Success(2)` and then that the recorded calls contain, **in this order**, these calls: drain `seatwave-1` on `lb1` in the first backend, drain it on `lb2` in the second, stop `seatwave-1`, start it, set it ready on both balancers, then drain `seatwave-2` and stop it. `containsSubsequence` allows other calls between the listed ones. The waits between the steps are checked by the next two tests.

```java
  @Test
  void startsAnInstanceOnlyAfterEveryBalancerHasSeenItDown() throws InterruptedException {
    // The checks take three readings to notice the stop, as real checks take seconds.
    world.readsUntilSeenDown = 3;

    Result<Integer, String> result = deploy(List.of(world.balancer("lb1"), world.balancer("lb2")));

    assertThat(result).isEqualTo(Result.success(2));
    assertThat(calls).contains("start seatwave-1 while DOWN", "start seatwave-2 while DOWN");
    assertThat(calls).noneMatch(call -> call.startsWith("start") && call.endsWith("while UP"));
  }
```

**`startsAnInstanceOnlyAfterEveryBalancerHasSeenItDown`.** New in 1.1.0. After `docker stop`, a real balancer needs a few seconds of failed checks before its table says `DOWN`. The deploy must wait for that before it starts the instance again. If it started too soon, the balancer might never notice the restart and would send full traffic at once to a cold instance, skipping slow start.

- `world.readsUntilSeenDown = 3` makes the scripted balancers slow: after a stop, the instance keeps its old status for two readings of the table and shows `DOWN` from the third on (the mechanism is in `World` below).
- The deploy with two balancers must still succeed (`Success(2)`).
- The scripted `start` records a second line naming the status at the moment of the start, such as `start seatwave-1 while DOWN`. Both instances must have been started while `DOWN`.
- `noneMatch(...)` checks that no recorded call both starts with `start` and ends with `while UP`: no start happened while the balancer still saw the old instance up.

In version 1.0.0 the scripted `stop` set `DOWN` at once, so removing the wait passed every test. The mutation check now replaces the wait's condition with `false`, and this test fails.

```java
  @Test
  void waitsTheSettleTimeBeforeTheNextInstance() throws InterruptedException {
    Duration settle = Duration.ofMillis(300);

    Result<Integer, String> result = deploy(List.of(world.balancer("lb1")), settle);

    assertThat(result).isEqualTo(Result.success(2));
    // A lower bound only: a sleep is never shorter than asked, however busy the machine is.
    assertThat(world.secondDrainNanos - world.firstReadyNanos)
        .isGreaterThanOrEqualTo(settle.toNanos());
  }
```

**`waitsTheSettleTimeBeforeTheNextInstance`.** New in 1.1.0. The **settle time** is a pause after one instance is fully up and before the next one is drained, so slow start can bring the first one to full weight. The test runs a deploy with a settle of 300 ms.

- The scripted balancer notes the moment `seatwave-1` is set `READY` (`firstReadyNanos`) and the moment `seatwave-2` is set `DRAIN` (`secondDrainNanos`), with `System.nanoTime()`, a clock that only counts forward and is meant for measuring time spans.
- The gap between the two must be at least 300 ms, in nanoseconds (`settle.toNanos()`).

The comment explains why only a lower bound is checked: `Thread.sleep` never wakes up early, but a busy machine can make it wake up late. An upper bound would make the test fail at random on a loaded machine. The mutation check replaces `Thread.sleep(settings.settle())` with `Thread.sleep(Duration.ZERO)`, and this test fails.

```java
  @Test
  void changesNothingWhenAnInstanceIsAlreadyFailingItsChecks() throws InterruptedException {
    world.status.put("seatwave/seatwave-2", "UP 1/3");

    Result<Integer, String> result = deploy(List.of(world.balancer("lb1")));

    assertThat(result)
        .isEqualTo(
            Result.error("check before: lb1 sees seatwave/seatwave-2 UP 1/3; nothing was changed"));
    assertThat(calls).isEmpty();
  }
```

**`changesNothingWhenAnInstanceIsAlreadyFailingItsChecks`.** New in 1.1.0. `seatwave-2` is reported as `UP 1/3`: still up, but one failed check away from trouble. Restarting the others while it is weak could leave too few instances. The deploy must refuse with the exact error `check before: lb1 sees seatwave/seatwave-2 UP 1/3; nothing was changed`, and `calls` must be empty. The deploy's check before now asks for `isFullyUp()` (only a plain `UP`), not `isUp()` (any status that starts with `UP`). The mutation check puts `isUp()` back, and this test fails.

```java
  @Test
  void changesNothingWhenAnInstanceIsNotUpBeforehand() throws InterruptedException {
    world.status.put("seatwave/seatwave-2", "DOWN");

    Result<Integer, String> result = deploy(List.of(world.balancer("lb1")));

    assertThat(result)
        .isEqualTo(
            Result.error("check before: lb1 sees seatwave/seatwave-2 DOWN; nothing was changed"));
    assertThat(calls).isEmpty();
  }
```

**`changesNothingWhenAnInstanceIsNotUpBeforehand`** sets `seatwave/seatwave-2` to `DOWN` before the deploy. The result must be exactly the error `check before: lb1 sees seatwave/seatwave-2 DOWN; nothing was changed`, and `calls` must be empty: no command was sent at all. This is the "refuse to start from a broken state" rule.

```java
  @Test
  void stopsWhenTheDrainedInstanceKeepsItsSessions() throws InterruptedException {
    world.sessionsStayOpen = true;

    Result<Integer, String> result = deploy(List.of(world.balancer("lb1")));

    assertThat(result).isInstanceOf(Result.Error.class);
    assertThat(calls).doesNotContain("stop seatwave-1");
  }
```

**`stopsWhenTheDrainedInstanceKeepsItsSessions`** makes the sessions stay open (`scur` is 3 forever). The deploy must give up with an error and must never call `stop seatwave-1`. This is the "never stop an instance with running requests" rule. It takes the drain timeout of 200 ms to fail.

```java
  @Test
  void stopsWhenTheBalancerDoesNotAnswer() throws InterruptedException {
    world.isAnswering = false;

    Result<Integer, String> result = deploy(List.of(world.balancer("lb1")));

    assertThat(result)
        .isEqualTo(
            Result.error(
                "check before: lb1 does not answer: the test balancer is off; nothing was changed"));
  }
```

**`stopsWhenTheBalancerDoesNotAnswer`** makes the balancer throw `IOException`. The error is exactly `check before: lb1 does not answer: the test balancer is off; nothing was changed`.

```java
  @Test
  void stopsWhenTheRestartedInstanceNeverComesUp() throws InterruptedException {
    world.staysDownAfterStart = true;

    Result<Integer, String> result = deploy(List.of(world.balancer("lb1")));

    assertThat(result).isEqualTo(Result.error("seatwave-1 did not pass its checks within PT0.2S"));
  }
```

**`stopsWhenTheRestartedInstanceNeverComesUp`** makes the restarted instance stay `DOWN` after `start`. The deploy must fail with `seatwave-1 did not pass its checks within PT0.2S`. `PT0.2S` is how Java prints a `Duration` of 200 ms (ISO 8601: P for period, T for time, 0.2 seconds).

```java
  private Result<Integer, String> deploy(List<BalancerControl> balancers)
      throws InterruptedException {
    return deploy(balancers, Duration.ZERO);
  }

  private Result<Integer, String> deploy(List<BalancerControl> balancers, Duration settle)
      throws InterruptedException {
    return new RollingDeploy(
            settings(settle),
            balancers,
            world.containers(),
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
        .run();
  }
```

`deploy` is a helper, in two forms. The first, used by most tests, calls the second with a settle of `Duration.ZERO`. Two methods with the same name and different parameters are called **overloads**; Java picks the one whose parameters fit the call. The second builds a `RollingDeploy` with the settings, the given balancers, the world's containers, and a `PrintStream` that writes into memory and is thrown away. Then it calls `run()`.

```java
  private static DeploySettings settings(Duration settle) {
    return new DeploySettings(
        "seatwave-balancer",
        INSTANCES,
        BACKENDS,
        List.<NamedBalancer>of(),
        Duration.ofMillis(200),
        Duration.ofSeconds(1),
        Duration.ofMillis(200),
        settle,
        Duration.ofMillis(10),
        Path.of("build/unused-audit.log"));
  }
```

`settings(settle)` builds a `DeploySettings` by hand. `List.<NamedBalancer>of()` is an empty list of balancers (the scripted balancers are passed separately). The numbers: drain wait 200 ms, stop timeout 1 s, up wait 200 ms, the settle the caller gave, poll every 10 ms. Short timeouts make the failing tests quick. The audit path is not used by `RollingDeploy`.

```java
  /** The servers' status as both balancers see it, and the containers that change it. */
  private final class World {
    private final Map<String, String> status = new ConcurrentHashMap<>();
    private volatile boolean sessionsStayOpen;
    private volatile boolean isAnswering = true;
    private volatile boolean staysDownAfterStart;
    // Readings of the balancers after a stop before they show the instance down; 0 is at once.
    private volatile int readsUntilSeenDown;
    private final Map<String, Integer> readsLeft = new ConcurrentHashMap<>();
    private volatile long firstReadyNanos;
    private volatile long secondDrainNanos;

    World() {
      for (String backend : BACKENDS) {
        for (String instance : INSTANCES) {
          status.put(backend + "/" + instance, "UP");
        }
      }
    }
```

`World` is an inner class that holds the whole scripted state. `status` maps `backend/instance` to a status text. Three `volatile` flags switch behaviors: sessions stay open, the balancer does not answer, the instance stays down after start. The fields added in 1.1.0:

- `readsUntilSeenDown`: how many readings of the table a stop takes to show. The comment says it: 0 means at once, as in version 1.0.0.
- `readsLeft`: for each stopped instance, how many readings are still to go before it shows `DOWN`. A `ConcurrentHashMap` is a map that several threads may change at the same time.
- `firstReadyNanos` and `secondDrainNanos`: the two moments the settle test compares. They start at 0, which means "not yet seen".

The constructor puts every server of every backend in the state `UP`.

```java
    BalancerControl balancer(String name) {
      return new BalancerControl() {
        @Override
        public String name() {
          return name;
        }

        @Override
        public StatTable stat() throws IOException {
          if (!isAnswering) {
            throw new IOException("the test balancer is off");
          }
          readsLeft.replaceAll((service, left) -> {
            if (left == 1) {
              BACKENDS.forEach(backend -> status.put(backend + "/" + service, "DOWN"));
            }
            return Math.max(0, left - 1);
          });
          List<StatRow> rows = new ArrayList<>();
          status.forEach((key, value) -> {
            String[] parts = key.split("/");
            rows.add(
                new StatRow(
                    Map.of(
                        "pxname",
                        parts[0],
                        "svname",
                        parts[1],
                        "status",
                        value,
                        "scur",
                        sessionsStayOpen ? "3" : "0")));
          });
          return new StatTable(rows);
        }

        @Override
        public void setState(String backend, String server, AdminState state) {
          calls.add(name + " " + backend + "/" + server + " " + state);
          if (server.equals("seatwave-1") && state == AdminState.READY && firstReadyNanos == 0) {
            firstReadyNanos = System.nanoTime();
          }
          if (server.equals("seatwave-2") && state == AdminState.DRAIN && secondDrainNanos == 0) {
            secondDrainNanos = System.nanoTime();
          }
        }
      };
    }
```

`balancer(name)` returns an anonymous `BalancerControl`.

- `stat()` throws `IOException` if the flag says the balancer is off.
- Then it counts one reading for every stopped instance. `readsLeft.replaceAll(...)` replaces each value of the map with what the function returns. For an instance with one reading left, this reading is the one that notices the stop: it sets the status to `DOWN` in both backends. The new value is one less, but never below zero (`Math.max(0, left - 1)`), so a finished count stays at 0 and does nothing more. With `readsUntilSeenDown = 3`, the first two readings after a stop still show the old status and the third shows `DOWN`. The count is shared by both balancers, so a reading by either one counts.
- It then builds a `StatTable` from the map: for each entry it splits the key at the slash and makes a `StatRow` with `pxname`, `svname`, `status` and `scur` (`"3"` if sessions stay open, else `"0"`).
- `setState` records a call such as `lb1 seatwave/seatwave-1 DRAIN`. It does not change the status. It also notes the time of the first `READY` of `seatwave-1` and of the first `DRAIN` of `seatwave-2`; the `== 0` checks keep the first moment if the same call comes twice (it comes once per balancer and backend).

The fake `DRAIN` does not turn the status into `DRAIN`, so the status in the table stays `UP`, `DOWN` or whatever the containers set.

```java
    Containers containers() {
      return new Containers() {
        @Override
        public void stop(String service) {
          calls.add("stop " + service);
          if (readsUntilSeenDown == 0) {
            BACKENDS.forEach(backend -> status.put(backend + "/" + service, "DOWN"));
          } else {
            readsLeft.put(service, readsUntilSeenDown);
          }
        }

        @Override
        public void start(String service) {
          calls.add("start " + service);
          calls.add("start " + service + " while " + status.get("seatwave/" + service));
          if (!staysDownAfterStart) {
            BACKENDS.forEach(backend -> status.put(backend + "/" + service, "UP"));
          }
        }
      };
    }
  }
}
```

`containers()` returns an anonymous `Containers`.

- `stop` records the call. With `readsUntilSeenDown` at 0 it sets every backend's status for that service to `DOWN` at once. Otherwise it only starts the countdown in `readsLeft`, and `stat()` above flips the status later.
- `start` records the call twice: once as `start seatwave-1`, which the order test looks for, and once with the status the balancer showed at that moment, such as `start seatwave-1 while DOWN`, which the seen-down test looks for. `status.get("seatwave/" + service)` reads the status in the first backend. Then it sets `UP`, unless the flag says the instance stays down.

### `deploy/DeployCliTest.java`

```java
package io.plainround.seatwave.balancer.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.balancer.haproxy.FakeBalancer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
```

The imports. `Clock`, `Instant` and `ZoneOffset` make a clock that always shows the same moment. `@TempDir` is from JUnit. `OutputStream` and `Properties` are new in 1.1.0: the settings file is now written by `Properties.store` (the helper `settings` below).

```java
class DeployCliTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-11T09:00:00Z"), ZoneOffset.UTC);
  private static final String HEADER = "# pxname,svname,scur,status,\n";

  @TempDir Path folder;

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();
  private final List<String> containerCalls = new CopyOnWriteArrayList<>();
  // What the fake balancer reports for seatwave-1; the fake containers change it.
  private String[] containerStatus = {"UP"};
  private Runnable onStop = () -> {};
```

`CLOCK` is fixed at `2026-11-11T09:00:00Z`, so the audit line has a known time. `HEADER` is a one-line `show stat` header with four columns. `@TempDir Path folder` makes JUnit create a fresh temporary folder for each test and delete it afterwards: the settings file and the audit file go there, not into the repository. `out` and `err` collect what the program prints. `containerCalls` records the container calls. `containerStatus` is a one-item array shared with the fake balancer in some tests; an array is used because a lambda may share the array but cannot change a plain variable.

`onStop` is new in 1.1.0. A `Runnable` is a piece of code with no input and no result. `() -> {}` is one that does nothing. The fake containers run it inside `stop`, so a test can make something happen at that exact moment of the deploy (one test makes the audit file read-only there).

```java
  @Test
  void printsThePlanAndChangesNothingByDefault() throws IOException {
    try (FakeBalancer balancer = new FakeBalancer(command -> HEADER)) {
      int status = run(settings(balancer), "");

      assertThat(status).isZero();
      assertThat(text(out)).contains("plan: restart [seatwave-1]", "dry run: nothing changed");
      assertThat(balancer.commands()).isEmpty();
      assertThat(containerCalls).isEmpty();
    }
  }
```

The first test is the dry run. A fake balancer that answers only the header. `run(settings(balancer), "")` with no flags must return 0, print `plan: restart [seatwave-1]` and `dry run: nothing changed`, and neither the balancer nor the containers may have been touched (`isEmpty()` on both lists). The `try` block closes the fake.

```java
  @Test
  void changesNothingWithoutAYes() throws IOException {
    try (FakeBalancer balancer = new FakeBalancer(command -> HEADER)) {
      int status = run(settings(balancer), "n\n", "--run");

      assertThat(status).isEqualTo(DeployCli.EXIT_FAILED);
      assertThat(text(err)).contains("not confirmed; nothing changed");
      assertThat(balancer.commands()).isEmpty();
    }
  }
```

The second test gives `--run` and the input `n\n`. The exit status must be `EXIT_FAILED` (1), the error text must say `not confirmed; nothing changed`, and the balancer must have received no command.

```java
  @Test
  void deploysAfterConfirmationAndWritesTheAudit() throws IOException {
    String[] status = {"UP"};
    try (FakeBalancer balancer =
        new FakeBalancer(command -> {
          if (command.startsWith("set server")) {
            return "\n";
          }
          return HEADER + "seatwave,seatwave-1,0," + status[0] + ",\n";
        })) {
      containerStatus = status;

      int code = run(settings(balancer), "y\n", "--run");

      assertThat(code).isZero();
      assertThat(containerCalls).containsExactly("stop seatwave-1", "start seatwave-1");
      assertThat(Files.readString(folder.resolve("audit.log")))
          .contains("2026-11-11T09:00:00Z deploy: started on [seatwave-1]")
          .contains("deploy: finished, 1 instances restarted");
    }
  }
```

The third test runs a whole deploy of one instance through the real settings reader, the real `RuntimeApi` over a socket, the real `RollingDeploy`, and fake containers.

- The fake balancer answers `set server` commands with an empty line and anything else with the header plus one row whose status is `status[0]`.
- The fake containers flip `status[0]` to `DOWN` on stop and `UP` on start.
- The input is `y\n` with `--run`: confirmed.
- The checks: exit status 0, the container calls are exactly `stop seatwave-1` then `start seatwave-1`, and the audit file contains the two lines `2026-11-11T09:00:00Z deploy: started on [seatwave-1]` and `deploy: finished, 1 instances restarted`.

This is the most complete test of the package: socket, parsing, algorithm and audit work together.

```java
  @Test
  void deploysWithoutAQuestionWhenGivenYes() throws IOException {
    String[] status = {"UP"};
    try (FakeBalancer balancer = deployingBalancer(status)) {
      containerStatus = status;

      int code = run(settings(balancer, folder.resolve("audit.log")), "", "--run", "--yes");

      assertThat(code).isZero();
      assertThat(text(out)).doesNotContain("Go ahead?").contains("deploy finished: 1 instances");
      assertThat(containerCalls).containsExactly("stop seatwave-1", "start seatwave-1");
    }
  }
```

**`deploysWithoutAQuestionWhenGivenYes`.** New in 1.1.0. `--yes` answers the question in advance, for a script that runs the deploy without a person at the keyboard. The input is empty (`""`): if the program asked anyway, it would read nothing and refuse. The checks: exit status 0, the printed text never contains `Go ahead?` and does contain `deploy finished: 1 instances`, and the containers were stopped and started. `deployingBalancer(status)` is the helper below: the same fake balancer as in the third test.

```java
  @Test
  void saysTheDeployFinishedWhenOnlyTheLastAuditLineFails() throws IOException {
    String[] status = {"UP"};
    Path audit = folder.resolve("audit.log");
    try (FakeBalancer balancer = deployingBalancer(status)) {
      containerStatus = status;
      // The audit file turns read-only after the first line, while the instance restarts.
      onStop = () -> audit.toFile().setReadOnly();

      int code = run(settings(balancer, audit), "", "--run", "--yes");

      assertThat(code).isEqualTo(DeployCli.EXIT_FAILED);
      assertThat(text(out)).contains("deploy finished: 1 instances restarted, all up");
      assertThat(text(err))
          .contains("the audit line of this deploy cannot be written")
          .contains("the deploy itself finished as printed above")
          .doesNotContain("deploy stopped");
    }
  }
```

**`saysTheDeployFinishedWhenOnlyTheLastAuditLineFails`.** New in 1.1.0. The audit file gets two lines: one before the deploy starts and one after it ends. This test makes the second one fail and checks what the program says.

- `onStop = () -> audit.toFile().setReadOnly()` runs while the instance is being stopped, after the first audit line was written. `toFile()` turns the `Path` into the older `File` type, which has `setReadOnly()`: from then on the file can be read but not written.
- The deploy itself succeeds, so the printed text says `deploy finished: 1 instances restarted, all up`.
- Writing the last line fails, and a guardrail that failed must not end with status 0, so the exit status is `EXIT_FAILED`.
- The error text must say `the audit line of this deploy cannot be written` and `the deploy itself finished as printed above`, and must not contain `deploy stopped`.

The defect it guards against: in version 1.0.0 a failure of the last audit line printed "deploy stopped" after a deploy that had finished, which tells the operator the opposite of the truth. The mutation check makes the message always say `stopped`, and this test fails. One limit: `setReadOnly` does not stop the superuser (`root`), who may write any file. Run as `root`, for example inside some containers, the write would succeed and this test would fail for that reason.

```java
  @Test
  void startsNothingWhenTheFirstAuditLineCannotBeWritten() throws IOException {
    String[] status = {"UP"};
    // A folder where the audit file should be: no line can be appended to it.
    Path audit = Files.createDirectories(folder.resolve("audit-folder.log"));
    try (FakeBalancer balancer = deployingBalancer(status)) {
      containerStatus = status;

      int code = run(settings(balancer, audit), "", "--run", "--yes");

      assertThat(code).isEqualTo(DeployCli.EXIT_FAILED);
      assertThat(text(err)).contains("deploy not started", "nothing changed");
      assertThat(containerCalls).isEmpty();
      assertThat(balancer.commands()).isEmpty();
    }
  }
```

**`startsNothingWhenTheFirstAuditLineCannotBeWritten`.** New in 1.1.0. Here the very first audit line fails. `Files.createDirectories(folder.resolve("audit-folder.log"))` makes a **folder** with the name the audit file should have. A program cannot append a line to a folder, so the write fails, whoever runs the test. The deploy must not start: exit status `EXIT_FAILED`, the error text contains `deploy not started` and `nothing changed`, no container call was made, and the fake balancer received no command at all (not even `show stat`). The rule: a deploy that cannot leave a record does not run.

```java
  @Test
  void refusesBadUsage() {
    assertThat(run(Path.of("x"), "", "--now")).isEqualTo(DeployCli.EXIT_BAD_USAGE);
    assertThat(text(err)).contains("usage: deploy");
  }
```

`refusesBadUsage` gives an unknown flag, `--now`, and expects exit status 2 and a `usage: deploy` message. The settings path `x` does not exist and is never read, because the arguments are checked first.

```java
  private int run(Path settings, String input, String... flags) {
    String[] arguments = new String[flags.length + 1];
    arguments[0] = settings.toString();
    System.arraycopy(flags, 0, arguments, 1, flags.length);
    return DeployCli.run(
        arguments,
        new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
        new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8),
        CLOCK,
        (project, stopTimeout) ->
            new Containers() {
              @Override
              public void stop(String service) {
                containerCalls.add("stop " + service);
                onStop.run();
                containerStatus[0] = "DOWN";
              }

              @Override
              public void start(String service) {
                containerCalls.add("start " + service);
                containerStatus[0] = "UP";
              }
            });
  }
```

`run` is the helper. It builds the argument array from the settings path and the flags with `System.arraycopy`, feeds the text as standard input, collects the two output streams as UTF-8 text, gives the fixed clock, and gives a container factory written as a lambda that returns an anonymous `Containers`. The anonymous class records `stop` and `start` and flips `containerStatus[0]`. Inside `stop` it runs `onStop` after recording the call and before the status turns `DOWN`.

```java
  private Path settings(FakeBalancer balancer) throws IOException {
    return settings(balancer, folder.resolve("audit.log"));
  }

  private Path settings(FakeBalancer balancer, Path audit) throws IOException {
    Properties properties = new Properties();
    properties.setProperty("compose.project", "seatwave-balancer");
    properties.setProperty("deploy.instances", "seatwave-1");
    properties.setProperty("deploy.backends", "seatwave");
    properties.setProperty("deploy.balancers", "lb1=127.0.0.1:" + balancer.port());
    properties.setProperty("deploy.drainTimeoutMillis", "1000");
    properties.setProperty("deploy.stopTimeoutMillis", "1000");
    properties.setProperty("deploy.upTimeoutMillis", "1000");
    properties.setProperty("deploy.settleMillis", "0");
    properties.setProperty("deploy.pollMillis", "10");
    // Written by Properties.store, which escapes a backslash, so a Windows path reads back whole.
    properties.setProperty("deploy.auditFile", audit.toString());
    Path file = folder.resolve("deploy.properties");
    try (OutputStream stream = Files.newOutputStream(file)) {
      properties.store(stream, "test");
    }
    return file;
  }
```

`settings` writes a `deploy.properties` file into the temporary folder, in two overloads: one with the audit file `audit.log` in the same folder, and one that takes the audit path. The values: the project name, one instance, one backend, one balancer on the fake's port, 1-second timeouts, no settle, a 10-ms poll, and the audit path.

In version 1.0.0 the file was built by joining text lines, so the audit path was written as plain text. On Windows a path such as `C:\Users\...` holds backslashes, and a properties file reads a backslash as the start of an escape, so the path would come back broken. Now the values go into a `Properties` object and `properties.store(stream, "test")` writes the file. `store` escapes every backslash (it writes `\\`), and it writes `test` as a comment line at the top, with the date. The comment in the code says why. `Files.newOutputStream(file)` opens the file for writing; the `try ( ... )` block closes it.

```java
  private static FakeBalancer deployingBalancer(String[] status) throws IOException {
    return new FakeBalancer(command ->
        command.startsWith("set server")
            ? "\n"
            : HEADER + "seatwave,seatwave-1,0," + status[0] + ",\n");
  }
```

`deployingBalancer(status)` builds the fake balancer of the deploying tests in one expression. `condition ? a : b` is Java's **conditional operator**: `a` if the condition holds, otherwise `b`. A `set server` command gets an empty line; anything else gets the header and one row for `seatwave-1` with the status in `status[0]`.

```java
  private static String text(ByteArrayOutputStream stream) {
    return stream.toString(StandardCharsets.UTF_8);
  }
}
```

`text` turns the collected bytes into a string.

What `DeployCliTest` still does not cover: a deploy that stops with an error (exit status 1 and the audit line `deploy: stopped`), invalid settings (exit status 2), an interrupted deploy, and `main` itself.

### `deploy/DeploySettingsTest.java`

New in 1.1.0. `DeploySettings.from(reader)` turns a settings file into a checked `DeploySettings`, or into a list of problem lines. These four tests read it directly.

```java
package io.plainround.seatwave.balancer.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.balancer.config.SettingsReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class DeploySettingsTest {
```

The imports. `SettingsReader` is the project's reader of settings files (`tests-config-walkthrough.md` reads its tests). `Properties` is Java's in-memory form of a settings file.

```java
  @Test
  void readsTheDeploySettingsShippedWithTheProject() throws IOException {
    Result<DeploySettings, List<String>> read =
        DeploySettings.from(
            new SettingsReader(SettingsReader.load(Path.of("config/deploy.properties"))));

    assertThat(read).isInstanceOf(Result.Success.class);
  }
```

**`readsTheDeploySettingsShippedWithTheProject`.** Loads the real `config/deploy.properties` of the repository and checks that it is valid (`Result.Success`). A shipped file that the program refuses would stop every deploy; this test catches that in the build.

```java
  @Test
  void refusesAStopTimeoutDockerWouldRoundToZeroSeconds() {
    Properties properties = valid();
    properties.setProperty("deploy.stopTimeoutMillis", "999");

    Result<DeploySettings, List<String>> read = DeploySettings.from(new SettingsReader(properties));

    assertThat(read)
        .isEqualTo(
            Result.error(
                List.of(
                    "deploy.stopTimeoutMillis must be at least 1000: docker counts whole seconds")));
  }
```

**`refusesAStopTimeoutDockerWouldRoundToZeroSeconds`.** `docker compose stop -t` takes whole seconds. `DockerCompose` passes `stopTimeout.toSeconds()`, which drops the fraction, so 999 ms would become `-t 0`: Docker would kill the instance at once instead of letting it drain. The test starts from valid settings, sets `deploy.stopTimeoutMillis` to 999, and requires exactly one problem: `deploy.stopTimeoutMillis must be at least 1000: docker counts whole seconds`. The mutation check removes the rule (`if (false)`), and this test fails.

```java
  @Test
  void reportsAMissingStopTimeoutOnceWithoutTheRuleThatNeedsIt() {
    Properties properties = valid();
    properties.remove("deploy.stopTimeoutMillis");

    Result<DeploySettings, List<String>> read = DeploySettings.from(new SettingsReader(properties));

    assertThat(read).isEqualTo(Result.error(List.of("deploy.stopTimeoutMillis is missing")));
  }
```

**`reportsAMissingStopTimeoutOnceWithoutTheRuleThatNeedsIt`.** The other side of the same rule. With the key removed, the reader already reports `deploy.stopTimeoutMillis is missing`. The whole-seconds rule must then not run: the missing value reads as zero, and the rule would add a second, misleading line ("must be at least 1000") about a value nobody wrote. `isEqualTo(Result.error(List.of(...)))` requires exactly the one line. The code counts the problems before and after reading the key and checks the rule only when no problem was added (`stopRead`). The mutation check drops that condition, and this test fails.

```java
  @Test
  void acceptsAnAuditFileInADeepFolderWithSpaces() {
    Properties properties = valid();
    properties.setProperty(
        "deploy.auditFile", "/checkouts/My Labs/" + "nested-folder/".repeat(20) + "audit.log");

    Result<DeploySettings, List<String>> read = DeploySettings.from(new SettingsReader(properties));

    assertThat(read).isInstanceOf(Result.Success.class);
  }
```

**`acceptsAnAuditFileInADeepFolderWithSpaces`.** The audit path is read with `filePath`, which allows any characters except control characters, up to 4,096 of them. A path with a space and twenty nested folders must be accepted.

```java
  private static Properties valid() {
    Properties properties = new Properties();
    properties.setProperty("compose.project", "seatwave-balancer");
    properties.setProperty("deploy.instances", "seatwave-1,seatwave-2");
    properties.setProperty("deploy.backends", "seatwave");
    properties.setProperty("deploy.balancers", "lb1=127.0.0.1:21005");
    properties.setProperty("deploy.drainTimeoutMillis", "1000");
    properties.setProperty("deploy.stopTimeoutMillis", "30000");
    properties.setProperty("deploy.upTimeoutMillis", "1000");
    properties.setProperty("deploy.settleMillis", "0");
    properties.setProperty("deploy.pollMillis", "10");
    properties.setProperty("deploy.auditFile", "build/stack/audit.log");
    return properties;
  }
}
```

`valid()` builds a complete, valid set of deploy settings in memory: two instances, one backend, one balancer, timeouts, and an audit file. Each test changes one key of it, so a failure points at that key. The stop timeout is 30,000 ms, the shipped file's value, which its comment explains: longer than the instance's 20 s drain deadline, so Docker never kills a draining instance.

### `deploy/DockerComposeTest.java`

New in 1.1.0. `DockerCompose` starts `docker compose` as a separate **process** (a running program of its own, with its own memory) and waits for it. To test it without Docker, the class takes the program to run as a list; the real code passes `docker compose`, and the tests pass `sh` and a script.

```java
package io.plainround.seatwave.balancer.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DockerCompose with a stand-in program in place of {@code docker compose}: a small shell script
 * that writes its arguments to a file, so the tests see the exact command line, and can fail or
 * print a lot on purpose. Needs a POSIX shell, as the project's scripts do.
 */
class DockerComposeTest {

  @TempDir Path folder;
```

The class comment says what the stand-in is: "a small shell script that writes its arguments to a file, so the tests see the exact command line". A **shell script** is a text file of commands for `sh`, the standard command interpreter of Unix-like systems. "Needs a POSIX shell, as the project's scripts do": the tests run on macOS and Linux, not on plain Windows. The comment names what the tests make the script do: write its arguments, fail, or print a lot. None of the three tests makes it hang, so the time-limit branches of `DockerCompose` (`did not finish within`, `did not end within`) are not tested.

```java
  @Test
  void stopsWithDockersWholeSecondTimeoutAndStartsByServiceName() throws Exception {
    Path calls = folder.resolve("calls.txt");
    DockerCompose compose =
        compose("printf '%s\\n' \"$*\" >> \"" + calls + "\"", Duration.ofMillis(30_500));

    compose.stop("seatwave-2");
    compose.start("seatwave-2");

    assertThat(Files.readAllLines(calls, StandardCharsets.UTF_8))
        .containsExactly(
            "-p seatwave-balancer stop -t 30 seatwave-2", "-p seatwave-balancer start seatwave-2");
  }
```

**`stopsWithDockersWholeSecondTimeoutAndStartsByServiceName`.** The script body is `printf '%s\n' "$*" >> "<calls.txt>"`. In the shell, `$*` is all the script's arguments joined by spaces, `printf '%s\n'` prints them followed by a line break, and `>>` appends that line to the file. (In the Java text, `\\n` and `\"` are a backslash-n and a double quote.) The stop timeout is 30,500 ms.

After `stop("seatwave-2")` and `start("seatwave-2")`, the file must hold exactly two lines:

- `-p seatwave-balancer stop -t 30 seatwave-2`: the project, the action, the timeout in whole seconds (30.5 s rounds down to 30), and the service;
- `-p seatwave-balancer start seatwave-2`.

This pins the exact command line Docker would get. Each argument is passed separately, never as one shell string, so a service name cannot smuggle in a second command.

```java
  @Test
  void reportsAFailedCommandWithItsExitStatusAndOutput() throws Exception {
    DockerCompose compose = compose("echo 'no such service' >&2; exit 3", Duration.ofSeconds(1));

    assertThatThrownBy(() -> compose.start("seatwave-9"))
        .isInstanceOf(IOException.class)
        .hasMessageEndingWith("start seatwave-9 exited 3: no such service");
  }
```

**`reportsAFailedCommandWithItsExitStatusAndOutput`.** The script prints `no such service` to the error stream (`>&2`) and exits with status 3. `start("seatwave-9")` must throw an `IOException` whose message ends with `start seatwave-9 exited 3: no such service`: the action, the exit status and what the program printed. `DockerCompose` merges the error stream into the normal output (`redirectErrorStream(true)`), so the message includes it.

```java
  @Test
  void readsALargeOutputWhileTheCommandRuns() throws Exception {
    // About 1 MB, far more than a pipe holds: a child that cannot write would never end.
    DockerCompose compose =
        compose(
            "i=0; while [ $i -lt 20000 ]; do echo " + "x".repeat(50) + "; i=$((i+1)); done; exit 1",
            Duration.ofSeconds(1));

    assertThatThrownBy(() -> compose.start("seatwave-1"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("exited 1");
  }
```

**`readsALargeOutputWhileTheCommandRuns`.** A **pipe** connects the child's output to the parent. It holds only a small amount of text (usually 64 KiB or less); when it is full, the child stops at its next write until the parent reads. A parent that waits for the child to end before it reads anything therefore waits forever for a child that prints a lot. The script here prints 20,000 lines of 50 `x` characters, about 1 MB, then exits 1. The loop is written in shell: `i=0`, then `while [ $i -lt 20000 ]` (while `i` is less than 20,000), `echo` the line, and `i=$((i+1))` adds one.

The test expects the `IOException` with `exited 1`. That only happens if `DockerCompose` reads the output while the child runs. In version 1.0.0 it read the output only after the wait, so this script would never end, and the wait would give up after the 30 s margin with `did not finish within`. Now a second thread (`FutureTask` on a virtual thread) reads as the child writes.

```java
  private DockerCompose compose(String body, Duration stopTimeout) throws IOException {
    Path program = folder.resolve("docker-stand-in.sh");
    Files.writeString(program, "#!/bin/sh\n" + body + "\n", StandardCharsets.UTF_8);
    return new DockerCompose(List.of("sh", program.toString()), "seatwave-balancer", stopTimeout);
  }
}
```

`compose(body, stopTimeout)` writes the stand-in script: `#!/bin/sh` (the **shebang** line, which names the interpreter), the body, and a line break, into `docker-stand-in.sh` in the temporary folder. It returns a `DockerCompose` that runs `sh <script>` in place of `docker compose`, for the project `seatwave-balancer`. The script is passed to `sh` as an argument, so it needs no execute permission.

## Try it yourself

Run these in the repository folder. Nothing needs Docker. Timings differ on every run.

**1. Run the tests of both packages.**

```bash
./gradlew test --tests 'io.plainround.seatwave.balancer.haproxy.*' --tests 'io.plainround.seatwave.balancer.deploy.*' --console=plain | grep -E 'PASSED|FAILED|BUILD'
```

```text
DeployCliTest > saysTheDeployFinishedWhenOnlyTheLastAuditLineFails() PASSED
DeployCliTest > printsThePlanAndChangesNothingByDefault() PASSED
DeployCliTest > changesNothingWithoutAYes() PASSED
DeployCliTest > deploysWithoutAQuestionWhenGivenYes() PASSED
DeployCliTest > refusesBadUsage() PASSED
DeployCliTest > deploysAfterConfirmationAndWritesTheAudit() PASSED
DeployCliTest > startsNothingWhenTheFirstAuditLineCannotBeWritten() PASSED
DeploySettingsTest > refusesAStopTimeoutDockerWouldRoundToZeroSeconds() PASSED
DeploySettingsTest > readsTheDeploySettingsShippedWithTheProject() PASSED
DeploySettingsTest > reportsAMissingStopTimeoutOnceWithoutTheRuleThatNeedsIt() PASSED
DeploySettingsTest > acceptsAnAuditFileInADeepFolderWithSpaces() PASSED
DockerComposeTest > readsALargeOutputWhileTheCommandRuns() PASSED
DockerComposeTest > stopsWithDockersWholeSecondTimeoutAndStartsByServiceName() PASSED
DockerComposeTest > reportsAFailedCommandWithItsExitStatusAndOutput() PASSED
RollingDeployTest > stopsWhenTheBalancerDoesNotAnswer() PASSED
RollingDeployTest > stopsWhenTheRestartedInstanceNeverComesUp() PASSED
RollingDeployTest > restartsEachInstanceInOrderOnEveryBalancer() PASSED
RollingDeployTest > changesNothingWhenAnInstanceIsAlreadyFailingItsChecks() PASSED
RollingDeployTest > changesNothingWhenAnInstanceIsNotUpBeforehand() PASSED
RollingDeployTest > waitsTheSettleTimeBeforeTheNextInstance() PASSED
RollingDeployTest > startsAnInstanceOnlyAfterEveryBalancerHasSeenItDown() PASSED
RollingDeployTest > stopsWhenTheDrainedInstanceKeepsItsSessions() PASSED
RuntimeApiTest > setsAServerStateAndAcceptsTheEmptyAnswer() PASSED
RuntimeApiTest > refusesTheShortAndPaddedFormsOfAnEntry() PASSED
RuntimeApiTest > refusesAZeroTimeout() PASSED
RuntimeApiTest > readsBalancersFromSettingsAndNamesABadEntry() PASSED
RuntimeApiTest > reportsHaproxysRefusal() PASSED
RuntimeApiTest > sendsOneCommandAndReadsTheWholeAnswer() PASSED
RuntimeApiTest > refusesAnythingThatWouldAddASecondCommand() PASSED
StatTableTest > findsTheBackendSummaryRow() PASSED
StatTableTest > callsOnlyAPlainUpFullyUp() PASSED
StatTableTest > readsTheServersOfABackendByColumnName() PASSED
StatTableTest > refusesAnAnswerThatIsNotAStatTable() PASSED
StatTableTest > reportsAColumnThatIsNotANumber() PASSED
StatTableTest > readsAnEmptyNumberAsZero() PASSED
BUILD SUCCESSFUL in 2s
```

That is 13 tests of the `haproxy` package and 22 of the `deploy` package. Add `cleanTest` before `test` if Gradle says the task is up to date. The order of the lines can differ.

**2. Break the order and watch a test fail.** In a scratch copy of the repository, open `RollingDeploy.java` and delete the line `setEverywhere(instance, AdminState.READY);`. Run command 1 again. `restartsEachInstanceInOrderOnEveryBalancer` fails, because the calls `READY` are missing from the recorded list. Undo it.

**3. Break a wait and watch the new tests notice.** In the same scratch copy, change `Thread.sleep(settings.settle());` to `Thread.sleep(0);` and run only `--tests '*RollingDeployTest'`. `waitsTheSettleTimeBeforeTheNextInstance() FAILED` appears. Undo it, then change the line `if (!waitFor(instance, row -> row.status().startsWith("DOWN"), settings.upTimeout())) {` to `if (false) {`. `startsAnInstanceOnlyAfterEveryBalancerHasSeenItDown() FAILED` appears. Undo it. (I ran both changes; in version 1.0.0 both passed every test.)

**4. Read the mutation list.** The module's mutation check changes one line at a time in a copy and runs the named tests. Among its 45 changes, these are caught by the tests of this note: the seen-down wait removed, the settle ignored, the stop-timeout rule removed, the stop-timeout rule run on a missing value, a short address accepted, port 0 accepted, the check before counting `UP 1/3` as up, and an audit failure that says "stopped" after a finished deploy. Each line of its output ends `a test FAILED (good)`.

## Mistakes people make

- **A fake that is too eager.** In version 1.0.0 the scripted `stop` set `DOWN` at once, so no test could tell whether the deploy waited. A fake should be as slow as the real thing in the way the test is about; `readsUntilSeenDown` is that slowness.
- **Checking only the result.** A deploy that restarts in the wrong order can still return `Success`. The first test checks the order of calls.
- **An upper bound on a sleep.** A sleep can last longer on a busy machine. The settle test checks only that it lasted at least the settle time.
- **Real clocks in tests.** The audit time is checked against a fixed clock, so the test does not depend on the date.
- **Tests that write into the repository.** `@TempDir` gives every test its own folder and removes it.
- **Writing a settings file by joining text.** A backslash in a value becomes an escape. `Properties.store` escapes it.
- **Reading a child's output only after it ends.** The pipe fills and both sides wait forever. `readsALargeOutputWhileTheCommandRuns` guards against it.
- **Waiting with `sleep` for something to happen.** These tests use short timeouts of the code under test (200 ms) and a polling loop of 10 ms, which are bounded. The module's test loop ran the whole test task 40 times beside 8 busy loops on the 12 cores with 0 failures.
- **Leaving a server running.** Every `FakeBalancer` is opened in a `try` block, so its socket is closed even when a check fails.

## Check yourself

1. What does `containsSubsequence` check that `contains` does not? *Answer: The items occur in the given order, with anything allowed between them.*
2. Why does `FakeBalancer` listen on port 0? *Answer: The system picks a free port, so tests running at the same time never collide.*
3. Which rule does `changesNothingWhenAnInstanceIsAlreadyFailingItsChecks` prove? *Answer: The deploy refuses to start when an instance is not fully up (`UP 1/3` counts as not fully up), and sends no command before it refuses.*
4. How does the scripted world let a test see whether the deploy waits for "seen down"? *Answer: With `readsUntilSeenDown = 3` a stop shows only on the third reading of the table, and `start` records the status it saw; a start `while UP` means the deploy did not wait.*
5. Why does `DockerComposeTest` print about 1 MB from its stand-in script? *Answer: Far more than a pipe holds, so the test ends only if `DockerCompose` reads the output while the child runs.*

## Further reading

- JUnit User Guide (this repository pins JUnit 6.1.3): https://docs.junit.org/current/user-guide/
- AssertJ Core documentation, 3.27: https://assertj.github.io/doc/
- Java SE 25 API: `java.net.ServerSocket`, `java.time.Clock`, `java.util.concurrent.CopyOnWriteArrayList`, `java.lang.ProcessBuilder`, `java.util.Properties.store`: https://docs.oracle.com/en/java/javase/25/docs/api/
- HAProxy 3.4 Management Guide (the answers the fake imitates): https://docs.haproxy.org/3.4/management.html
- Docker Compose CLI reference, `docker compose stop` (the `-t` timeout in seconds): https://docs.docker.com/reference/cli/docker/compose/stop/
- POSIX shell command language (`$*`, `printf`, `>>`): https://pubs.opengroup.org/onlinepubs/9799919799/utilities/V3_chap02.html
