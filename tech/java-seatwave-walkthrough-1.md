# The `seatwave` package, part 1: settings, small types and the lifecycle (line by line)

This is part 1 of 3. This part reads `ServiceSettings`, `Hold`, `GrantedHold`, `HoldRefusal`, `HoldCommand`, `HoldStore`, `StoreUnavailableException`, `LifecycleState` and `Lifecycle`. Part 2 (`java-seatwave-walkthrough-2.md`) reads `Reply`, `Request`, `Problems`, `SeatWaveServer` and `HoldRoutes`. Part 3 (`java-seatwave-walkthrough-3.md`) reads `JdbcHoldStore`, `DatabasePool`, `ServiceMetrics` and `SeatWaveCli`. The notes were split because the package is about 1,900 lines and each file quotes every line.

## What it is

The `seatwave` package is one SeatWave instance: the program that answers `POST /v1/holds` ("keep this seat for me") and `GET /v1/holds/{holdId}` ("show me my hold"). Module 10 starts three copies of it. They sit behind two HAProxy balancers (see `haproxy.md`) and share one PostgreSQL database (see `postgresql-and-h2.md`).

The nine files in this part:

- `ServiceSettings.java`: everything one instance needs to start, read from a settings file. It has no defaults.
- `Hold.java`: one held seat, as the database recorded it.
- `GrantedHold.java`: a hold the store granted, plus a flag that says whether it was a replay of an older request.
- `HoldRefusal.java`: the three reasons a hold can be refused. These are normal business answers, not errors.
- `HoldCommand.java`: a request to hold one seat, after every field has been checked.
- `HoldStore.java`: the interface (the list of promised methods) of the place where holds live.
- `StoreUnavailableException.java`: the error for "the database cannot be used".
- `LifecycleState.java`: the four states an instance moves through.
- `Lifecycle.java`: the small machine that decides whether a request may come in, and counts the requests that are running.

Terms you need.

