# The `haproxy` package: asking a running load balancer what it sees and telling it what to do

## What it is

The `haproxy` package is a small client written in Java. It talks to a program called **HAProxy**. HAProxy is a **load balancer**: it sits between the people who send requests and several identical servers, and it passes each request to one of those servers. This repository runs two HAProxy programs side by side, called `lb1` and `lb2`. Behind them run three copies of the SeatWave service: `seatwave-1`, `seatwave-2` and `seatwave-3`.

A running HAProxy can be controlled without restarting it. It listens on a **socket** for text commands. A socket here means a network address: an IP address (which machine) plus a port number (which door on that machine). A program connects to that address, sends one line of text, and reads the answer. This door is called the **runtime API**. This package is the Java side of that conversation.

Two commands matter to this repository:

- `show stat` asks for a table of numbers about every frontend, backend and server that HAProxy knows. HAProxy writes it as **CSV** (comma-separated values: one row per line, cells separated by commas).
- `set server <backend>/<server> state <state>` changes one server's **administrative state**: `ready` (normal), `drain` (take no new requests, finish the running ones) or `maint` (maintenance: no requests and no health checks).

The package has five files. `RuntimeCommand` builds a command safely. `RuntimeApi` sends it and reads the answer. `NamedBalancer` pairs a balancer's name with its address. `StatTable` and `StatRow` turn the CSV into Java objects.

## Why this repository uses it

Module 10 asks two questions about a balancer: how long does it take to notice that a server died, and can you replace every server without a single failed request? Both answers live inside HAProxy. Only HAProxy knows the moment it marked a server `DOWN`, and only HAProxy can be told to stop sending new requests to a server before that server is stopped.

So two other packages depend on this one. The `harness` package reads `show stat` ten times a second during a load run to learn when each server changed status (see `java-harness-walkthrough-1.md` and `java-harness-walkthrough-2.md`). The `deploy` package sets `drain` and `ready` and polls `show stat` while it restarts instances (see `java-deploy-walkthrough.md`). The package depends on nothing but the JDK and the `result4j` library, which the rule in `ARCHITECTURE.md` requires, so a test can replace HAProxy with a few lines of Java.

## Where it appears

| File | What it does |
|---|---|
| `haproxy/RuntimeCommand.java` | One command line, built only from checked parts. |
| `haproxy/RuntimeApi.java` | Opens a connection, sends one command, reads the whole answer. |
| `haproxy/NamedBalancer.java` | A balancer's name and its `RuntimeApi`, parsed from `lb1=10.210.10.21:9999`. |
| `haproxy/StatTable.java` | The parsed answer of `show stat`: a list of rows. |
| `haproxy/StatRow.java` | One row, read by column name. |
| `test/.../haproxy/FakeBalancer.java` | A stand-in for HAProxy's runtime API, used by tests (see `tests-tools-walkthrough-1.md`). |
| `test/.../haproxy/RuntimeApiTest.java`, `StatTableTest.java` | The tests of this package. |
| `src/test/resources/show-stat-lb2.csv` | A real `show stat` answer of `lb2`, captured from the running stack. |
| `haproxy/haproxy.cfg` | The line `stats socket ipv4@0.0.0.0:9999 level admin` opens the door (not part of this package). |
| `config/*.properties` | The keys `watch.balancers` and `deploy.balancers` list the balancers as `name=address:port`. |

## How it works underneath

**One connection, one command.** The client opens a TCP connection to the balancer's runtime API port, writes one line ending in a newline, and reads. HAProxy runs the command, writes the answer, and closes the connection. The client therefore reads until the connection ends. That is how the runtime API behaves when you do not ask it for an interactive session.

**What the answer looks like.** For `show stat` the answer is CSV. The first line starts with `# ` and names the columns. This repository's captured answer has 214 columns, from `pxname` and `svname` to `srid`. Every following line is one row. HAProxy ends every line with a comma, so cutting a line at the commas leaves one empty cell at the end.

**Three kinds of rows.** A row describes a **frontend** (where requests come in), a **backend** (a group of servers that requests go out to) or one **server**. The cell `pxname` holds the frontend or backend name, and `svname` holds the server name, or the word `FRONTEND` or `BACKEND` for a summary row. In `haproxy.cfg` the backend `seatwave` holds the three servers; its summary row adds up their numbers.

**The columns this package reads.**

