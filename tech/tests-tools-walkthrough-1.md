# The tests of the tools, part 1: the runtime API client and the rolling deploy

This is part 1 of 2. Part 1 reads the tests of the `haproxy` package (`FakeBalancer`, `RuntimeApiTest`, `StatTableTest`) and of the `deploy` package (`RollingDeployTest`, `DeployCliTest`). Part 2 (`tests-tools-walkthrough-2.md`) reads the tests of the `harness` package (`HarnessCliTest`, `RunReportTest`).

## What it is

A **test** is a small program that checks that another program does what it should. It runs the code, compares the result with what you expect, and fails with a message if they differ. This repository runs its tests with **JUnit**, which finds methods marked `@Test` and runs them, and **AssertJ**, which gives readable checks such as `assertThat(x).isEqualTo(y)`. One command, `./gradlew check`, runs all tests of the project. The README says it ran 98 of them, of which the tests in this note and in `tests-tools-walkthrough-2.md` are 31: 6 in `RuntimeApiTest`, 5 in `StatTableTest`, 5 in `RollingDeployTest`, 4 in `DeployCliTest`, 3 in `HarnessCliTest` and 8 in `RunReportTest`.

The tools in this note control two things the project does not own: HAProxy and Docker. A test cannot start a real HAProxy or a real Docker container without a full stack. So the tests use **fakes**: small stand-ins that behave like the real thing in the one way the test needs. A fake that records what it was asked, and answers with prepared text, can check that the code asks the right questions in the right order.

## Why this repository uses it

The deploy stops production servers. Its logic has to be right before it touches anything. The order of steps, the refusal to start when an instance is down, the refusal to stop an instance that still has sessions, the dry run that changes nothing, the question before it acts: these are rules, and each one is a test. They run in about a second and need no Docker.

The tests have a second purpose that this note is honest about. Reading them with the code shows what they do **not** prove. I changed the code in a scratch copy (a technique called **mutation testing**: break a line on purpose and see if a test notices) and listed the changes that no test caught. They are in "Try it yourself" and in the walkthrough.

## Where it appears

| File | What it tests or provides |
|---|---|
| `haproxy/FakeBalancer.java` | A stand-in for HAProxy's runtime API on a loopback port. Used by three test classes. |
| `haproxy/RuntimeApiTest.java` | `RuntimeApi`, `RuntimeCommand` and `NamedBalancer`. |
| `haproxy/StatTableTest.java` | `StatTable` and `StatRow`, with a real captured answer. |
| `deploy/RollingDeployTest.java` | The deploy algorithm, with a scripted balancer and scripted containers. |
| `deploy/DeployCliTest.java` | The command line: dry run, confirmation, audit, bad usage. |
| `src/test/resources/show-stat-lb2.csv` | The real `show stat` answer the tests read. |

## How it works underneath

**A test is a method.** JUnit looks for methods with `@Test`, creates a fresh object of the test class for each one, runs the method, and reports `PASSED` or `FAILED`. An exception that escapes the method, including a failed AssertJ check, is a failure. Method names here are sentences, such as `changesNothingWithoutAYes`, so a failure line reads like a broken promise.

**Arrange, act, assert.** Each test has three parts. Arrange: build the fakes and the input. Act: call the code. Assert: check the result. In this repository the parts are separated by blank lines.

**Fakes at the edges.** The deploy has two edges. One is `BalancerControl`, an interface with three methods. The other is `Containers`, with two. Because the deploy depends on the interfaces and not on HAProxy or Docker, a test can hand it an object it controls. The runtime API client (`RuntimeApi`) has a different edge: a real network socket. For that, `FakeBalancer` opens a real socket on the loopback address, so the real client code runs against a fake server.

**Loopback.** The address `127.0.0.1` always means "this computer". A program that listens on it can be reached only from the same machine. The port number 0 means "any free port, you choose", so tests do not collide.

