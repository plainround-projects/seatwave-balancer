# Logging with SLF4J 2.0.20 and Logback 1.6.6

## What it is

A **log** is a text record that a program writes about what it is doing: "seatwave-1 is READY", "the shared database is unavailable". One line per event, with the time. Logs are how a person finds out afterwards what a running server did.

Two libraries cooperate here. **SLF4J** (Simple Logging Facade for Java) is a **facade**: a small front door with the calls the code uses (`LOG.info(...)`) and no ability to write anything. **Logback** is the **engine** behind the door: it decides which lines are kept, how each line looks and where it goes. The code talks only to SLF4J. If the team wanted another engine, no line of code would change.

Four ideas are needed:
- A **logger** is an object with a name, usually the name of the class that owns it. The code asks SLF4J for one: `LoggerFactory.getLogger(SeatWaveServer.class)`.
- A **level** says how important a line is. The five levels from least to most important are `TRACE`, `DEBUG`, `INFO`, `WARN` and `ERROR`. Logback keeps a line only if its level is at least the configured level.
- An **appender** is a destination: the console, a file, a network socket.
- The **MDC** (mapped diagnostic context) is a small map of text values that belongs to the current thread. A pattern can print a value from it on every line. The code puts a request's id there, and every line written while that request runs carries the id.

## Why this repository uses it

Three SeatWave instances run behind a balancer, and a question that raises is "which line in which instance's log belongs to which request, and which instance wrote it?" Each instance gives every request it admits a random id (32 hexadecimal characters) and puts it in the MDC, so every log line written while that request runs carries the id, and the same id comes back to the client inside the error body (`urn:seatwave:request:<id>`). The comment at the top of `logback.xml` states the goal: "One line per lifecycle event and per warning, with the request's correlation id when there is one."

A second reason is that the logs go to standard error, so a tool's report on standard output stays clean (`java-harness-walkthrough-1.md`).

A third reason is cost. The comment in `logback.xml` says it: holds are logged at `DEBUG`, because at 1,000 holds per second per instance an `INFO` line each would cost more than the hold. At the default level they are not written at all.

## Where it appears

| Path | What it does with logging |
| --- | --- |
| `gradle/libs.versions.toml` | `slf4j = "2.0.20"` and `logback = "1.6.6"`, with the entries `slf4j-api` and `logback-classic` (`toml-version-catalog.md`). |
| `build.gradle.kts` | `implementation(libs.slf4j.api)` and `runtimeOnly(libs.logback.classic)` (`gradle.md`). |
| `gradle.lockfile` | Locks `slf4j-api` on all four classpaths, `logback-classic` and `logback-core` on the two run classpaths (`dependency-locking.md`). |
| `src/main/resources/logback.xml` | Logback's configuration: one console appender, one pattern, root level `INFO`, and a line for HikariCP. |
| `src/main/java/.../seatwave/SeatWaveServer.java` | Creates a logger, puts and clears the MDC value, logs the lifecycle at `INFO`. |
| `src/main/java/.../seatwave/HoldRoutes.java` | Creates a logger, logs each hold at `DEBUG` and a database failure at `WARN`. |
| HikariCP (`hikaricp.md`) | A library that logs through SLF4J too; its lines pass through the same file. |

Only these two classes of this repository use the logging API. I searched `src` for `LoggerFactory` and `MDC` and found no other file.

## How it works underneath

1. The first time the code calls `LoggerFactory.getLogger(...)`, SLF4J looks for an engine. SLF4J 2.x uses the JDK's **service loader**: it asks the JVM for every class listed in a file named `META-INF/services/org.slf4j.spi.SLF4JServiceProvider` on the classpath. The Logback jar has such a file, and it names `ch.qos.logback.classic.spi.LogbackServiceProvider` (I read it with `unzip -p`). With no engine on the classpath, SLF4J would print a warning and drop every line.
2. Logback then looks for its configuration: a file named `logback.xml` on the classpath. This repository's file is in `src/main/resources`, which Gradle copies into the jar and onto the classpath of the tests. If Logback finds no file, it uses a built-in default that writes every level from `DEBUG` up to standard output, with a different pattern.
3. A call such as `LOG.info("held {} for {} s", seat, 300)` first checks the level. The `{}` markers are replaced by the arguments only if the line will be kept, so a dropped `DEBUG` line costs almost nothing.
4. A kept line goes to the appender. The appender's **encoder** turns the event into text with the **pattern**, and the appender writes it.
5. The MDC is stored per thread. A server that reuses threads (this one uses a pool of 64 workers) must clear the MDC at the end of each request, or the next request on that thread would show the old id.