| Column | Meaning |
|---|---|
| `pxname`, `svname` | Which backend, which server. |
| `status` | `UP`, `DOWN`, `DRAIN`, `MAINT`, or a status in transition such as `UP 2/3`. |
| `scur` | Sessions now: how many requests the server is working on at this moment. |
| `stot` | Sessions in total since HAProxy started. |
| `wretr`, `wredis` | Retries made, and requests moved to another server after a failure. |
| `check_status` | The result of the last health check, for example `L7OK` (the HTTP check passed). |
| `weight` | How big a share of requests the server gets; slow start raises it step by step. |

**Reading a status.** A **health check** is a small request HAProxy sends to a server every two seconds in this repository (`inter 2s`). Three failed checks in a row (`fall 3`) mark the server `DOWN`. Two good checks (`rise 2`) mark it `UP` again. In between, the status carries a counter. After the first failed check an `UP` server reads `UP 2/3`, after the second `UP 1/3`, and after the third `DOWN`. This is the order the README's captured run shows (`UP -> UP 2/3 -> UP 1/3 -> DOWN`). A server that was `DOWN` and passes its first check reads `DOWN 1/2`, then `UP`. A server in the `drain` state reads `DRAIN`, and if it also fails its checks the counters appear after the word.

**Why columns are found by name.** The table has 214 columns and a new HAProxy version may add more. If the code counted positions, an added column would silently shift every value. Reading by the name in the header line survives that.

## Walkthrough of this repository's use

### `haproxy/RuntimeCommand.java`

A command is text sent to a program that obeys text. If a name that came from a settings file could contain a newline or a semicolon, it could start a second command. This class makes that impossible.

```java
package io.plainround.seatwave.balancer.haproxy;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
```

These are the imports. `package` says which folder-like group of classes this file belongs to. Each `import` lets the file use a class from another package by its short name. `Locale` controls how text changes case, `Objects` has null checks, and `Pattern` is a compiled **regular expression**: a short text that describes which strings are allowed. `Collectors` gathers the items of a stream into one result; `visible` below uses it.

```java
/**
 * One command for HAProxy's runtime API, built only from checked parts, so no input can add a
 * second command (a newline or a semicolon) to the line.
 *
 * @param line the command text, without the newline
 */
public record RuntimeCommand(String line) {

  // HAProxy names of backends and servers in this project: letters, digits, - and _.
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
```

The comment before `public record` is Javadoc: documentation that tools turn into pages. `{@code ...}` marks a piece of code inside it, and `@param line` documents the one value this class holds.

A **record** is a short way to write a class that only holds values. `RuntimeCommand(String line)` makes a class with one field called `line`. The compiler writes the constructor, the method `line()` that returns the field, and the methods `equals`, `hashCode` and `toString`. A record cannot be changed after it is created, so a command cannot change between the check and the sending.

`NAME` is a pattern: one to 64 characters out of letters, digits, `-` and `_`. `private static final` means it belongs to the class, only this file can see it, and it never changes. The backend and server names in this repository (`seatwave`, `seatwave_active_only`, `seatwave-2`) all fit.

```java
  /** Checks the line. */
  public RuntimeCommand {
    Objects.requireNonNull(line, "line");
    if (line.contains("\n") || line.contains(";")) {
      throw new IllegalArgumentException("a runtime command is one line with one command");
    }
  }
```

This is a **compact constructor**: it is written without a parameter list and runs first, before the field is set. If a check throws an exception, the object is never created. `Objects.requireNonNull` throws if the value is `null`. Then the code refuses any line with a newline (`\n`) or a semicolon, the two ways to put a second command on the line. `IllegalArgumentException` is the Java exception for "you gave me a bad value".

```java
  /**
   * Returns {@code show stat}, the CSV of every frontend, backend and server.
   *
   * @return the command
   */
  public static RuntimeCommand showStat() {
    return new RuntimeCommand("show stat");
  }
```

`showStat()` is a **static factory method**: a method that belongs to the class and returns a new object, so the caller writes `RuntimeCommand.showStat()` and never types the command text. The text is fixed.

```java
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
```