**What the tests do not do.** They never start Docker, never start HAProxy, and never call a real clock for the audit file (the audit test uses a fixed clock). The class `DockerCompose` has no test at all.

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
        .hasMessageContaining("backend");
  }
```

The fourth test is the security rule. A command with a semicolon is refused by the constructor. A backend name with a newline in it is refused by `setState`, with a message that mentions `backend`. Both must throw `IllegalArgumentException`. In a scratch copy I removed the name check, and I removed the semicolon check; this test failed each time (it caught both mutations).

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
}
```

The sixth test is about `NamedBalancer.parse`. It parses a good list, a port that is too large (99999), and an address that is not an address (999.210.10.21), and checks the result of each. `isInstanceOf(Result.Success.class)` checks the type of the good result. `isEqualTo(Result.error("..."))` compares with the exact error: a record has `equals`, so two errors with the same text are equal. The exact messages are part of the contract: the user reads them.

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
  void refusesAnAnswerThatIsNotAStatTable() {
    assertThatThrownBy(() -> StatTable.parse("Unknown command: 'show stats'\n"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Unknown command");
    assertThatThrownBy(() -> StatTable.parse(""))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("an empty answer");
  }
```

The fourth test passes text that is not a table. `Unknown command: 'show stats'` gives an `IOException` mentioning the text; an empty string gives one mentioning `an empty answer`.

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

The fifth test builds a row whose `scur` is `many`. Reading `currentSessions` must throw `IllegalStateException` with the column name. `row::currentSessions` is a method reference passed to `assertThatThrownBy`, so the call happens inside the check.

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

The first test restarts both instances with two balancers. It checks that the result is `Success(2)` and then that the recorded calls contain, **in this order**, these calls: drain `seatwave-1` on `lb1` in the first backend, drain it on `lb2` in the second, stop `seatwave-1`, start it, set it ready on both balancers, then drain `seatwave-2` and stop it. `containsSubsequence` allows other calls between the listed ones. It does not check that sessions or the down status were waited for.

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

The second test sets `seatwave/seatwave-2` to `DOWN` before the deploy. The result must be exactly the error `check before: lb1 sees seatwave/seatwave-2 DOWN; nothing was changed`, and `calls` must be empty: no command was sent at all. This is the "refuse to start from a broken state" rule.

```java
  @Test
  void stopsWhenTheDrainedInstanceKeepsItsSessions() throws InterruptedException {
    world.sessionsStayOpen = true;

    Result<Integer, String> result = deploy(List.of(world.balancer("lb1")));

    assertThat(result).isInstanceOf(Result.Error.class);
    assertThat(calls).doesNotContain("stop seatwave-1");
  }
```

The third test makes the sessions stay open (`scur` is 3 forever). The deploy must give up with an error and must never call `stop seatwave-1`. This is the "never stop an instance with running requests" rule. It takes the drain timeout of 200 ms to fail.

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

The fourth test makes the balancer throw `IOException`. The error is exactly `check before: lb1 does not answer: the test balancer is off; nothing was changed`.

```java
  @Test
  void stopsWhenTheRestartedInstanceNeverComesUp() throws InterruptedException {
    world.staysDownAfterStart = true;

    Result<Integer, String> result = deploy(List.of(world.balancer("lb1")));

    assertThat(result).isEqualTo(Result.error("seatwave-1 did not pass its checks within PT0.2S"));
  }
```

The fifth test makes the restarted instance stay `DOWN` after `start`. The deploy must fail with `seatwave-1 did not pass its checks within PT0.2S`. `PT0.2S` is how Java prints a `Duration` of 200 ms (ISO 8601: P for period, T for time, 0.2 seconds).

```java
  private Result<Integer, String> deploy(List<BalancerControl> balancers)
      throws InterruptedException {
    return new RollingDeploy(
            settings(),
            balancers,
            world.containers(),
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
        .run();
  }
```

`deploy` is a helper. It builds a `RollingDeploy` with the settings, the given balancers, the world's containers, and a `PrintStream` that writes into memory and is thrown away. Then it calls `run()`.

```java
  private static DeploySettings settings() {
    return new DeploySettings(
        "seatwave-balancer",
        INSTANCES,
        BACKENDS,
        List.<NamedBalancer>of(),
        Duration.ofMillis(200),
        Duration.ofSeconds(1),
        Duration.ofMillis(200),
        Duration.ZERO,
        Duration.ofMillis(10),
        Path.of("build/unused-audit.log"));
  }
```

`settings()` builds a `DeploySettings` by hand. `List.<NamedBalancer>of()` is an empty list of balancers (the scripted balancers are passed separately). The numbers: drain wait 200 ms, stop timeout 1 s, up wait 200 ms, settle zero, poll every 10 ms. Short timeouts make the failing tests quick. The audit path is not used by `RollingDeploy`.

```java
  /** The servers' status as both balancers see it, and the containers that change it. */
  private final class World {
    private final Map<String, String> status = new ConcurrentHashMap<>();
    private volatile boolean sessionsStayOpen;
    private volatile boolean isAnswering = true;
    private volatile boolean staysDownAfterStart;

    World() {
      for (String backend : BACKENDS) {
        for (String instance : INSTANCES) {
          status.put(backend + "/" + instance, "UP");
        }
      }
    }
```

`World` is an inner class that holds the whole scripted state. `status` maps `backend/instance` to a status text. Three `volatile` flags switch behaviors: sessions stay open, the balancer does not answer, the instance stays down after start. The constructor puts every server of every backend in the state `UP`.

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
        }
      };
    }
```

`balancer(name)` returns an anonymous `BalancerControl`.

- `stat()` throws `IOException` if the flag says the balancer is off. Otherwise it builds a `StatTable` from the map: for each entry it splits the key at the slash and makes a `StatRow` with `pxname`, `svname`, `status` and `scur` (`"3"` if sessions stay open, else `"0"`).
- `setState` only records a call such as `lb1 seatwave/seatwave-1 DRAIN`. It does not change the status.

That last point is the reason for a gap. The fake `DRAIN` does not turn the status into `DRAIN`, so the status in the table stays `UP`, `DOWN` or whatever the containers set.

```java
    Containers containers() {
      return new Containers() {
        @Override
        public void stop(String service) {
          calls.add("stop " + service);
          BACKENDS.forEach(backend -> status.put(backend + "/" + service, "DOWN"));
        }

        @Override
        public void start(String service) {
          calls.add("start " + service);
          if (!staysDownAfterStart) {
            BACKENDS.forEach(backend -> status.put(backend + "/" + service, "UP"));
          }
        }
      };
    }
  }
}
```

`containers()` returns an anonymous `Containers`. `stop` records the call and sets every backend's status for that service to `DOWN` **at once**. `start` records the call and sets `UP`, unless the flag says the instance stays down.

Because `stop` makes the status `DOWN` immediately, the deploy's wait for "seen down" is satisfied on its first look. No test can tell whether that wait exists. I removed it in a scratch copy (changed the condition to `false`) and all 31 tests of the three packages still passed. The same is true of the settle pause: the tests use a settle of zero. A better fake would stay `UP` for a few reads after `stop` and `DOWN` afterwards, and a test would then check that `start` came after the first `DOWN` was read.

### `deploy/DeployCliTest.java`

```java
package io.plainround.seatwave.balancer.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.balancer.haproxy.FakeBalancer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
```

The imports. `Clock`, `Instant` and `ZoneOffset` make a clock that always shows the same moment. `@TempDir` is from JUnit.

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
```

`CLOCK` is fixed at `2026-11-11T09:00:00Z`, so the audit line has a known time. `HEADER` is a one-line `show stat` header with four columns. `@TempDir Path folder` makes JUnit create a fresh temporary folder for each test and delete it afterwards: the settings file and the audit file go there, not into the repository. `out` and `err` collect what the program prints. `containerCalls` records the container calls. `containerStatus` is a one-item array shared with the fake balancer in one test; an array is used because a lambda may share the array but cannot change a plain variable.

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
  void refusesBadUsage() {
    assertThat(run(Path.of("x"), "", "--now")).isEqualTo(DeployCli.EXIT_BAD_USAGE);
    assertThat(text(err)).contains("usage: deploy");
  }
```

The fourth test gives an unknown flag, `--now`, and expects exit status 2 and a `usage: deploy` message. The settings path `x` does not exist and is never read, because the arguments are checked first.

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

`run` is the helper. It builds the argument array from the settings path and the flags with `System.arraycopy`, feeds the text as standard input, collects the two output streams as UTF-8 text, gives the fixed clock, and gives a container factory written as a lambda that returns an anonymous `Containers`. The anonymous class records `stop` and `start` and flips `containerStatus[0]`.

```java
  private Path settings(FakeBalancer balancer) throws IOException {
    return Files.writeString(
        folder.resolve("deploy.properties"),
        String.join(
            "\n",
            "compose.project=seatwave-balancer",
            "deploy.instances=seatwave-1",
            "deploy.backends=seatwave",
            "deploy.balancers=lb1=127.0.0.1:" + balancer.port(),
            "deploy.drainTimeoutMillis=1000",
            "deploy.stopTimeoutMillis=1000",
            "deploy.upTimeoutMillis=1000",
            "deploy.settleMillis=0",
            "deploy.pollMillis=10",
            "deploy.auditFile=" + folder.resolve("audit.log"),
            ""),
        StandardCharsets.UTF_8);
  }
```

`settings` writes a `deploy.properties` file into the temporary folder: the project name, one instance, one backend, one balancer on the fake's port, 1-second timeouts, no settle, a 10-ms poll, and an audit file in the same folder. It joins the lines with newlines. `Files.writeString` writes and returns the path. One portability note: `folder.resolve("audit.log")` is written into a `.properties` file as plain text. On Windows its backslashes would be read as escape characters. The tests as written assume a path with forward slashes.

```java
  private static String text(ByteArrayOutputStream stream) {
    return stream.toString(StandardCharsets.UTF_8);
  }
}
```

`text` turns the collected bytes into a string.

What `DeployCliTest` does not cover, as the coverage report of my scratch copy shows: the flag `--yes`, a deploy that stops with an error (exit status 1 and the audit line `deploy: stopped`), invalid settings (exit status 2), an audit file that cannot be written, an interrupted deploy, and `main` itself.

## Try it yourself

Run these in the repository folder. Nothing needs Docker. Timings differ on every run.

**1. Run the tests of both packages.**

```bash
./gradlew test --tests 'io.plainround.seatwave.balancer.haproxy.*' --tests 'io.plainround.seatwave.balancer.deploy.*' --console=plain | grep -E 'PASSED|FAILED|BUILD'
```

```text
DeployCliTest > printsThePlanAndChangesNothingByDefault() PASSED
DeployCliTest > changesNothingWithoutAYes() PASSED
DeployCliTest > refusesBadUsage() PASSED
DeployCliTest > deploysAfterConfirmationAndWritesTheAudit() PASSED
RollingDeployTest > stopsWhenTheBalancerDoesNotAnswer() PASSED
RollingDeployTest > stopsWhenTheRestartedInstanceNeverComesUp() PASSED
RollingDeployTest > restartsEachInstanceInOrderOnEveryBalancer() PASSED
RollingDeployTest > changesNothingWhenAnInstanceIsNotUpBeforehand() PASSED
RollingDeployTest > stopsWhenTheDrainedInstanceKeepsItsSessions() PASSED
RuntimeApiTest > setsAServerStateAndAcceptsTheEmptyAnswer() PASSED
RuntimeApiTest > refusesAZeroTimeout() PASSED
RuntimeApiTest > readsBalancersFromSettingsAndNamesABadEntry() PASSED
RuntimeApiTest > reportsHaproxysRefusal() PASSED
RuntimeApiTest > sendsOneCommandAndReadsTheWholeAnswer() PASSED
RuntimeApiTest > refusesAnythingThatWouldAddASecondCommand() PASSED
StatTableTest > findsTheBackendSummaryRow() PASSED
StatTableTest > readsTheServersOfABackendByColumnName() PASSED
StatTableTest > refusesAnAnswerThatIsNotAStatTable() PASSED
StatTableTest > reportsAColumnThatIsNotANumber() PASSED
StatTableTest > readsAnEmptyNumberAsZero() PASSED
BUILD SUCCESSFUL in 1s
```

That is 11 tests of the `haproxy` package and 9 of the `deploy` package.

**2. Break the order and watch a test fail.** In a scratch copy of the repository, open `RollingDeploy.java` and delete the line `setEverywhere(instance, AdminState.READY);`. Run command 1 again. `restartsEachInstanceInOrderOnEveryBalancer` fails, because the calls `READY` are missing from the recorded list. (I ran this change.) Undo it.

**3. Find what no test notices.** In the same scratch copy, change `Thread.sleep(settings.settle());` to `Thread.sleep(0);`. All tests pass (I ran this change): the settle time is never checked. Undo it.

**4. See the mutations I ran.** I changed one line at a time in a scratch copy and ran the tests of the three packages. These changes were caught by a test: removing the `READY` call, removing the session wait, accepting any answer to the question `Go ahead?`, accepting a removal before the last answer, ignoring the HAProxy refusal text, `isUp` as an exact match, setting states on one balancer only, removing the name check, allowing a semicolon in a command, keeping the warm-up requests. These were **not** caught: removing the wait for "seen down", ignoring the settle time, a strict instead of inclusive bound in the failed-in-between count, flooring the percentile rank, a removal counted when the server was already out, `<=` in the hold mix, removing the stop-timeout rule of the settings, removing the last watch reading, and counting every 4xx as a failure.

## Mistakes people make

- **A fake that is too eager.** `FakeBalancer` and the scripted world are simple on purpose, but the scripted `stop` sets `DOWN` at once, so the test cannot tell whether the code waits. A fake should be as slow as the real thing in the way the test is about.
- **Checking only the result.** A deploy that restarts in the wrong order can still return `Success`. The first test checks the order of calls.
- **Real clocks in tests.** The audit time is checked against a fixed clock, so the test does not depend on the date.
- **Tests that write into the repository.** `@TempDir` gives every test its own folder and removes it.
- **Waiting with `sleep` for something to happen.** These tests use short timeouts of the code under test (200 ms) and a polling loop of 10 ms, which are bounded. The README says the whole suite ran 40 times in a row beside 8 busy loops with no failure.
- **Leaving a server running.** Every `FakeBalancer` is opened in a `try` block, so its socket is closed even when a check fails.

## Check yourself

1. What does `containsSubsequence` check that `contains` does not? *Answer: The items occur in the given order, with anything allowed between them.*
2. Why does `FakeBalancer` listen on port 0? *Answer: The system picks a free port, so tests running at the same time never collide.*
3. Which rule does `changesNothingWhenAnInstanceIsNotUpBeforehand` prove? *Answer: The deploy refuses to start when an instance is not up, and sends no command before it refuses.*
4. Why can no test notice that the deploy stops waiting for "seen down"? *Answer: The scripted `stop` sets the status to `DOWN` at once, so the wait is satisfied immediately whether it exists or not.*
5. What does `@TempDir` give a test? *Answer: A fresh temporary folder that JUnit deletes afterwards.*

## Further reading

- JUnit User Guide (this repository pins JUnit 6.1.3): https://docs.junit.org/current/user-guide/
- AssertJ Core documentation, 3.27: https://assertj.github.io/doc/
- Java SE 25 API: `java.net.ServerSocket`, `java.time.Clock`, `java.util.concurrent.CopyOnWriteArrayList`: https://docs.oracle.com/en/java/javase/25/docs/api/
- HAProxy 3.4 Management Guide (the answers the fake imitates): https://docs.haproxy.org/3.4/management.html