## Walkthrough of this repository's use

### `src/main/resources/logback.xml`

```xml
<configuration>
  <!-- One line per lifecycle event and per warning, with the request's correlation id when there is
       one. Holds are logged at DEBUG: at 1,000 holds per second per instance an INFO line each
       would cost more than the hold. -->
  <!-- Log lines go to standard error, so a tool's report on standard output stays clean. -->
  <appender name="console" class="ch.qos.logback.core.ConsoleAppender">
    <target>System.err</target>
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} %-5level [%X{correlationId:-none}] %logger{0} - %msg%n</pattern>
    </encoder>
  </appender>
  <!-- HikariCP's own INFO lines (pool start and stop) are kept; its DEBUG chatter is not. -->
  <logger name="com.zaxxer.hikari" level="INFO"/>
  <root level="INFO">
    <appender-ref ref="console"/>
  </root>
</configuration>
```

- `<configuration>` is the root element of a Logback file. XML is text made of **elements**: a start tag `<name>`, content, and an end tag `</name>`. An element with no content can be written in one piece, with a `/` before the `>`, as in `<appender-ref ref="console"/>`.
- Lines 2 to 5 are two XML comments (`<!-- ... -->`). The first says what each line is for: one line per lifecycle event and per warning, with the request's correlation id when there is one, and why holds are not at `INFO`. A **correlation id** is an identifier that ties together the records of one request. The second says: "Log lines go to standard error, so a tool's report on standard output stays clean."
- `<appender name="console" class="ch.qos.logback.core.ConsoleAppender">` defines an appender called `console`, of the Logback class `ConsoleAppender`.
- `<target>System.err</target>` makes the appender write to **standard error**, the second output stream of a program (standard output is the first). A command-line tool prints its result on standard output and its log on standard error, so `> result.txt` captures only the result.
- `<encoder>` holds the settings that turn an event into text, and `<pattern>%d{HH:mm:ss.SSS} %-5level [%X{correlationId:-none}] %logger{0} - %msg%n</pattern>` is the layout. Each `%` item means:

| Item | Meaning |
| --- | --- |
| `%d{HH:mm:ss.SSS}` | The time of the event, hours, minutes, seconds and milliseconds, for example `07:36:57.559`. |
| `%-5level` | The level, padded on the right to five characters, so `INFO` becomes `INFO ` and the columns line up. |
| `[%X{correlationId:-none}]` | The MDC value named `correlationId` between square brackets. The `:-none` after the name is a default: when the MDC has no such value, print `none`. |
| `%logger{0}` | The logger name shortened to its last part. The `0` means "no package at all", so `io.plainround.seatwave.balancer.seatwave.SeatWaveServer` prints as `SeatWaveServer`. |
| ` - ` | A literal separator. |
| `%msg` | The message, after the `{}` markers are filled in. |
| `%n` | A line break. |

- `<logger name="com.zaxxer.hikari" level="INFO"/>` sets the level of one named logger and everything below it in the name tree. The comment above it says what it is for: HikariCP's own `INFO` lines (the pool's start and stop) are kept, and its `DEBUG` chatter is not. Because the root level is also `INFO`, this line changes nothing today; it keeps the pool quiet even if someone lowers the root level to `DEBUG` to see the holds.
- `<root level="INFO">` configures the **root logger**, the ancestor of every logger. Its level is `INFO`: lines at `INFO`, `WARN` and `ERROR` are kept, and `DEBUG` and `TRACE` are dropped.
- `<appender-ref ref="console"/>` connects the root logger to the appender named `console`. Without this line, nothing would be written anywhere.
- `</configuration>` ends the file.

### The loggers and the MDC in `SeatWaveServer`

```java
  private static final Logger LOG = LoggerFactory.getLogger(SeatWaveServer.class);
```