`setState` builds `set server seatwave/seatwave-2 state drain`. It checks that `state` is not null. It sends both names through `checked`, and it writes the state in lower case, because HAProxy wants `drain` and the Java value is `DRAIN`. `state.name()` returns the constant's name as text, and `Locale.ROOT` makes the case change the same on every computer (without it, a computer set to Turkish would turn `I` into a different letter). The `+` operators join the pieces into one line, and the line then passes through the compact constructor above, so even this path is checked twice.

```java
  private static String checked(String name, String what) {
    Objects.requireNonNull(name, what);
    if (!NAME.matcher(name).matches()) {
      throw new IllegalArgumentException(
          what + " must match " + NAME.pattern() + ", but was \"" + visible(name) + "\"");
    }
    return name;
  }

  private static String visible(String text) {
    // A control character is shown as its code, so a newline in a name cannot break the message
    // into two lines (one of which a reader would take for another message).
    return text.codePoints()
        .mapToObj(codePoint ->
            Character.isISOControl(codePoint)
                ? String.format(Locale.ROOT, "\\u%04x", codePoint)
                : Character.toString(codePoint))
        .collect(Collectors.joining());
  }
```

`checked` returns the name unchanged if the whole name matches the pattern, and throws otherwise. `matcher(name).matches()` asks whether the entire text fits the pattern, not just a part of it. The message names which argument was wrong (`backend` or `server`), prints the pattern, and then the bad value in double quotes, written with `\"` because a plain `"` would end the Java text.

The bad value goes through `visible` first. A **control character** is a character that is not printed but does something, such as a newline (code 10) or a tab (code 9). The comment says why they are replaced: a newline inside a name would break the message into two lines, and a reader could take the second line for another message.