- A **package** is a named folder of Java files. `io.plainround.seatwave.balancer.seatwave` is the package of every file here; the first line of each file says so.
- A **class** is a blueprint for objects. A **record** is a short way to write a class that only carries data: you list the fields in brackets and Java writes the constructor, the getters (they have the field's name, so `hold.seat()`), `equals`, `hashCode` and `toString` for you. A record cannot be changed after it is built.
- A **compact constructor** is a record's check-on-the-way-in: it has the record's name and no brackets, and it runs before the fields are stored. This code uses it to refuse a missing value.
- An **interface** lists methods without bodies. Any class that says `implements` it must supply them. A **sealed interface** names, in one place, every type that may implement it, so the compiler knows the list is complete.
- An **enum** is a type with a fixed list of values.
- **`Optional<T>`** is a box that holds one value or nothing. It replaces returning `null` for "no answer".
- **`Result<T, E>`** (the result4j library, see `result4j.md`) is a box that holds either a success value or an error value. This code returns it when a failure is an expected outcome, such as "the seat is taken".
- A **checked exception** is an error type that a method must announce with `throws`, and that every caller must handle or pass on.
- A **lock** lets one thread at a time run a piece of code. A **thread** is one line of execution inside the program. A server runs many threads, one per request being worked on.
- **Javadoc** is the `/** ... */` comment before a class or method. Tags such as `@param` and `@return` describe the arguments and the result.

The full tour of the language is in `java.md`. Here every feature is explained where it first appears.

## Why this repository uses it

Module 10's promise is that any of three identical instances can answer for any request. That is only true if an instance keeps nothing in its own memory that another instance would need. So the package is built around three ideas.

- **No state in memory.** Every hold lives in the database. `Hold` and `GrantedHold` are plain data that the store hands back. `HoldStore` is the doorway to the database, and the tests can swap it for a fake.
- **A shutdown that loses nothing.** A balancer sends a request to an instance only while that instance says it is ready. During a rolling deploy the instance must stop taking new requests, finish the ones it has, and only then exit. `Lifecycle` is the one place that enforces this, under one lock.
- **No hidden defaults.** `ServiceSettings` refuses a settings file that lacks a key, and lists every problem at once, so a reader never has to guess which number the program chose.

## Where it appears

| File | What it does |
|---|---|
| `seatwave/ServiceSettings.java` | Reads and checks the settings of one instance. Used by `SeatWaveCli` and, as a plain record, by the tests (`TestSettings`). |
| `seatwave/Hold.java` | The hold as the store returns it. Used by `JdbcHoldStore`, `HoldRoutes` and the tests. |
| `seatwave/GrantedHold.java` | A granted hold with the replay flag. Returned inside a `Result` by `HoldStore.hold`. |
| `seatwave/HoldRefusal.java` | `SeatTaken`, `NoSuchSeat`, `KeyReused`. The error side of that `Result`. |
| `seatwave/HoldCommand.java` | The checked request a store receives. Built by `HoldRoutes.checked`. |
| `seatwave/HoldStore.java` | The interface. Two implementations: `JdbcHoldStore` (real) and `ScriptedHoldStore` (tests). |
| `seatwave/StoreUnavailableException.java` | Thrown by a store when the database fails; `HoldRoutes` turns it into `503`. |
| `seatwave/LifecycleState.java` | The four states. Read by `Lifecycle`, `SeatWaveServer`, `HoldRoutes` and `ServiceMetrics`. |
| `seatwave/Lifecycle.java` | The gate and the counter. Used by `SeatWaveServer` and `ServiceMetrics`. |

## How it works underneath

**The life of an instance.** An instance starts in `STARTING`: its two ports are open, but every request is refused. After the database pool exists, `SeatWaveCli` calls `markReady`, and the state becomes `READY`. When the process receives the stop signal (SIGTERM, which `docker stop` sends), the state becomes `DRAINING`: no new request is admitted, the requests already running finish. When the count of running requests reaches zero, or the deadline passes, the state becomes `STOPPED` and the ports close. A stop signal that arrives while the instance is still starting skips `READY`: `STARTING` goes straight to `DRAINING`.

**The gate.** Every request must ask `tryAdmit` first. If the state is `READY`, `tryAdmit` adds one to the in-flight counter and returns an `Admission`, a ticket. When the response has been written, the request closes the ticket and the counter goes down by one. If the state is anything else, `tryAdmit` returns an empty `Optional` and the caller must refuse the request without doing any of its work.

**Why one lock.** "Is the state READY?" and "add one to the counter" must be a single step. Suppose they were two steps. A request could see `READY`, then the drain could start and see a counter of zero, then the request could add one. The drain would finish, the instance would stop, and that request would be cut off. With one lock around both steps, that order cannot happen: either the request is counted before the drain starts (and the drain waits for it), or it is refused.

**Settings.** The settings file is a list of `key=value` lines (see `java-properties.md`). `SettingsReader` (see `java-config-json-walkthrough.md`) reads one key at a time and keeps a list of problems. `ServiceSettings.from` asks it for thirteen keys, checks two rules that involve more than one key, and returns either the finished record or the whole list of problems.

**Holds and the database.** A `HoldCommand` goes into `HoldStore.hold`. The store answers with a `Result`: success with a `GrantedHold`, or an error with one of the three `HoldRefusal` types. If the database itself fails, the store throws `StoreUnavailableException`, because that is not a business answer, it is a broken part. The route turns it into a `503` so the balancer can try another instance (see `haproxy.md`).

## Walkthrough of this repository's use

### `seatwave/ServiceSettings.java`

The file starts with the package line and the imports. An **import** says "I will use this class from another package by its short name".

```java
package io.plainround.seatwave.balancer.seatwave;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.balancer.config.SettingsReader;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
```

- `Result` is the success-or-error box from result4j.
- `SettingsReader` is the file reader from the `config` package.
- `InetAddress` is Java's type for a network address such as `127.0.0.1`.
- `Path` is a file location; `Duration` is a length of time.
- `Pattern` is a regular expression: a short description of what text is allowed (for example "seven to fifteen digits and dots").

The Javadoc comment explains each field of the record. It is the specification, so the walkthrough reads it in full.

```java
/**
 * Everything one SeatWave instance needs, read from a settings file with no defaults.
 *
 * @param bindAddress the address both listeners bind to: 0.0.0.0 inside a container, 127.0.0.1 on a
 *     reader's own machine
 * @param port the request listener, the one the balancer sends requests to
 * @param adminPort the operations listener for {@code /health}, {@code /ready} and {@code
 *     /metrics}, on its own thread, so a busy instance still answers its checks
 * @param backlog connections the kernel may hold for each listener before SeatWave accepts them
 * @param workerThreads the platform threads that run requests, Module 4's 64
 * @param queueCapacity requests that may wait for a worker; one more is refused
 * @param database where the shared database is and how many connections to keep
 * @param drainDeadline the longest a shutdown waits for in-flight requests
 */
```

- `bindAddress` is the network address both ports listen on. `0.0.0.0` means "every address this machine has", which is right inside a container. `127.0.0.1` means "only this machine", which is right on your own laptop.
- `port` is the request port. `adminPort` is a second port for `/health`, `/ready` and `/metrics`, served by its own thread. The comment gives the reason: a busy instance must still answer its checks, or the balancer would think a busy instance is a dead one.
- `backlog` is how many connections the operating system may keep waiting, for each port, before the program takes them with `accept`.
- `workerThreads` and `queueCapacity` size the pool of threads that run requests (part 2 builds it). 64 is the number Module 4 measured.
- `database` and `drainDeadline` are the database settings and the longest a shutdown waits.

```java
public record ServiceSettings(
    InetAddress bindAddress,
    int port,
    int adminPort,
    int backlog,
    int workerThreads,
    int queueCapacity,
    DatabaseSettings database,
    Duration drainDeadline) {
```

`public record ServiceSettings(...)` declares the record. Its eight components become eight private fields, eight accessor methods and a constructor. `InetAddress`, `Duration` and `DatabaseSettings` are real types, not text: the settings are parsed once, here, and the rest of the program never sees a string that might be wrong.

```java
  // An IP address literal; a host name would need DNS before the server could start.
  private static final Pattern ADDRESS = Pattern.compile("[0-9.]{7,15}|[0-9a-fA-F:]{2,39}");
  // Only the two databases this project runs: PostgreSQL in the stack, H2 in a reader's test.
  private static final Pattern JDBC_URL =
      Pattern.compile("jdbc:(postgresql://[A-Za-z0-9.-]{1,100}:[0-9]{1,5}/[a-z_]{1,32}|h2:mem:.*)");
  private static final Pattern USER = Pattern.compile("[a-z_][a-z0-9_]{0,31}");
  // Any path a file system accepts, spaces and long folders included, but no control character
  // (a newline would let a value end one line of a log and start another).
  private static final Pattern FILE = Pattern.compile("[^\\p{Cntrl}]{1,4096}");
  private static final int HIGHEST_PORT = 65_535;
  private static final int MAX_WORKERS = 1_024;
  private static final int MAX_BACKLOG = 65_535;
  private static final int MAX_QUEUE = 100_000;
  // PostgreSQL's default max_connections is 100; one instance never needs more than that.
  private static final int MAX_POOL = 100;
  private static final int MAX_WAIT_MILLIS = 600_000;
  // JDBC's isValid counts whole seconds, so the readiness check cannot wait less than one.
  private static final int MIN_CHECK_MILLIS = 1_000;
```

These lines are the rules, written once as constants. `private static final` means: belongs to the class, not to one object, and never changes.

- `ADDRESS` allows either `[0-9.]{7,15}` (a dotted IPv4 address: seven to fifteen digits and dots) or `[0-9a-fA-F:]{2,39}` (an IPv6 address: hexadecimal digits and colons). The pattern only filters the characters. `literal` below checks that the text really is an address. The comment says why host names are refused: a name needs the DNS service before the server could start.
- `JDBC_URL` allows only the two databases this project runs. `jdbc:postgresql://host:port/name` is the stack. `jdbc:h2:mem:...` is the in-memory database that tests use. A JDBC URL (see `postgresql-and-h2.md`) is the text a Java program uses to find a database. Any other URL is a problem. The `|` in the pattern means "or". `.*` in the H2 branch means any characters, so a test may name its database.
- `USER` is a database user name: a lowercase letter or an underscore first, then up to 31 more lowercase letters, digits or underscores. This is PostgreSQL's own habit for plain names.
- `FILE` is a path as a file system can hold it: any character except a control character, from 1 to 4,096 characters. `\p{Cntrl}` is the class of control characters (a tab, a line break, the NUL byte). The comment gives the reason: a line break in a value could end one line of a log and start another, so someone could forge a log line. Spaces and long folders are allowed because a reader's copy of the project may sit in any folder. `ServiceSettingsTest.acceptsAPasswordFileInALongFolderWithSpaces` proves it.
- The `HIGHEST_PORT` to `MAX_WAIT_MILLIS` constants are the upper limits. 65,535 is the highest TCP port. `MAX_POOL` is 100 because PostgreSQL's default `max_connections` is 100, and the comment says one instance never needs more. The underscore in `65_535` is only for reading: Java ignores it.
- `MIN_CHECK_MILLIS` is 1,000. The comment gives the reason: JDBC's `isValid` counts whole seconds, so a readiness check cannot wait less than one second.

One rough edge for a careful reader. Part 3 shows that the connection pool library refuses a connection timeout below 250 ms, while `db.connectTimeoutMillis` is allowed from 1 (line 89 below). The rule here is looser than the library's. Section "Mistakes people make" returns to it.

```java
  /**
   * Creates the settings.
   *
   * @throws IllegalArgumentException if the two listeners share a port
   */
  public ServiceSettings {
    Objects.requireNonNull(bindAddress, "bindAddress");
    Objects.requireNonNull(database, "database");
    Objects.requireNonNull(drainDeadline, "drainDeadline");
    if (port != 0 && port == adminPort) {
      throw new IllegalArgumentException("the two listeners need two different ports");
    }
  }
```

The **compact constructor** runs whenever a `ServiceSettings` is built, by `from` or directly by a test.

- `Objects.requireNonNull(x, "name")` throws a `NullPointerException` that names the argument if `x` is null. It turns a vague crash later into a clear one now.
- `port != 0 && port == adminPort` refuses one port for both listeners. Port 0 is special: it asks the operating system for any free port, so two zeros are fine, they become two different ports.
- The `@throws` line in the Javadoc promises this error. `ServiceSettingsTest.cannotBeBuiltWithOnePortForBothListeners` checks it.

```java
  /**
   * Reads the settings, reporting every missing or invalid key and every broken rule between keys
   * together.
   *
   * @param reader the settings reader
   * @return the settings, or one line per problem
   */
  public static Result<ServiceSettings, List<String>> from(SettingsReader reader) {
    Objects.requireNonNull(reader, "reader");
    String bind = reader.textMatching("http.bindAddress", ADDRESS);
    int port = reader.intBetween("http.port", 0, HIGHEST_PORT);
    int adminPort = reader.intBetween("http.adminPort", 0, HIGHEST_PORT);
    int backlog = reader.intBetween("http.backlog", 1, MAX_BACKLOG);
    int workers = reader.intBetween("http.workerThreads", 1, MAX_WORKERS);
    int queue = reader.intBetween("http.queueCapacity", 1, MAX_QUEUE);
    String url = reader.textMatching("db.url", JDBC_URL);
    String user = reader.textMatching("db.user", USER);
    String passwordFile = reader.textMatching("db.passwordFile", FILE);
    int poolSize = reader.intBetween("db.poolSize", 1, MAX_POOL);
    Duration connect = reader.millisBetween("db.connectTimeoutMillis", 1, MAX_WAIT_MILLIS);
    Duration check =
        reader.millisBetween("readiness.checkTimeoutMillis", MIN_CHECK_MILLIS, MAX_WAIT_MILLIS);
    Duration drain = reader.millisBetween("drain.deadlineMillis", 0, MAX_WAIT_MILLIS);
```

`from` is a **static factory**: a method on the class that builds an object, so the caller does not call `new`. Its return type is `Result<ServiceSettings, List<String>>`: success carries the settings, error carries the list of problem sentences.

Each line reads one key. The reader records a problem and returns a stand-in when a key is bad, so the next line can still run. That is how one run reports every mistake.

- `http.bindAddress`: text matching `ADDRESS`.
- `http.port`, `http.adminPort`: whole numbers from 0 to 65,535.
- `http.backlog`: from 1 to 65,535. `http.workerThreads`: 1 to 1,024. `http.queueCapacity`: 1 to 100,000.
- `db.url`, `db.user`, `db.passwordFile`: text matching `JDBC_URL`, `USER` and `FILE`. The file holds the password. The settings file never holds the password itself, so a settings file can be shown or committed.
- `db.poolSize`: 1 to 100.
- `db.connectTimeoutMillis`: a `Duration` of 1 to 600,000 milliseconds. This is how long a request waits to get a connection from the pool.
- `readiness.checkTimeoutMillis`: from `MIN_CHECK_MILLIS` to 600,000.
- `drain.deadlineMillis`: 0 to 600,000. Zero means "do not wait".

```java
    if (port != 0 && port == adminPort) {
      reader.addProblem("http.port and http.adminPort must differ (0 means any free port)");
    }
    if (poolSize > workers) {
      reader.addProblem(
          "db.poolSize must not be larger than http.workerThreads: a connection no worker can use"
              + " is held open at the database for nothing");
    }
```

Two rules that involve two keys each. A single key cannot know about the other, so the code checks after both are read.

- The two ports must differ. This repeats the compact constructor's rule on purpose: the constructor throws, which is right for a bug, while this adds a problem sentence, which is right for a typo in a file. The words "0 means any free port" explain the exception.
- `poolSize > workers` is refused. A pool bigger than the number of threads that use it would hold database connections that nothing can use. Each open connection costs the database memory, so the sentence says "held open at the database for nothing". The sentence is built from two string pieces joined with `+`, which is only a way to keep the line short.

```java
    Optional<InetAddress> address = bind.isEmpty() ? Optional.empty() : literal(reader, bind);
    if (!reader.problems().isEmpty() || address.isEmpty()) {
      return Result.error(reader.problems());
    }
    return Result.success(
        new ServiceSettings(
            address.get(),
            port,
            adminPort,
            backlog,
            workers,
            queue,
            new DatabaseSettings(url, user, Path.of(passwordFile), poolSize, connect, check),
            drain));
  }
```

- `bind.isEmpty() ? Optional.empty() : literal(reader, bind)` is the **conditional operator**: if the condition is true, take the first value, else the second. When the bind address is missing, an earlier line already recorded "is missing", so no second problem is added.
- `reader.problems()` returns a copy of the list. If it has anything, or the address is empty, the method returns `Result.error(...)`.
- Otherwise it builds the `DatabaseSettings` (turning the password file text into a `Path`) and the `ServiceSettings`, and returns `Result.success(...)`. `address.get()` is safe here: the check above already ruled out an empty `Optional`.

```java
  private static Optional<InetAddress> literal(SettingsReader reader, String text) {
    try {
      return Optional.of(InetAddress.ofLiteral(text));
    } catch (IllegalArgumentException notAnAddress) {
      reader.addProblem("http.bindAddress must be an IP address, but was " + text);
      return Optional.empty();
    }
  }
```

`literal` turns the address text into an `InetAddress`. `InetAddress.ofLiteral` (added in Java 22) accepts only an address written out, never a name, so it never calls DNS. If the text is not an address, for example `999.1.1.1`, it throws `IllegalArgumentException`. The code catches that and records a problem, because a typo in a file is expected. `ServiceSettingsTest.refusesAHostNameAsTheBindAddress` pins the exact sentence.

```java
  /**
   * Where the shared database is.
   *
   * @param url the JDBC URL
   * @param user the database user
   * @param passwordFile the file that holds the password, generated at run time and never committed
   * @param poolSize connections kept open to the database
   * @param connectTimeout the longest a request waits for a connection from the pool
   * @param checkTimeout the longest the readiness check waits for the database
   */
  public record DatabaseSettings(
      String url,
      String user,
      Path passwordFile,
      int poolSize,
      Duration connectTimeout,
      Duration checkTimeout) {

    /** Checks that no field is missing. */
    public DatabaseSettings {
      Objects.requireNonNull(url, "url");
      Objects.requireNonNull(user, "user");
      Objects.requireNonNull(passwordFile, "passwordFile");
      Objects.requireNonNull(connectTimeout, "connectTimeout");
      Objects.requireNonNull(checkTimeout, "checkTimeout");
    }
  }
}
```

`DatabaseSettings` is a record **nested** inside `ServiceSettings`; code outside writes `ServiceSettings.DatabaseSettings`. Its six components are the JDBC URL, the user, the password file's path, the pool size, how long a request waits for a connection, and how long the readiness check waits. The compact constructor refuses a missing value in the five object fields. (`poolSize` is an `int`, which cannot be null.)

### `seatwave/Hold.java`

```java
package io.plainround.seatwave.balancer.seatwave;

import java.time.Instant;
import java.util.Objects;

/**
 * One seat held by one customer until a moment, as the shared database recorded it.
 *
 * @param holdId the hold's identifier, a random UUID
 * @param eventId the event, Module 1's event 1
 * @param seat the seat label, for example {@code A-7}
 * @param customer the customer's identifier as the client sent it
 * @param expiresAt when the hold ends, Module 1's 300 seconds after it began
 */
public record Hold(String holdId, long eventId, String seat, String customer, Instant expiresAt) {

  /** Checks that no field is missing. */
  public Hold {
    Objects.requireNonNull(holdId, "holdId");
    Objects.requireNonNull(seat, "seat");
    Objects.requireNonNull(customer, "customer");
    Objects.requireNonNull(expiresAt, "expiresAt");
  }
```

A hold is one seat kept for one customer until a moment.

- `holdId` is a random UUID (a 128-bit identifier written as 36 characters, such as `580ec039-fe3e-4ae2-a95c-6dd0c0cab0e4`). `eventId` is a `long` (a 64-bit whole number), and Module 1's only event is 1. `seat` is a label like `A-7`. `customer` is the client's own identifier. `expiresAt` is an `Instant`, one point on the time line with no time zone, so it is the same everywhere.
- The compact constructor refuses a null in the four object fields. `eventId` is a `long`, so it cannot be null.

```java
  /**
   * Says whether the hold still blocks its seat.
   *
   * @param now the current time
   * @return true until the expiry moment
   */
  public boolean isActiveAt(Instant now) {
    return now.isBefore(expiresAt);
  }
}
```

`isActiveAt(now)` answers "does this hold still keep its seat?". `now.isBefore(expiresAt)` is true up to, but not at, the expiry moment. At exactly the expiry the hold is over. The database rule in `JdbcHoldStore` is `hold_expires_at <= ?` (an expired hold may be replaced), and the two agree: "active" is the opposite of "may be replaced". `JdbcHoldStoreTest.grantsTheSeatAgainOnceTheHoldHasExpired` moves the clock by exactly 300 seconds to test that edge.

### `seatwave/GrantedHold.java`

```java
package io.plainround.seatwave.balancer.seatwave;

import java.util.Objects;

/**
 * A hold the store granted.
 *
 * @param hold the hold
 * @param isReplay true when this request's Idempotency-Key had already made the hold, possibly on
 *     another instance, so nothing new was written
 */
public record GrantedHold(Hold hold, boolean isReplay) {

  /** Checks the hold. */
  public GrantedHold {
    Objects.requireNonNull(hold, "hold");
  }
}
```

`GrantedHold` pairs a `Hold` with `isReplay`. When a client retries a `POST` with the same `Idempotency-Key` (a header that says "this is the same attempt as before"), the store finds the hold that the first attempt made and returns it with `isReplay` true. The route logs the difference and answers the same `201` either way. The balancer may retry a request when an instance dies before it answers, so this is the part that stops one click from holding two seats.

### `seatwave/HoldRefusal.java`

```java
package io.plainround.seatwave.balancer.seatwave;

/** Why a hold was not granted: the expected business failures of {@code POST /v1/holds}. */
public sealed interface HoldRefusal {

  /** Another active hold or a booking owns the seat. */
  record SeatTaken() implements HoldRefusal {}

  /** The event or the seat does not exist in the database. */
  record NoSuchSeat() implements HoldRefusal {}

  /** The Idempotency-Key was already used with a different request (Module 7). */
  record KeyReused() implements HoldRefusal {}
}
```

A `sealed interface` lists, in the same file, the only types allowed to implement it. Here there are three nested records with no fields:

- `SeatTaken`: another active hold or a booking owns the seat. The route answers `409`.
- `NoSuchSeat`: the event or the seat is not in the database. The route answers `404`.
- `KeyReused`: the same `Idempotency-Key` came with a different request. The route answers `422`.

Because the list is closed, a `switch` over a `HoldRefusal` in `HoldRoutes.refused` needs no `default` branch, and the compiler will refuse to build the project if a fourth type is added and a `switch` forgets it.

### `seatwave/HoldCommand.java`

```java
package io.plainround.seatwave.balancer.seatwave;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A checked request to hold one seat: every field has passed the contract's rules, so a store never
 * sees an invalid value.
 *
 * @param idempotencyKey Module 7's {@code Idempotency-Key}, an RFC 9651 string with its quotes
 * @param fingerprint the SHA-256 of the request's meaning, so a key reused for another seat is
 *     caught
 * @param eventId the event, 1 or more
 * @param seat the seat label: a section letter A to T, a hyphen and a number 1 to 1000
 * @param customer the customer: 1 to 64 letters, digits, {@code -} or {@code _}
 */
public record HoldCommand(
    String idempotencyKey, String fingerprint, long eventId, String seat, String customer) {
```

A `HoldCommand` is the request after the route has validated it. Its Javadoc states every rule.

- `idempotencyKey`: Module 7's header value. It is an RFC 9651 "String": a quoted text of printable characters, so the quotes are part of the value. That is why the tests write `"\"k-1\""`.
- `fingerprint`: the SHA-256 hash of the request's meaning (event, seat and customer). If the same key arrives with a different fingerprint, the store knows the client reused a key for another request.
- `eventId`, `seat`, `customer`: the request itself.

```java
  // Module 7: an Idempotency-Key is an RFC 9651 String, a quoted value of printable characters.
  static final Pattern IDEMPOTENCY_KEY = Pattern.compile("\"[\\x21\\x23-\\x5B\\x5D-\\x7E]{1,64}\"");
  // Module 1's seat labels: A-1 to T-1000.
  static final Pattern SEAT = Pattern.compile("[A-T]-([1-9][0-9]{0,2}|1000)");
  static final Pattern CUSTOMER = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
```

Four patterns, written as constants.

- `IDEMPOTENCY_KEY`: `"` then 1 to 64 characters from the printable ASCII ranges, then `"`. The ranges `\x21`, `\x23-\x5B` and `\x5D-\x7E` are written in hexadecimal. Together they allow `!` and everything from `#` to `~` except the double quote (hex 22) and the backslash (hex 5C). So the key cannot contain a quote or a backslash, and cannot contain a space either (hex 20).
- `SEAT`: a letter A to T, a hyphen, and a number from 1 to 1000. The pattern is `[1-9][0-9]{0,2}` (one to three digits, no leading zero) or the text `1000`.
- `CUSTOMER`: 1 to 64 letters, digits, hyphens or underscores.
- `SHA256_HEX`: exactly 64 lowercase hexadecimal characters, which is what a SHA-256 hash looks like as text.

The first three are not `private`: they have the default, package-wide access, so `HoldRoutes` (same package) can run the same patterns on the raw request. One rule in one place, used twice.

```java
  /**
   * Checks every field.
   *
   * @throws IllegalArgumentException if a field breaks its rule; the routes check first, so this is
   *     a programming error
   */
  public HoldCommand {
    require(IDEMPOTENCY_KEY, idempotencyKey, "idempotencyKey");
    require(SHA256_HEX, fingerprint, "fingerprint");
    require(SEAT, seat, "seat");
    require(CUSTOMER, customer, "customer");
    if (eventId < 1) {
      throw new IllegalArgumentException("eventId must be 1 or more, but was " + eventId);
    }
  }
```

The compact constructor checks all four text fields with `require`, and the event number by hand. A command that exists is always valid. The Javadoc says that the routes check first, so a throw here means a programming mistake, not bad input from a client.

```java
  private static void require(Pattern rule, String value, String name) {
    Objects.requireNonNull(value, name);
    if (!rule.matcher(value).matches()) {
      throw new IllegalArgumentException(name + " must match " + rule.pattern());
    }
  }
```

`require` is a private helper. `rule.matcher(value).matches()` is true only if the **whole** value fits the pattern. `matches()` differs from `find()`, which would accept a pattern found anywhere inside. The message uses `rule.pattern()`, the pattern's source text, so the error names the rule that broke.

### `seatwave/HoldStore.java`

```java
package io.plainround.seatwave.balancer.seatwave;

import com.github.sviperll.result4j.Result;
import java.util.Optional;

/**
 * Where holds live: outside every instance, so any instance can answer for any hold. The JDBC store
 * is the real one; the tests also use a store that can hold a request open on purpose, to test
 * draining. Implementations are thread-safe.
 */
public interface HoldStore {
```

`HoldStore` is the interface for "where holds live". The comment gives the reason it is an interface: the real store is `JdbcHoldStore`, and the tests also use a store that can keep a request open on purpose, so draining can be tested. All implementations must be **thread-safe**: many request threads call them at once.

```java
  /**
   * Holds a seat, or replays the hold this request's Idempotency-Key already made.
   *
   * @param command the checked request
   * @return the granted hold, or why it was refused
   * @throws StoreUnavailableException if the database cannot be used
   */
  Result<GrantedHold, HoldRefusal> hold(HoldCommand command) throws StoreUnavailableException;
```

`hold` takes a `HoldCommand`. It returns a `Result` of a `GrantedHold` or a `HoldRefusal`. It `throws StoreUnavailableException`: the caller must handle a broken database. Notice the split: expected outcomes are values in the `Result`, broken infrastructure is an exception.

```java
  /**
   * Finds a hold by its identifier.
   *
   * @param holdId the hold's UUID
   * @return the hold, or empty if no hold has this identifier
   * @throws StoreUnavailableException if the database cannot be used
   */
  Optional<Hold> find(String holdId) throws StoreUnavailableException;
```

`find` looks a hold up by its identifier. `Optional<Hold>` is empty when no hold has that id. The Javadoc here says the same as before about the exception.

```java
  /**
   * Says whether the database answers now, for the readiness check.
   *
   * @return true if a connection was obtained and answered within the store's timeout
   */
  boolean isReachable();
}
```

`isReachable` is for the readiness check: can the instance get a connection and does the database answer in time? It returns a plain `boolean` and throws nothing: "no" is a normal answer for a readiness check.

### `seatwave/StoreUnavailableException.java`

```java
package io.plainround.seatwave.balancer.seatwave;

/**
 * The shared database could not be used: an infrastructure failure, not a business answer. The
 * routes turn it into {@code 503} with {@code Retry-After}, never into a guess.
 */
public final class StoreUnavailableException extends Exception {

  private static final long serialVersionUID = 1L;

  /**
   * Wraps the cause.
   *
   * @param message what was being done
   * @param cause the database's own exception
   */
  public StoreUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
```

- `extends Exception` makes this a checked exception: the compiler forces every caller to deal with it.
- `final` means no one can extend it.
- `serialVersionUID` is a version number that Java asks for on exception classes that could be written to a stream. It is never used here, but the compiler warns without it, and this build turns warnings into errors.
- The constructor takes a message ("what was being done") and the cause (the database driver's own exception) and passes both to `Exception`. The cause is kept so a log line can show the real reason.

### `seatwave/LifecycleState.java`

```java
package io.plainround.seatwave.balancer.seatwave;

/**
 * Where one SeatWave instance is in its life, in the only order it can move: {@code STARTING} to
 * {@code READY} to {@code DRAINING} to {@code STOPPED}. A shutdown signal during start goes from
 * {@code STARTING} straight to {@code DRAINING}.
 */
public enum LifecycleState {
  /**
   * The listeners are open, but the instance has not said it can serve; every request is refused.
   */
  STARTING,
  /** The instance admits new requests and its readiness check can pass. */
  READY,
  /** Shutdown has begun: no new request is admitted, the ones in flight are finishing. */
  DRAINING,
  /** In-flight requests reached zero, or the drain deadline passed; the listeners are closing. */
  STOPPED
}
```

An `enum` with four values. Each value has a Javadoc comment in `/** ... */`. The comment of the whole type states the one allowed order: `STARTING`, `READY`, `DRAINING`, `STOPPED`, with the one shortcut `STARTING` to `DRAINING`. The order is enforced by `Lifecycle`, not by the enum itself.

### `seatwave/Lifecycle.java`

The imports first.

```java
package io.plainround.seatwave.balancer.seatwave;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
```

- `Duration` is a length of time; `Objects` has `requireNonNull`; `Optional` is the maybe-box.
- `TimeUnit` converts between units such as nanoseconds and milliseconds.
- `AtomicBoolean` is a boolean that can be changed safely from several threads, even without a lock.
- `ReentrantLock` is a lock; `Condition` is a waiting place attached to a lock: a thread can wait on it, and another thread can wake it.

```java
/**
 * The module's invariant in one place: an instance admits no new request after it starts draining,
 * and it stops only when its in-flight count reaches zero or a stated deadline passes.
 *
 * <p>The state and the in-flight count change under one lock, so "is it still ready?" and "count
 * this request" are a single step: no request can be counted after draining began, and the drain
 * cannot miss a request that was counted just before. The server's one request handler asks {@link
 * #tryAdmit} before it reads a byte of the request, and no other code path reaches the routes.
 *
 * <p>Thread-safe. The lock is held for a few instructions only, never across I/O.
 */
public final class Lifecycle {
```

The class comment states the invariant of the whole module in two sentences: no new request after draining starts, and stop only when nothing is in flight or a deadline passes. It says why one lock is needed (the reason in "How it works underneath" above) and that "the server's one request handler asks `tryAdmit` before it reads a byte of the request, and no other code path reaches the routes". The lock is held "for a few instructions only, never across I/O", so no thread waits long for it.

```java
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition idle = lock.newCondition();
  private LifecycleState state = LifecycleState.STARTING;
  private long inFlight;
```

- `lock` is the one lock. `ReentrantLock` is "reentrant": a thread that already holds it may take it again without blocking itself.
- `idle` is a `Condition` of that lock. A thread that waits for "no request left" waits here.
- `state` starts as `STARTING`. `inFlight` is a `long` counter of admitted requests. Both are only touched while the lock is held, so they need no `volatile` or atomic type.

```java
  /**
   * Admits one request if the instance is ready.
   *
   * @return an admission to close when the response has been written, or empty when the instance is
   *     starting, draining or stopped; the caller must then refuse the request without doing any of
   *     its work
   */
  public Optional<Admission> tryAdmit() {
    lock.lock();
    try {
      if (state != LifecycleState.READY) {
        return Optional.empty();
      }
      inFlight++;
      return Optional.of(new Admission(this));
    } finally {
      lock.unlock();
    }
  }
```

`tryAdmit` is the gate.

1. `lock.lock()` takes the lock. If another thread holds it, this thread waits.
2. `try { ... } finally { lock.unlock(); }` is the standard way to hold a lock. The `finally` block runs whatever happens inside, even if an exception is thrown, so the lock can never stay locked.
3. If the state is not `READY`, return `Optional.empty()`: the request is refused.
4. Otherwise `inFlight++` and return an `Admission`. `new Admission(this)` gives the ticket a link back to this lifecycle.

```java
  /**
   * Moves from starting to ready.
   *
   * @return true if the instance is now ready; false if shutdown began first, which wins
   */
  public boolean markReady() {
    lock.lock();
    try {
      if (state == LifecycleState.STARTING) {
        state = LifecycleState.READY;
      }
      return state == LifecycleState.READY;
    } finally {
      lock.unlock();
    }
  }
```

`markReady` moves `STARTING` to `READY`, and only that. If shutdown already began, the state is `DRAINING` or `STOPPED`, the `if` does nothing, and the method returns false: "shutdown began first, which wins". The last line returns whether the state is `READY` now.

```java
  /** Starts draining: from now on {@link #tryAdmit} refuses. Calling it again changes nothing. */
  public void startDraining() {
    lock.lock();
    try {
      if (state == LifecycleState.STARTING || state == LifecycleState.READY) {
        state = LifecycleState.DRAINING;
      }
    } finally {
      lock.unlock();
    }
  }
```

`startDraining` moves `STARTING` or `READY` to `DRAINING`. From `DRAINING` or `STOPPED` it changes nothing, so calling it twice is harmless (the Javadoc says so). From this moment `tryAdmit` refuses.

```java
  /**
   * Waits until no admitted request is left, or the deadline passes, then marks the instance
   * stopped. Starts draining first if nobody did.
   *
   * @param deadline the longest wait; zero means do not wait
   * @return how many requests were still in flight when the wait ended, and how long it took
   * @throws InterruptedException if the waiting thread is interrupted; the state is then stopped
   *     too, because a shutdown that was interrupted is still a shutdown
   */
  public DrainResult awaitDrained(Duration deadline) throws InterruptedException {
    Objects.requireNonNull(deadline, "deadline");
    if (deadline.isNegative()) {
      throw new IllegalArgumentException("the drain deadline cannot be negative: " + deadline);
    }
    long startNanos = System.nanoTime();
    long remainingNanos = deadline.toNanos();
    lock.lock();
    try {
      if (state == LifecycleState.STARTING || state == LifecycleState.READY) {
        state = LifecycleState.DRAINING;
      }
      // A loop, because a condition can wake up without a signal; awaitNanos returns what is left.
      while (inFlight > 0 && remainingNanos > 0) {
        remainingNanos = idle.awaitNanos(remainingNanos);
      }
      return new DrainResult(inFlight, Duration.ofNanos(System.nanoTime() - startNanos));
    } finally {
      state = LifecycleState.STOPPED;
      lock.unlock();
    }
  }
```

`awaitDrained` is the waiting half of the shutdown.

1. It refuses a negative deadline with `IllegalArgumentException`, a bug-in-the-caller error.
2. It notes the start time with `System.nanoTime()`, a clock that only measures elapsed time and cannot jump backwards. `remainingNanos` is the time left to wait.
3. It takes the lock and, as a safety step, starts draining if no one did.
4. The `while` loop waits while there is work (`inFlight > 0`) and time (`remainingNanos > 0`). `idle.awaitNanos(remainingNanos)` releases the lock, sleeps until another thread calls `signalAll` or the time runs out, takes the lock back, and returns how much of the time is left. The comment explains the loop: a waiting thread can wake up without a signal (a "spurious wakeup"), so the condition is checked again every time.
5. It returns a `DrainResult` with the number of requests still in flight and the time it waited.
6. The `finally` block sets the state to `STOPPED` and unlocks. It runs on every exit, including an interrupt: the Javadoc says "a shutdown that was interrupted is still a shutdown". `LifecycleTest.stopsWhenTheWaitingThreadIsInterrupted` tests that.

`throws InterruptedException` is how Java reports that another thread asked this one to stop waiting.

```java
  /**
   * Returns the current state.
   *
   * @return the state at the moment of the call
   */
  public LifecycleState state() {
    lock.lock();
    try {
      return state;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns the number of admitted requests whose response is not written yet.
   *
   * @return the in-flight count at the moment of the call
   */
  public long inFlight() {
    lock.lock();
    try {
      return inFlight;
    } finally {
      lock.unlock();
    }
  }
```

`state()` and `inFlight()` return the current values. They also take the lock: reading a value another thread writes, without a lock, could give a stale number. The Javadoc says "at the moment of the call", because the value may change right after.

```java
  private void release() {
    lock.lock();
    try {
      inFlight--;
      if (inFlight == 0) {
        idle.signalAll();
      }
    } finally {
      lock.unlock();
    }
  }
```

`release` is private: only an `Admission` calls it. It subtracts one, and if the count is now zero it calls `idle.signalAll()` to wake any thread waiting in `awaitDrained`. `signalAll` wakes every waiter; here there is at most one.

```java
  /**
   * One admitted request. Closing it ends the request's part of the in-flight count; closing it
   * twice counts once, so a handler's {@code finally} and a try-with-resources cannot both count.
   */
  public static final class Admission implements AutoCloseable {

    private final Lifecycle lifecycle;
    private final AtomicBoolean isClosed = new AtomicBoolean();

    private Admission(Lifecycle lifecycle) {
      this.lifecycle = lifecycle;
    }

    @Override
    public void close() {
      if (isClosed.compareAndSet(false, true)) {
        lifecycle.release();
      }
    }
  }
```

`Admission` is a nested class, the ticket.

- It implements `AutoCloseable`, so a caller can write `try (Admission a = ...)` and Java calls `close()` at the end. `SeatWaveServer` uses `finally` instead, with the same effect.
- `isClosed` is an `AtomicBoolean`. `compareAndSet(false, true)` changes it to true only if it is false now, and says whether it did. So the first `close()` returns the ticket to the lifecycle, and every later `close()` does nothing. The Javadoc says why: "a handler's finally and a try-with-resources cannot both count". `LifecycleTest.countsAnAdmissionClosedTwiceOnlyOnce` tests it.
- The constructor is private, so only `Lifecycle` can make a ticket.

```java
  /**
   * How a drain ended.
   *
   * @param inFlightLeft requests still running when the wait ended: zero after a clean drain, more
   *     when the deadline passed first
   * @param waited how long the wait took
   */
  public record DrainResult(long inFlightLeft, Duration waited) {

    /** Checks the fields. */
    public DrainResult {
      if (inFlightLeft < 0) {
        throw new IllegalArgumentException("inFlightLeft cannot be negative: " + inFlightLeft);
      }
      Objects.requireNonNull(waited, "waited");
    }

    /**
     * Says whether every admitted request finished before the deadline.
     *
     * @return true when nothing was left in flight
     */
    public boolean isClean() {
      return inFlightLeft == 0;
    }

    /**
     * Returns the wait in whole milliseconds, for a log line.
     *
     * @return the wait, rounded down
     */
    public long waitedMillis() {
      return TimeUnit.NANOSECONDS.toMillis(waited.toNanos());
    }
  }
}
```

`DrainResult` is a record: `inFlightLeft` (zero after a clean drain) and `waited`. Its constructor refuses a negative count and a missing duration. `isClean()` is true when nothing was left. `waitedMillis()` converts the `Duration` to whole milliseconds, rounded down, for a log line. `TimeUnit.NANOSECONDS.toMillis(...)` does the conversion.

## Try it yourself

All commands run from the project folder. Gradle prints one line per test. Timings differ on every run. These ran with the offline flag only because the build files were already in the local cache; you can leave it out.

1. Run the lifecycle tests and read their names. They are a list of the rules in this part.

```bash
./gradlew --offline cleanTest test --tests '*LifecycleTest' 2>&1 | grep -E "PASSED|FAILED|BUILD"
```

Eleven lines end in `PASSED`, for example `LifecycleTest > admitsNothingAfterDrainingStartsEvenUnderARace() PASSED`, and the last line is `BUILD SUCCESSFUL`.

2. Run the settings tests.

```bash
./gradlew --offline cleanTest test --tests '*ServiceSettingsTest' 2>&1 | grep -E "PASSED|FAILED|BUILD"
```

Six tests pass. One of them reads the real file `config/seatwave-container.properties` and checks that it is valid.

3. Collect the jar and its libraries, and start the program with nothing. It prints its usage and exits with status 2.

```bash
./gradlew --offline -q stackFiles
java -cp 'build/stack/lib/*' io.plainround.seatwave.balancer.seatwave.SeatWaveCli; echo "exit $?"
```

```text
usage: serve <seatwave.properties> <instance name, such as seatwave-1>
exit 2
```

4. Break a settings file on purpose and read every problem at once. This copy has no `db.url`, the same value for both ports, and a pool bigger than the worker count.

```bash
sed -e '/^db.url/d' -e 's/^http.adminPort=.*/http.adminPort=8080/' -e 's/^db.poolSize=.*/db.poolSize=65/' config/seatwave-container.properties > build/bad.properties
java -cp 'build/stack/lib/*' io.plainround.seatwave.balancer.seatwave.SeatWaveCli build/bad.properties seatwave-1; echo "exit $?"
```

```text
the settings in build/bad.properties have 3 problem(s):
  db.url is missing
  http.port and http.adminPort must differ (0 means any free port)
  db.poolSize must not be larger than http.workerThreads: a connection no worker can use is held open at the database for nothing
exit 2
```

An instance runs for real only in Docker Compose, where the database lives at `10.210.10.5`. These notes were written without Docker, so the full stack was not run for this part. The README's captured output shows it. Part 3 shows what an instance does without a database.

## Mistakes people make

- **Doing the check and the count in two steps.** The classic shutdown bug. Here one lock covers both, and `LifecycleTest.admitsNothingAfterDrainingStartsEvenUnderARace` hammers the gate from eight threads while draining starts.
- **Forgetting `finally` after `lock.lock()`.** An exception between lock and unlock would leave the lock taken for ever. Every lock use in `Lifecycle` has the `try`/`finally` pair.
- **Waiting with `if` instead of `while`.** A condition wait can wake up without a signal. The loop checks the condition again.
- **Closing a ticket twice and counting twice.** `Admission.close` is idempotent (safe to repeat) through `compareAndSet`.
- **Letting a settings rule be looser than the library behind it.** `db.connectTimeoutMillis` is accepted from 1 millisecond, but the connection pool library refuses anything under 250. A file with `db.connectTimeoutMillis=100` passes `ServiceSettings.from` and then the instance dies with a stack trace and exit status 1 (not the documented 2). Part 3 shows the run. The fix is to raise the minimum in `ServiceSettings` to 250.
- **Treating a broken database like a business answer.** "The seat is taken" is a `Result`. "The database did not answer" is an exception. Mixing them would let a database outage look like a sold-out show.
- **Using `find()` where `matches()` is meant.** `HoldCommand.require` uses `matches()`, so the whole value must fit the rule.

## Check yourself

1. Why are `tryAdmit`'s state check and its counter increment done under one lock?
   Answer: so that no request can be counted after draining began and no drain can miss a request counted just before. Two separate steps would let a request slip in between the drain's check and its stop.
2. What does `markReady` return if shutdown began during start, and why?
   Answer: false. The state is already `DRAINING`, and the shutdown wins over becoming ready.
3. Why is `HoldRefusal` a sealed interface and not a plain enum or a set of exceptions?
   Answer: the three refusals are expected answers, so they travel in a `Result`; sealed means the compiler knows the complete list and checks every `switch` over it.
4. A settings file has `http.port=9000` and `http.adminPort=9000`. What happens, and what does the user see?
   Answer: `from` records the problem "http.port and http.adminPort must differ (0 means any free port)" and returns an error with the whole list. The command-line entry point prints it and exits with status 2.
5. Why does `Admission.close` use `compareAndSet`?
   Answer: so closing twice counts once; only the call that flips the flag from false to true returns the ticket.

## Further reading

- Java SE 25 API, `java.util.concurrent.locks.ReentrantLock`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/locks/ReentrantLock.html
- Java SE 25 API, `Condition`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/locks/Condition.html
- Java SE 25 API, `java.util.concurrent.atomic.AtomicBoolean`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/atomic/AtomicBoolean.html
- Java Language Specification 25, records (Chapter 8.10) and sealed classes (Chapter 8.1.6): https://docs.oracle.com/javase/specs/jls/se25/html/index.html
- Java SE 25 API, `InetAddress.ofLiteral`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/InetAddress.html
- RFC 9651, Structured Field Values for HTTP (the Idempotency-Key string form): https://www.rfc-editor.org/rfc/rfc9651