`private static final` means one logger per class, made once. Passing the class gives the logger the class's full name, which `%logger{0}` shortens. The imports above it are `org.slf4j.Logger`, `org.slf4j.LoggerFactory` and `org.slf4j.MDC`.

```java
    LOG.info("{} is {}", instanceName, lifecycle.state());
```

When the instance is marked ready, `{}` is filled with the instance name and the lifecycle state, so the line reads `seatwave-1 is READY` (the state's enum name, upper case). The first argument is the name given on the command line.

```java
    LOG.info("{} is draining with {} in flight", instanceName, lifecycle.inFlight());
```

At shutdown, the first thing `drainAndStop` says is how many requests are still in flight, for example `seatwave-1 is draining with 0 in flight`.

```java
        MDC.put("correlationId", correlationId);
```

Inside the request handler, after the lifecycle has admitted the request, the id goes into the MDC under the key the pattern asks for, `correlationId`. The id is made at the top of the handler: `UUID.randomUUID().toString().replace("-", "")`, a random value of 32 hexadecimal characters. It is the instance's own; no header from the client or the balancer is believed, so a client cannot choose what appears in the log.

```java
        MDC.clear();
```

This is in the `finally` block of the handler, next to `admitted.close()`, so it runs after success, after an error and after a client that hung up. It is the guard against the stale id described above.

### The loggers in `HoldRoutes`

```java
  private static final Logger LOG = LoggerFactory.getLogger(HoldRoutes.class);
```

The same one-logger-per-class line.

```java
        LOG.debug(
            "held {} until {}{}",
            granted.hold().seat(),
            granted.hold().expiresAt(),
            granted.isReplay() ? " (replayed for a retried request)" : "");
```

One line per granted hold, at `DEBUG`: the seat, the moment it expires, and the words `(replayed for a retried request)` when the hold was an answer to a retried request with the same `Idempotency-Key`. A line like this is called a **business event**: it records something the business cares about, not a technical detail. At `INFO` it is not written, which is why the real logs below never show it.

```java
    LOG.warn("the shared database is unavailable: {}", failure.getMessage());
```

A `WARN` line is kept. When the shared database does not answer, the route logs why and then replies `503` with `Retry-After`. The correlation id is on the line, because it is written inside the request.

### What it looks like

The instance itself runs only inside the Compose stack, and I did not run Docker. The tests start real servers with the same `logback.xml`, though, and Gradle keeps what a test class wrote to standard error in `build/test-results/test/`. These are lines from `SeatWaveServerTest` and `SeatWaveCliTest` as I read them in the XML reports (the times differ on every run):

```
07:36:57.537 INFO  [none] HikariDataSource - seatwave-1 - Starting...
07:36:57.556 INFO  [none] HikariDataSource - seatwave-1 - Start completed.
07:36:57.559 INFO  [none] SeatWaveServer - seatwave-1 is READY
07:36:57.560 INFO  [none] SeatWaveServer - seatwave-1 is draining with 0 in flight
07:36:57.561 INFO  [none] HikariDataSource - seatwave-1 - Shutdown initiated...
07:36:57.562 INFO  [none] HikariDataSource - seatwave-1 - Shutdown completed.
07:37:02.910 WARN  [2f5dc39f615244ddbd7b5dc859ad3e44] HoldRoutes - the shared database is unavailable: interrupted
```

Reading the last line from the left: the time, `WARN ` padded, the correlation id in brackets, the short logger name, the separator and the message. The other lines were written outside any request, so the brackets say `none`. The `HikariDataSource` lines are HikariCP's own, passing through the same pattern.

## Try it yourself

All commands ran in a scratch copy on 11 November 2026.

1. Check that nothing but two classes uses the logging API:

```
grep -rln "LoggerFactory\|MDC" src
```

   Expected: `src/main/java/io/plainround/seatwave/balancer/seatwave/SeatWaveServer.java` and `src/main/java/io/plainround/seatwave/balancer/seatwave/HoldRoutes.java`.

2. See the engine registration inside the Logback jar:

```
unzip -p "$(find ~/.gradle -name 'logback-classic-1.6.6.jar' | head -1)" META-INF/services/org.slf4j.spi.SLF4JServiceProvider
```

   Expected: `ch.qos.logback.classic.spi.LogbackServiceProvider`.

3. Watch the pattern work with a tiny program. Save this as `L.java` in an empty folder:

```
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class L {
  private static final Logger LOG = LoggerFactory.getLogger(L.class);

  public static void main(String[] args) {
    LOG.debug("a debug line");
    LOG.info("no id yet, {} seats", 3);
    MDC.put("correlationId", "abc123");
    LOG.info("held {} for {} s", "A-1", 300);
    LOG.warn("a warning");
    MDC.clear();
    LOG.info("after clear");
  }
}
```

   Copy `src/main/resources/logback.xml` into a folder named `cfg` beside it, then run it with the folder first on the classpath and the three jars from Gradle's cache after it:

```
CP=$(find ~/.gradle -name 'slf4j-api-2.0.20.jar'|head -1):$(find ~/.gradle -name 'logback-classic-1.6.6.jar'|head -1):$(find ~/.gradle -name 'logback-core-1.6.6.jar'|head -1)
java -cp "cfg:$CP" L.java 2>err.txt; cat err.txt
```

   Expected: nothing on standard output, and in `err.txt` four lines such as `07:37:19.047 INFO  [none] L - no id yet, 3 seats`, `07:37:19.049 INFO  [abc123] L - held A-1 for 300 s`, `07:37:19.049 WARN  [abc123] L - a warning` and `07:37:19.049 INFO  [none] L - after clear`. The times differ on every run. There is no `DEBUG` line, because the root level is `INFO`.

4. Run the same program without the configuration file:

```
java -cp "$CP" L.java 2>/dev/null
```

   Expected: five lines on standard output in Logback's default layout, such as `07:37:19.344 [main] DEBUG L -- a debug line`, now including the `DEBUG` line and the thread name, and without the correlation id.

5. Read the log lines of a real test run. Run the tests of the command-line class, then print the standard-error section of its report:

```
./gradlew --offline test --tests '*SeatWaveCliTest'
grep -A8 '<system-err>' build/test-results/test/TEST-io.plainround.seatwave.balancer.seatwave.SeatWaveCliTest.xml
```

   Expected: lines like `seatwave-1 - Starting...`, `seatwave-1 is READY`, `seatwave-1 is draining with 0 in flight` and `seatwave-1 - Shutdown completed.`

## Mistakes people make

- **Logging with `System.out.println`.** It has no level, no time and no switch to turn it off, and it mixes with a tool's real output. Here the log goes to standard error through a logger.
- **Building the message with `+`.** `"held " + seat` makes the text even when the line is dropped. The `{}` form makes it only when the line is kept.
- **Forgetting to clear the MDC.** On a reused thread the next request would show the previous id. The `finally` block with `MDC.clear()` prevents it.
- **Trusting a client's request id.** This code makes its own, so a client cannot write what it likes into the log (a log-forging attack).
- **Logging a secret.** The lines here carry an instance name, a state, a seat and an id, never the database password or a customer name.
- **Putting the engine on the compile classpath.** Logback is `runtimeOnly`, so the code cannot depend on an engine class by accident.
- **Expecting the hold lines.** The root level is `INFO`, so the `DEBUG` line for each hold is silent unless you change `logback.xml`.

## Check yourself

1. *What is the difference between SLF4J and Logback?* SLF4J is the front door the code calls; Logback is the engine that decides what is kept, how it looks and where it goes.
2. *Why does the pattern print `[none]` for some lines?* `%X{correlationId:-none}` prints the MDC value, and `none` when the thread has no `correlationId`, as for the lifecycle lines.
3. *Why does the appender write to `System.err`?* So a tool's result on standard output stays clean.
4. *Why does the handler call `MDC.clear()` in a `finally` block?* The MDC belongs to the thread, threads are reused, and the id must not leak into the next request, even after an error.
5. *Which lines does the root level `INFO` drop?* `DEBUG` and `TRACE`.

## Further reading

- SLF4J 2.0 manual: <https://www.slf4j.org/manual.html>
- Logback 1.6 manual, configuration: <https://logback.qos.ch/manual/configuration.html>
- Logback layouts (the pattern items): <https://logback.qos.ch/manual/layouts.html>
- Logback MDC: <https://logback.qos.ch/manual/mdc.html>