- `text.codePoints()` gives the characters of the text as a stream of numbers, one **code point** (the character's number in Unicode) each.
- `mapToObj` turns each number back into a piece of text. `Character.isISOControl(codePoint)` is true for the control characters (codes 0 to 31 and 127 to 159).
- A control character becomes `\u` and its code as four hexadecimal digits: `String.format(Locale.ROOT, "\\u%04x", codePoint)`, where `\\` in Java source is one backslash, `%04x` is the number in hexadecimal padded with zeros to four digits. A newline becomes the six characters `\u000a`.
- Any other character is kept as it is with `Character.toString(codePoint)`.
- `Collectors.joining()` glues the pieces back into one text.

So `setState("seatwave", "a\nb", ...)` fails with a one-line message that ends in `but was "a\u000ab"`. Version 1.0.0 printed the raw newline.

```java
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
```

An **enum** is a type with a fixed list of values. `AdminState` has `READY`, `DRAIN` and `MAINT`, the three states the runtime API can set. A caller cannot pass a fourth state, because there is no way to write one. The comment on each says what HAProxy does in that state.

### `haproxy/RuntimeApi.java`

```java
package io.plainround.seatwave.balancer.haproxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
```

The imports: the input and output classes, `Socket` for the network connection, `InetSocketAddress` for an address plus a port, `StandardCharsets` for text encodings, `Duration` for lengths of time.

```java
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
```

This record holds two values: the balancer's `address` and a `timeout`. The Javadoc says the class is thread-safe. A **thread** is one line of work inside a program; several threads can run at the same time. A class is thread-safe when many threads can use one object without breaking it. Here that holds because a record never changes, and each `send` opens its own connection.

`MAX_ANSWER_BYTES` is 1,048,576, one megabyte. Writing the number as `1_048_576` is allowed: the underscores are only for the eyes. The comment explains the limit: an answer of the lab's `show stat` is a few kilobytes, so something larger is not a `show stat` and reading stops.

```java
  /** Checks the fields. */
  public RuntimeApi {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("the timeout must be positive: " + timeout);
    }
  }
```

The compact constructor rejects `null` and a timeout that is zero or negative. A zero timeout would mean "wait forever" on some sockets, which is the opposite of what a timeout is for.

```java
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
```

This is the heart of the package. Line by line:

- `Objects.requireNonNull(command, "command")` refuses a missing command.
- `Math.toIntExact(timeout.toMillis())` turns the timeout into a whole number of milliseconds and throws if it does not fit in an `int`. The socket methods want an `int`.
- `try (Socket socket = new Socket()) { ... }` is a **try-with-resources** block. Whatever is declared in the parentheses is closed automatically when the block ends, even when an exception is thrown. So the connection is never left open. `new Socket()` creates an unconnected socket.
- `socket.connect(address, timeoutMillis)` opens the connection and gives up after the timeout. This is where a dead balancer shows itself: a connection to a stopped container fails or times out.
- `socket.setSoTimeout(timeoutMillis)` sets the **read timeout**. A read that waits longer than this throws `SocketTimeoutException`, a kind of `IOException`.
- `out.write((command.line() + "\n").getBytes(StandardCharsets.US_ASCII))` writes the command and the newline that ends it. Programs talk over a network in bytes, so the text is turned into bytes first; the runtime API is plain ASCII. `out.flush()` pushes the bytes out now instead of waiting in a buffer.
- `in.readNBytes(MAX_ANSWER_BYTES + 1)` reads until the other side closes the connection, or until that many bytes have arrived. Asking for one byte more than the limit is how the next line can tell "exactly at the limit" from "over the limit".
- If more than the limit arrived, the method throws an `IOException` that names the address. `IOException` is the Java exception for trouble talking to the outside world. The method declares `throws IOException`, which forces every caller to decide what to do about it.
- Otherwise the bytes become a `String`, and the method returns it.

```java
  /**
   * Reads every row of {@code show stat}.
   *
   * @return the table
   * @throws IOException if the command fails or the answer is not a stat table
   */
  public StatTable showStat() throws IOException {
    return StatTable.parse(send(RuntimeCommand.showStat()));
  }
```

`showStat()` sends the `show stat` command and hands the answer to `StatTable.parse`.

```java
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
```

`setState` sends the `set server` command. HAProxy answers an accepted `set server` with an empty line, so `strip()` removes the whitespace and an empty result means success. Any other text is HAProxy explaining why it refused, for example `No such server.`. The code turns it into an `IOException` that names the address, the backend and the server and ends with HAProxy's own words.

### `haproxy/NamedBalancer.java`

```java
package io.plainround.seatwave.balancer.haproxy;

import com.github.sviperll.result4j.Result;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
```

`Result` comes from the `result4j` library. It is a type with two cases, `Success` and `Error`, that a method returns when failure is expected and ordinary. A bad line in a settings file is such a failure, so `parse` returns a `Result` instead of throwing.

```java
/**
 * A balancer by its lab name, with the address of its runtime API, as a settings file writes it:
 * {@code lb1=10.210.10.21:9999}. Immutable.
 *
 * @param name the balancer's name, the same as its {@code X-Balancer} header
 * @param api the client for its runtime API
 */
public record NamedBalancer(String name, RuntimeApi api) {

  // A port from 1 up, without a leading zero; the address is checked once it is parsed.
  private static final Pattern ENTRY =
      Pattern.compile("([a-z][a-z0-9-]{0,31})=([0-9.]{7,15}):([1-9][0-9]{0,4})");
  private static final int HIGHEST_PORT = 65_535;
```

A balancer is its name and its `RuntimeApi`. The name is the one that HAProxy puts in the `X-Balancer` response header, so the harness can count answers per balancer.

`ENTRY` says what one list item looks like. The pattern `([a-z][a-z0-9-]{0,31})=([0-9.]{7,15}):([1-9][0-9]{0,4})` has three **groups**, the parts in parentheses: a name that starts with a lowercase letter (one to 32 characters), then `=`, then an address made of seven to 15 digits and dots, then `:`, then a port of one to five digits whose first digit is 1 to 9. The comment says what that last part rules out: port 0 and a leading zero such as `09999`. The address is checked once it is parsed, in `parse` below. `HIGHEST_PORT` is 65,535, the largest port number.

```java
  /** Checks the fields. */
  public NamedBalancer {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(api, "api");
  }
```

The compact constructor only checks for null.

```java
  /**
   * Reads a list of {@code name=address:port} entries.
   *
   * @param entries the items of a settings key
   * @param timeout the runtime API timeout for every balancer
   * @return the balancers in order, or the first entry that is not valid, with the reason
   */
  public static Result<List<NamedBalancer>, String> parse(List<String> entries, Duration timeout) {
    Objects.requireNonNull(entries, "entries");
    Objects.requireNonNull(timeout, "timeout");
    List<NamedBalancer> balancers = new ArrayList<>();
    for (String entry : entries) {
      Matcher matcher = ENTRY.matcher(entry);
      if (!matcher.matches() || Integer.parseInt(matcher.group(3)) > HIGHEST_PORT) {
        return Result.error(
            "'" + entry + "' is not name=address:port, such as lb1=10.210.10.21:9999");
      }
      InetAddress address;
      try {
        address = InetAddress.ofLiteral(matcher.group(2));
      } catch (IllegalArgumentException notAnAddress) {
        return Result.error("'" + entry + "' does not hold an IP address");
      }
      // ofLiteral also reads short and padded forms (127.0.1, 010.0.0.1); only the dotted form
      // that prints back the same is the address the reader meant.
      if (!address.getHostAddress().equals(matcher.group(2))) {
        return Result.error(
            "'" + entry + "' does not hold a dotted IPv4 address with four parts and no padding");
      }
      balancers.add(
          new NamedBalancer(
              matcher.group(1),
              new RuntimeApi(
                  new InetSocketAddress(address, Integer.parseInt(matcher.group(3))), timeout)));
    }
    return Result.success(List.copyOf(balancers));
  }
}
```

`parse` turns a list such as `lb1=10.210.10.21:9999,lb2=10.210.10.22:9999` (already split at the commas by the settings reader) into balancers.

- The generic type `Result<List<NamedBalancer>, String>` reads "either a list of balancers, or a text that explains the first problem".
- For each entry the code asks the `Matcher` whether the whole entry fits `ENTRY`. A `Matcher` holds the result of applying a pattern to a text. If it does not fit, or the port number is larger than 65,535, the method returns `Result.error(...)` at once, with a message that shows the expected shape. The pattern cannot rule out `99999` by itself, which is why the port is compared as a number.
- `matcher.group(2)` is the address text. `InetAddress.ofLiteral` turns text into an address object and throws `IllegalArgumentException` if the text is not an address, without asking the network (a name such as `lb1.example` would otherwise cost a DNS lookup). For `999.210.10.21` the pattern passes but `ofLiteral` refuses, and the message says "does not hold an IP address".
- `ofLiteral` also accepts forms a reader would not expect, as the comment says. I ran it on Java 25.0.2: `127.0.1` reads as `127.0.0.1` (the JDK reads the last number as the remaining two bytes), and `010.0.0.1` reads as `10.0.0.1` (the leading zero is dropped). `address.getHostAddress()` prints the address back in its full dotted form. If that differs from the text in the entry, the entry was written in a short or padded form, and the method returns the error "does not hold a dotted IPv4 address with four parts and no padding". Version 1.0.0 accepted both forms; the test `refusesTheShortAndPaddedFormsOfAnEntry` in `RuntimeApiTest` now checks the refusal.
- A new `NamedBalancer` is built with `new InetSocketAddress(address, port)`. The same `timeout` is given to every balancer.
- `List.copyOf` returns a list that cannot be changed, and `Result.success` wraps it.

### `haproxy/StatRow.java`

```java
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
```

A `StatRow` holds a `Map<String, String>`: a collection that finds a value by its key, here the cell text by column name. The compact constructor calls `Map.copyOf`, which makes an unchangeable copy, so a row cannot change after it is made.

```java
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
```

Each of these methods reads one column by name and gives it a meaningful Java name. `proxy()` returns `pxname`, `server()` returns `svname`, `status()` returns `status`. They return the text as HAProxy wrote it.

`isUp()` says whether the balancer may send new requests now. It returns true if the status starts with `UP`. That includes `UP 2/3` and `UP 1/3`, because a server whose checks have started to fail still gets requests until the third failure. `DRAIN`, `MAINT`, `DOWN` and `NOLB` all return false.

`isFullyUp()` is stricter: it is true only for the exact text `UP`. A server at `UP 1/3` or `UP 2/3` has failed checks counted against it and is on its way down, as the Javadoc says. The rolling deploy (`deploy/RollingDeploy.java`) uses this method before it changes anything and after a restart, so it never takes an instance out while another one is already failing.

```java
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
```

The number columns: `currentSessions()` reads `scur`, `retries()` reads `wretr`, `redispatches()` reads `wredis`, `totalSessions()` reads `stot`, `weight()` reads `weight`, and `checkStatus()` reads the text of `check_status`. The Javadoc says what each one means in HAProxy's management guide.

`secondsSinceChange()` reads `lastchg`, which the management guide describes as the number of seconds since the last transition between up and down. HAProxy leaves the cell empty for a frontend's summary row (the test capture shows it), and the method then returns zero, as the Javadoc says. No main program calls it; the stack tests (`StackTest`) use it together with `isFullyUp()` to wait until every instance has been fully up for longer than slow start, so a test starts on a settled stack.

```java
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
```

`text` returns the cell, or an empty string when the column is not there. `getOrDefault` is the map method for that.

`number` turns a cell into a `long` (a whole number that can be very large). An empty cell is zero: HAProxy leaves a cell empty when it does not count that thing for that row. `Long.parseLong` throws `NumberFormatException` for text such as `many`. The code catches it and throws `IllegalStateException` with the column name and the bad cell, so the message says what was wrong. The original exception is passed as the second argument, the **cause**, so a stack trace shows both. (Version 1.0.0 wrapped the cell in `Objects.toString(cell)`, a call that did nothing because `cell` is already a `String`; it is gone, and so is the import of `Objects`.)

### `haproxy/StatTable.java`

```java
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
```

A `StatTable` is a list of rows. Its Javadoc states two rules that the code follows: the first line starts with `# ` and names the columns, and columns are found by name.

```java
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
```

`parse` is the only way in.

- `csv.lines()` splits the text into lines, `.filter(line -> !line.isBlank())` drops empty lines, and `.toList()` collects the rest. The part `line -> !line.isBlank()` is a **lambda**: a short unnamed function. A stream is a chain of steps over a collection.
- The first line must exist and start with `# `. If not, the text is not a `show stat` answer. HAProxy answers an unknown command or a refused command with a sentence, for example `Unknown command: 'show stats'`. The code throws an `IOException` with that first line, or with `an empty answer`, so the cause is visible.
- `lines.getFirst().substring(2)` removes the `# ` and `.split(",", -1)` cuts at the commas. The second argument `-1` keeps empty cells at the end. Without it Java would drop them, and the number of cells would not match the number of names. The comment on the line above says the same.
- For each remaining line the code cuts the cells the same way and fills a `HashMap`, a map that can be changed while it is built. The loop condition `column < names.size() && column < cells.length` stops at the shorter of the two, so a short line cannot cause an index error.
- Each map becomes a `StatRow`, and the list becomes a `StatTable`.

```java
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
```

Three lookups, all built from streams.

- `servers(backend)` keeps the rows whose `pxname` is the backend, and drops the `BACKEND` and `FRONTEND` summary rows. What remains is the real servers.
- `server(backend, server)` picks one of them by name. It returns an `Optional`, a box that holds a value or is empty, so the caller must handle "no such server" instead of getting `null`. `findFirst` takes the first match.
- `backendSummary(backend)` returns the `BACKEND` row of a backend. The harness reads its `stot`, `wretr` and `wredis` before and after a run, to report how many requests the balancer passed and retried.

## Try it yourself

Run these in the repository folder. Gradle needs a JDK 25 and downloads nothing here beyond what the build already has. Timings differ on every run.

**1. Run the tests of this package.**

```bash
./gradlew test --tests 'io.plainround.seatwave.balancer.haproxy.*' --console=plain | grep -E 'PASSED|FAILED|BUILD'
```

```text
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
BUILD SUCCESSFUL in 1s
```

The package has 13 tests: 7 in `RuntimeApiTest` and 6 in `StatTableTest`. The time on the last line differs on every run.

**2. Look at the real answer the tests use.** `cut -d, -f1,2,18` keeps columns 1, 2 and 18 of the CSV, which are `pxname`, `svname` and `status`.

```bash
cut -d, -f1,2,18 src/test/resources/show-stat-lb2.csv | head -n 9
```

```text
# pxname,svname,status
public,FRONTEND,OPEN
seatwave,seatwave-1,UP
seatwave,seatwave-2,UP
seatwave,seatwave-3,UP
seatwave,BACKEND,UP
seatwave_active_only,seatwave-1,UP
seatwave_active_only,seatwave-2,UP
seatwave_active_only,seatwave-3,UP
```

**3. Use the classes from a shell.** `jshell` is the Java shell that comes with the JDK. This feeds it a short script on its standard input. Run `./gradlew classes` first if the folder `build/classes` does not exist.

```bash
jshell --class-path build/classes/java/main - <<'EOF'
import io.plainround.seatwave.balancer.haproxy.*;
System.out.println(RuntimeCommand.setState("seatwave", "seatwave-2", RuntimeCommand.AdminState.DRAIN).line());
try { RuntimeCommand.setState("seatwave\nshow", "s1", RuntimeCommand.AdminState.MAINT); } catch (IllegalArgumentException e) { System.out.println(e.getMessage()); }
var table = StatTable.parse(java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/show-stat-lb2.csv")));
table.servers("seatwave").forEach(r -> System.out.println(r.server() + " " + r.status() + " " + r.checkStatus() + " weight " + r.weight() + " scur " + r.currentSessions() + " fully up " + r.isFullyUp() + " lastchg " + r.secondsSinceChange()));
System.out.println(table.backendSummary("seatwave").get().status());
EOF
```

```text
set server seatwave/seatwave-2 state drain
backend must match [A-Za-z0-9_-]{1,64}, but was "seatwave\u000ashow"
seatwave-1 UP L7OK weight 1 scur 0 fully up true lastchg 190
seatwave-2 UP * L7OK weight 1 scur 0 fully up true lastchg 70
seatwave-3 UP L7OK weight 1 scur 0 fully up true lastchg 190
UP
```

The second line shows the refusal: the name with a newline in it never becomes a command. The message stays on one line, because `visible` wrote the newline as `\u000a` (version 1.0.0 printed the raw newline, and the word `show` appeared on a line of its own). Each server line shows `fully up true`, since every status in this capture is exactly `UP`, and `lastchg`: the seconds since its last change when the capture was taken. The `*` in `UP * L7OK` belongs to the check status, `* L7OK`, and is how HAProxy marks a check that was running at the moment of the capture.

**4. Break a check on purpose.** In a scratch copy of the repository, change the line `if (!NAME.matcher(name).matches()) {` in `RuntimeCommand.java` to `if (false) {` and run the first command again. `refusesAnythingThatWouldAddASecondCommand` fails (I ran this change in a scratch copy). Undo the change afterwards.

## Mistakes people make

- **Building a command by gluing text together.** A name from a file or a user can carry a newline or a semicolon and become a second command. This repository builds commands only through `RuntimeCommand`, which checks every part, and tests that a name with a newline is refused.
- **Counting columns by position.** HAProxy has added columns between versions. This package finds columns by the names in the header line.
- **Forgetting the trailing comma.** Without `split(",", -1)`, Java throws away the last empty cell and the cell count differs between header and rows.
- **Treating `UP 1/3` as down, or as healthy.** It is up, one failed check away from `DOWN`. `isUp()` follows HAProxy: the balancer still sends it requests. `isFullyUp()` asks the stricter question a deploy needs: is it exactly `UP`, with no failed check counted?
- **Waiting forever on a dead balancer.** Both the connect and the read have a timeout. The harness depends on that: a killed balancer must show up as "unreachable", not as a hang.
- **Leaving the runtime API open.** This lab opens it at `admin` level on every address of the lab network, and the comment in `haproxy.cfg` says so. In production it belongs on a management network or a UNIX socket.

## Check yourself

1. Why does `RuntimeApi.send` read until the connection ends instead of reading one line? *Answer: HAProxy writes the answer and closes the connection. A `show stat` answer has many lines, so only the end of the connection marks the end of the answer.*
2. What does an empty answer to `set server` mean? *Answer: HAProxy accepted the command. Any text means it refused, and `setState` turns that text into an exception.*
3. Why does `isUp()` return true for `UP 1/3`? *Answer: The server has failed two checks but not the third, and HAProxy still sends it requests until it reaches `DOWN`.*
4. What does `split(",", -1)` change? *Answer: It keeps the empty cells at the end of a line, so the cells line up with the column names.*
5. Why is the `-1` needed for HAProxy's output in particular? *Answer: HAProxy ends every line with a comma, so every line has an empty last cell.*

## Further reading

- HAProxy 3.4 Management Guide, "Unix Socket commands" (the runtime API) and "Statistics and monitoring" (the `show stat` columns): https://docs.haproxy.org/3.4/management.html
- HAProxy 3.4 Configuration Manual, `stats socket`: https://docs.haproxy.org/3.4/configuration.html
- Java SE 25 API: `java.net.Socket`, `java.util.regex.Pattern`, `java.util.Optional`: https://docs.oracle.com/en/java/javase/25/docs/api/
- result4j (the `Result` type): https://github.com/sviperll/result4j
