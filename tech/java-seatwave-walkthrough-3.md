# The `seatwave` package, part 3: the database store, the pool, the metrics and the command line (line by line)

This is part 3 of 3. Part 1 (`java-seatwave-walkthrough-1.md`) reads the settings, the small types and `Lifecycle`. Part 2 (`java-seatwave-walkthrough-2.md`) reads `Reply`, `Request`, `Problems`, `SeatWaveServer` and `HoldRoutes`. This part reads `JdbcHoldStore`, `DatabasePool`, `ServiceMetrics` and `SeatWaveCli`.

## What it is

- `JdbcHoldStore.java`: the real `HoldStore`. It keeps every hold in the shared database and decides "one seat, one owner" with one SQL statement.
- `DatabasePool.java`: opens the pool of database connections that all requests of one instance share.
- `ServiceMetrics.java`: the instance's counters, written as text at `GET /metrics`.
- `SeatWaveCli.java`: `main`, the starting point of the program. It reads the settings, builds the parts in order, and waits for the stop signal.

Terms you need.

- **JDBC** (Java Database Connectivity) is Java's standard way to talk to a relational database. A `Connection` is one open line to the database. A `PreparedStatement` is an SQL statement with `?` marks that you fill in with values. A `ResultSet` is the table of rows a query returns. An `SQLException` is the error when something fails. The details are in `postgresql-and-h2.md`.
- **SQL** is the language of relational databases; `sql-schema.md` explains it from zero, and every statement in this part is explained again where it appears.
- A **transaction** is a group of statements that the database treats as one: either all take effect (**commit**) or none does (**rollback**). Until the commit, other connections see none of its changes.
- A **row lock**: when a transaction changes a row, the database keeps that row locked until the transaction ends. Another transaction that wants to change the same row waits.
- An **isolation level** says what one transaction can see of the others. **READ COMMITTED**, the default of both PostgreSQL and H2, means every statement sees what had been committed before that statement began.
- A **unique constraint** is a database rule that no two rows may have the same value in a column. Breaking it raises an error with the standard code `23505`.
- A **connection pool** keeps a fixed set of open connections and lends them out, because opening a connection is slow (see `hikaricp.md`).
- A **metric** is a number that a program publishes about itself. A **counter** only goes up. A **gauge** goes up and down. A **histogram** counts how many observations fell at or below each of several limits.
- A **shutdown hook** is a piece of code the Java runtime runs when the process is asked to stop.
- A **signal** is the operating system's way to tell a process something. **SIGTERM** means "please stop"; `docker stop` sends it.
- An **exit status** is the number a program returns to the shell: 0 means success, other numbers mean failure.

## Why this repository uses it

- **The database is the single owner of every seat.** Three instances run at once and a balancer may retry a request on another instance. If the rule lived in each instance's memory, two fans could hold one seat. In `JdbcHoldStore`, one conditional `UPDATE` decides, and the database's row lock makes the check and the change one step.
- **A retried request must not hold twice.** A hold has an `Idempotency-Key`. The store keeps the key in a table with a unique constraint, so a retry finds the first hold, whichever instance it reaches.
- **The pool is fixed.** The number of database connections the database sees never changes with load: 16 per instance, 48 for three, under PostgreSQL's default limit of 100.
- **The instance starts without the database.** The pool does not fail at start. The readiness check fails instead, so the balancer sends the instance nothing until the database answers.
- **Metrics tell the lab what happened.** The queue-full and not-admitted counters are the instance's own account of what it refused.

## Where it appears

| File | What it does |
|---|---|
| `seatwave/JdbcHoldStore.java` | `hold`, `find` and `isReachable` against the database. Tested on H2 by `JdbcHoldStoreTest`. |
| `seatwave/DatabasePool.java` | `open`: reads the password file and creates the Hikari pool. Called by `SeatWaveCli.start`. |
| `seatwave/ServiceMetrics.java` | Counters; `render` writes the `/metrics` text. Used by `SeatWaveServer`. |
| `seatwave/SeatWaveCli.java` | `main`, `start`, `stop`. Run by Compose as the container command. |

## How it works underneath

**One hold, in database terms.** `hold` takes a connection from the pool and starts a transaction (it turns autocommit off, so nothing is final until `commit`). Inside the transaction:

1. Look up the key in `holds`. If the key is known, answer with that hold (a replay), or refuse if its fingerprint differs (a reused key).
2. Otherwise try to take the seat with one `UPDATE seats ... WHERE` statement. The `WHERE` clause says "the seat is free, or its hold has expired". If the statement changed one row, the seat is ours. If it changed zero rows, someone else holds it, or the seat does not exist.
3. Insert the hold into `holds`, then commit.

**Two instances race for one seat.** Both run the `UPDATE`. The database locks the row for the first one. The second waits. When the first commits, the second re-checks the `WHERE` clause against the new row, sees `HELD` with a future expiry, and changes zero rows. Exactly one wins. That is why the comment in the class says "the row lock makes check and set a single step".

**Two requests with one key race.** If both carry the same key and the same seat, the second one's `UPDATE` waits and changes nothing; the code then looks the key up again, now sees the committed hold, and replays it. If both carry the same key and different seats, both can take their own seat and both try to insert a row with that key. The unique constraint lets one insert pass and fails the other with code `23505`. The loser rolls back (which also releases its seat), looks the key up and replays the winner's hold, or refuses a reused key. I ran that second case with sixteen threads, one key and sixteen different seats, counting the `rollback` calls: all 16 got a granted hold (one new, fifteen replays), 15 rollbacks happened, and exactly one seat and one `holds` row existed afterwards. This branch (lines 98 to 109 of `JdbcHoldStore.java`) is not run by any test in the project: JaCoCo shows those lines as missed. The behavior is right, but nothing in the build would notice if it broke.

**The pool.** `DatabasePool` reads the password from a file, fills a configuration object, and makes a `HikariDataSource`. Each `getConnection()` borrows a connection; closing it gives it back. Hikari also puts the connection back into its original state on return (for example autocommit on), which the store relies on when it turns autocommit off.

**Starting and stopping.** `main` calls `start`, which checks the arguments, loads the settings, opens the pool, builds the store and the server, marks the instance ready and prints two lines. Then `main` registers a shutdown hook and waits. The hook runs on SIGTERM: it calls `stop`, which drains the server, closes the pool and prints one line.

## Walkthrough of this repository's use

### `seatwave/JdbcHoldStore.java`

The imports are the JDBC and time classes.

```java
package io.plainround.seatwave.balancer.seatwave;

import com.github.sviperll.result4j.Result;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
```

- `Connection`, `PreparedStatement`, `ResultSet`, `SQLException` are JDBC.
- `javax.sql.DataSource` is the standard "give me connections" interface. The store depends on this interface, not on Hikari, so a test can hand it H2's plain data source.
- `OffsetDateTime` is a date and time with an offset from UTC (`+00:00`). `ZoneOffset` names offsets. `ChronoUnit` names units of time such as milliseconds. `Instant` is a point on the time line.

```java
/**
 * Holds in the shared database (PostgreSQL in the stack, H2 in its PostgreSQL mode in the tests).
 *
 * <p>Module 1's rule, one seat one owner, is decided by one conditional {@code UPDATE}: the row
 * lock makes check and set a single step, so two instances racing for one seat cannot both win. The
 * {@code holds} table's unique key on the Idempotency-Key makes a retried request find the hold it
 * made, whichever instance the retry reaches. That matters behind a balancer that retries a request
 * when an instance died before answering (Section 6 of the module).
 *
 * <p>Transactions run at READ COMMITTED, the default of both databases: every statement sees what
 * was committed before it began, which is what the re-check after a lost race needs. Thread-safe:
 * every call takes its own connection from the pool.
 */
public final class JdbcHoldStore implements HoldStore {
```

The class comment is the design in short.

- Module 1's rule, one seat one owner, is decided by one conditional `UPDATE`.
- The `holds` table's unique key on the `Idempotency-Key` makes a retried request find its hold, whichever instance the retry reaches. It matters because a balancer retries a request when an instance died before it answered.
- Transactions run at READ COMMITTED, "the default of both databases", and the re-check after a lost race needs exactly that: each statement sees what was committed before it started.
- Thread-safe, because "every call takes its own connection from the pool".

```java
  // Module 1's hold: 300 seconds.
  static final Duration HOLD_DURATION = Duration.ofSeconds(300);
  // The SQL standard's class 23, "integrity constraint violation", code 505 "unique violation";
  // PostgreSQL and H2 both report it for a duplicate key.
  private static final String UNIQUE_VIOLATION = "23505";
```

- `HOLD_DURATION` is 300 seconds, Module 1's hold time. It is package-visible so tests can use it.
- `UNIQUE_VIOLATION` is `"23505"`. An `SQLException` has an **SQLState**, a five-character code from the SQL standard. Class `23` is "integrity constraint violation" and `505` is "unique violation". The comment says PostgreSQL and H2 both report it for a duplicate key. The code recognises the duplicate by this code and not by the message text, which differs between databases.

Now the four SQL statements and one more, each a Java **text block**: text between `"""` marks that may span lines. The closing `\` at the end of the last line tells Java not to add a final newline.

```java
  private static final String FIND_BY_KEY =
      """
      SELECT hold_id, body_sha256, event_id, seat_label, customer, expires_at
      FROM holds WHERE idempotency_key = ?\
      """;
  private static final String FIND_BY_ID =
      """
      SELECT hold_id, body_sha256, event_id, seat_label, customer, expires_at
      FROM holds WHERE hold_id = ?\
      """;
```

- `FIND_BY_KEY`: select six columns from `holds` for the row whose `idempotency_key` equals the first `?`. The unique constraint on that column also gives the database an index, so the lookup is fast.
- `FIND_BY_ID`: the same six columns, found by `hold_id`, the primary key.

```java
  // The WHERE clause is the whole rule: free, or held with an expired hold.
  private static final String TAKE_SEAT =
      """
      UPDATE seats SET status = 'HELD', hold_id = ?, hold_customer = ?, hold_expires_at = ?
      WHERE event_id = ? AND seat_label = ?
        AND (status = 'AVAILABLE' OR (status = 'HELD' AND hold_expires_at <= ?))\
      """;
```

`TAKE_SEAT` is the heart of the module.

```text
UPDATE seats SET status = 'HELD', hold_id = ?, hold_customer = ?, hold_expires_at = ?
WHERE event_id = ? AND seat_label = ?
  AND (status = 'AVAILABLE' OR (status = 'HELD' AND hold_expires_at <= ?))
```

It sets the four columns of the seat row (status, hold id, customer, expiry) for the one seat named by event and label, but only if the seat is `AVAILABLE`, or is `HELD` and its hold has expired (`hold_expires_at <= now`). The comment is right: "The WHERE clause is the whole rule". The statement returns the number of rows it changed: 1 means "the seat is yours", 0 means "not yours". A seat that is `CONFIRMED` (sold) never matches.

```java
  private static final String RECORD_HOLD =
      """
      INSERT INTO holds (hold_id, idempotency_key, body_sha256, event_id, seat_label, customer,
                         expires_at)
      VALUES (?, ?, ?, ?, ?, ?, ?)\
      """;
  private static final String SEAT_EXISTS =
      "SELECT 1 FROM seats WHERE event_id = ? AND seat_label = ?";
```

- `RECORD_HOLD` inserts one row into `holds`: id, key, fingerprint, event, seat, customer, expiry. Seven columns and seven `?` marks.
- `SEAT_EXISTS` is `SELECT 1 FROM seats WHERE ...`: it returns a row if the seat exists. It is used only to tell "taken" from "does not exist" after the `UPDATE` changed nothing.

```java
  private final DataSource dataSource;
  private final Clock clock;
  private final int reachableTimeoutSeconds;

  /**
   * Creates the store.
   *
   * @param dataSource the shared pool, created once by the caller and never closed here
   * @param clock the clock holds start and expire by
   * @param reachableTimeout how long the readiness check waits for the database, at least 1 s
   *     because JDBC counts {@code isValid} in whole seconds
   */
  public JdbcHoldStore(DataSource dataSource, Clock clock, Duration reachableTimeout) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.clock = Objects.requireNonNull(clock, "clock");
    long seconds = Objects.requireNonNull(reachableTimeout, "reachableTimeout").toSeconds();
    if (seconds < 1) {
      throw new IllegalArgumentException("reachableTimeout must be at least 1 s");
    }
    this.reachableTimeoutSeconds = Math.toIntExact(seconds);
  }
```

The fields and the constructor. The Javadoc says the pool is "created once by the caller and never closed here". The constructor refuses a readiness timeout under one second, because `Connection.isValid` takes whole seconds: a smaller number would round down to zero, which JDBC reads as "wait for ever". `toSeconds()` cuts the fraction, so 999 ms becomes 0 and is refused. `Math.toIntExact` converts a `long` to an `int` and throws if it does not fit. `JdbcHoldStoreTest.rejectsAReadinessTimeoutUnderOneSecond` tests it.

```java
  @Override
  public Result<GrantedHold, HoldRefusal> hold(HoldCommand command)
      throws StoreUnavailableException {
    Objects.requireNonNull(command, "command");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        Result<GrantedHold, HoldRefusal> outcome = holdInTransaction(connection, command);
        connection.commit();
        return outcome;
```

`hold` begins.

- `try (Connection connection = dataSource.getConnection())` borrows a connection and returns it to the pool at the end, whatever happens.
- `connection.setAutoCommit(false)` starts manual transaction control: no statement is final until `commit()`.
- `holdInTransaction` does the work and returns the `Result`. Then `connection.commit()` makes everything final, and `outcome` is returned.

Note that a refusal such as `SeatTaken` is a normal return, so it also commits; the transaction changed nothing.

```java
      } catch (SQLException failure) {
        connection.rollback();
        if (!UNIQUE_VIOLATION.equals(failure.getSQLState())) {
          throw failure;
        }
        // Another request with the same key committed first: this is a retry that raced its
        // original, so answer what the original made.
        StoredHold original =
            findByKey(connection, command.idempotencyKey())
                .orElseThrow(() -> new SQLException("a duplicate key without a hold", failure));
        connection.commit();
        return replay(original, command);
      }
```

If an `SQLException` happens inside:

1. `connection.rollback()` throws away everything the transaction did. In PostgreSQL a failed statement leaves the transaction unusable until a rollback, so this must come first.
2. `if (!UNIQUE_VIOLATION.equals(failure.getSQLState())) throw failure;` any error other than a duplicate key goes up.
3. For a duplicate key, the comment explains: "Another request with the same key committed first: this is a retry that raced its original". The code reads the key again (in a new transaction) with `findByKey`. If no row is there, something is wrong, and it throws a new `SQLException` that keeps the first as its cause. Otherwise it commits (ending that read transaction) and answers `replay(original, command)`.

```java
    } catch (SQLException failure) {
      throw new StoreUnavailableException("holding seat " + command.seat() + " failed", failure);
    }
  }
```

The outer `catch` turns any `SQLException` (a failed connection, a failed statement) into `StoreUnavailableException` with the message "holding seat A-7 failed". `JdbcHoldStoreTest.turnsADatabaseFailureIntoStoreUnavailable` checks that the message holds the seat.

```java
  @Override
  public Optional<Hold> find(String holdId) throws StoreUnavailableException {
    Objects.requireNonNull(holdId, "holdId");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(FIND_BY_ID)) {
      statement.setString(1, holdId);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? Optional.of(holdFrom(rows)) : Optional.empty();
      }
    } catch (SQLException failure) {
      throw new StoreUnavailableException("reading hold " + holdId + " failed", failure);
    }
  }
```

`find` borrows a connection, prepares `FIND_BY_ID`, fills the first `?` with `setString(1, holdId)` (parameters count from 1), runs the query, and returns `Optional.of(holdFrom(rows))` if a row came back (`rows.next()` moves to the first row and says whether there is one) or `Optional.empty()`. Using `?` marks and not gluing the id into the SQL text is what stops **SQL injection**, the attack where a client sends text that changes the SQL. The route already limits the id to a UUID, but the store does not rely on that.

```java
  @Override
  public boolean isReachable() {
    try (Connection connection = dataSource.getConnection()) {
      return connection.isValid(reachableTimeoutSeconds);
    } catch (SQLException unreachable) {
      // The readiness check's whole job is to answer "no" here; the pool logs the cause itself.
      return false;
    }
  }
```

`isReachable` borrows a connection and asks `isValid(timeout)`, which sends a tiny test to the database. If borrowing fails (the pool times out or the database is down), the `SQLException` means "no". The comment says "the pool logs the cause itself". Both outcomes return a plain boolean, never an exception.

```java
  private Result<GrantedHold, HoldRefusal> holdInTransaction(
      Connection connection, HoldCommand command) throws SQLException {
    Optional<StoredHold> earlier = findByKey(connection, command.idempotencyKey());
    if (earlier.isPresent()) {
      return replay(earlier.get(), command);
    }
    // Milliseconds: both databases keep microseconds, so a replay read back from the table must
    // print the same expiry as the first answer did.
```

`holdInTransaction` runs the steps in order. Step 1: `findByKey`. If a hold with this key exists, `replay` it at once: nothing is written.

```java
    Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    Hold hold =
        new Hold(
            UUID.randomUUID().toString(),
            command.eventId(),
            command.seat(),
            command.customer(),
            now.plus(HOLD_DURATION));
```

Step 2 builds the new hold.

- `clock.instant().truncatedTo(ChronoUnit.MILLIS)` cuts the time to whole milliseconds. The comment gives the reason: both databases keep microseconds, so a replay read back from the table must print the same expiry as the first answer.
- `UUID.randomUUID().toString()` makes the id. `now.plus(HOLD_DURATION)` is the expiry.

```java
    if (takeSeat(connection, hold, now) == 0) {
      // The UPDATE may have waited for a racing request's row lock; if that request carried this
      // same key, it has committed by now and this request is its retry, not a loser.
      Optional<StoredHold> raced = findByKey(connection, command.idempotencyKey());
      if (raced.isPresent()) {
        return replay(raced.get(), command);
      }
      return Result.error(
          seatExists(connection, command)
              ? new HoldRefusal.SeatTaken()
              : new HoldRefusal.NoSuchSeat());
    }
```

`takeSeat(...) == 0` means no row changed.

- The comment explains the re-read: the `UPDATE` may have waited for a racing request's row lock. If that request carried the same key, it has committed by now and this request is its retry, not a loser. So `findByKey` again, and replay if found.
- Otherwise `seatExists` tells the two refusals apart: `SeatTaken` if the seat is there, `NoSuchSeat` if not.

```java
    recordHold(connection, hold, command);
    return Result.success(new GrantedHold(hold, false));
  }
```

Success path: `recordHold` inserts the row into `holds`, and the method returns `GrantedHold(hold, false)`. If a concurrent request inserted the same key first, `recordHold` raises the unique violation and the catch in `hold` handles it.

```java
  private int takeSeat(Connection connection, Hold hold, Instant now) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(TAKE_SEAT)) {
      statement.setString(1, hold.holdId());
      statement.setString(2, hold.customer());
      statement.setObject(3, utc(hold.expiresAt()));
      statement.setLong(4, hold.eventId());
      statement.setString(5, hold.seat());
      statement.setObject(6, utc(now));
      return statement.executeUpdate();
    }
  }
```

`takeSeat` prepares `TAKE_SEAT` and fills six places:

1. the hold id, 2. the customer, 3. the expiry (as an `OffsetDateTime` in UTC), 4. the event number (`setLong`), 5. the seat label, 6. the time `now` for the "already expired" test.

`executeUpdate()` returns the number of rows changed. `utc(...)` (below) makes the time values.

```java
  private static void recordHold(Connection connection, Hold hold, HoldCommand command)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(RECORD_HOLD)) {
      statement.setString(1, hold.holdId());
      statement.setString(2, command.idempotencyKey());
      statement.setString(3, command.fingerprint());
      statement.setLong(4, hold.eventId());
      statement.setString(5, hold.seat());
      statement.setString(6, hold.customer());
      statement.setObject(7, utc(hold.expiresAt()));
      statement.executeUpdate();
    }
  }

  private static boolean seatExists(Connection connection, HoldCommand command)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(SEAT_EXISTS)) {
      statement.setLong(1, command.eventId());
      statement.setString(2, command.seat());
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }
```

- `recordHold` fills seven places and runs `executeUpdate()`; it ignores the count because a plain `INSERT` either adds one row or throws.
- `seatExists` fills the event and the label and returns whether the query gave a row.

Both are `private static`: they use only their arguments.

```java
  private static Optional<StoredHold> findByKey(Connection connection, String idempotencyKey)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(FIND_BY_KEY)) {
      statement.setString(1, idempotencyKey);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next()
            ? Optional.of(new StoredHold(holdFrom(rows), rows.getString("body_sha256")))
            : Optional.empty();
      }
    }
  }
```

`findByKey` runs `FIND_BY_KEY`. If a row exists, it builds a `StoredHold` from the `Hold` (via `holdFrom`) and the stored fingerprint (`rows.getString("body_sha256")`); else it returns an empty `Optional`. Columns are read by name, not by position.

```java
  private static Result<GrantedHold, HoldRefusal> replay(StoredHold stored, HoldCommand command) {
    // Module 7: the same key with a different request is refused, never answered with the other
    // request's hold.
    return stored.fingerprint().equals(command.fingerprint())
        ? Result.success(new GrantedHold(stored.hold(), true))
        : Result.error(new HoldRefusal.KeyReused());
  }
```

`replay` is the Module 7 rule. If the stored fingerprint equals the command's, the same request came again: success with `isReplay` true. If it differs, the same key was used for another request: the error `KeyReused`, never the other request's hold.

```java
  private static Hold holdFrom(ResultSet rows) throws SQLException {
    return new Hold(
        rows.getString("hold_id"),
        rows.getLong("event_id"),
        rows.getString("seat_label"),
        rows.getString("customer"),
        rows.getObject("expires_at", OffsetDateTime.class).toInstant());
  }

  private static OffsetDateTime utc(Instant instant) {
    // An explicit offset: java.sql.Timestamp would be read in the JVM's own time zone.
    return instant.atOffset(ZoneOffset.UTC);
  }
```

- `holdFrom` builds a `Hold` from the current row. `rows.getObject("expires_at", OffsetDateTime.class).toInstant()` reads the time column as an `OffsetDateTime` and converts it to an `Instant`.
- `utc` turns an `Instant` into an `OffsetDateTime` at offset UTC. The comment says why: `java.sql.Timestamp` "would be read in the JVM's own time zone", which would shift times on a machine set to another zone. An explicit offset has no such ambiguity.

```java
  /** A hold with the fingerprint of the request that made it. */
  private record StoredHold(Hold hold, String fingerprint) {}
}
```

`StoredHold` is a private record that pairs a `Hold` with the fingerprint of the request that made it.

### `seatwave/DatabasePool.java`

```java
package io.plainround.seatwave.balancer.seatwave;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.Objects;

/**
 * Opens the one connection pool an instance shares between all its requests (HikariCP).
 * Thread-safe: it holds no state.
 */
final class DatabasePool {

  private DatabasePool() {}
```

- Package-private class (no `public`), private constructor: a holder of one static method.
- HikariCP is the pool library (see `hikaricp.md`).

```java
  /**
   * Creates the pool. It does not fail when the database is down at start: the instance then starts
   * with a failing readiness check, and the balancer sends it nothing until the database answers.
   *
   * @param settings where the database is
   * @param instanceName the instance, which names the pool in the database's own view
   * @return the pool; the caller closes it when the instance stops
   * @throws IOException if the password file cannot be read; the message names the file
   */
  static HikariDataSource open(ServiceSettings.DatabaseSettings settings, String instanceName)
      throws IOException {
    Objects.requireNonNull(settings, "settings");
```

The Javadoc makes the key promise: the pool "does not fail when the database is down at start", so the instance starts with a failing readiness check and the balancer sends it nothing until the database answers. The caller closes the pool. The method declares `throws IOException` for an unreadable password file.

```java
    Objects.requireNonNull(instanceName, "instanceName");
    String password;
    try {
      password = Files.readString(settings.passwordFile(), StandardCharsets.UTF_8).strip();
    } catch (NoSuchFileException missing) {
      throw new IOException(
          "the database password file " + settings.passwordFile() + " does not exist", missing);
    }
```

`Files.readString(path, UTF_8).strip()` reads the whole password file and removes the final newline and spaces. A missing file becomes an `IOException` whose message names the file: "the database password file ... does not exist". `SeatWaveCliTest.saysWhichPasswordFileIsMissing` tests that. The password is only ever held in this local variable and handed to Hikari.

```java
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(settings.url());
    config.setUsername(settings.user());
    config.setPassword(password);
    config.setPoolName(instanceName);
    // A fixed pool: the number of connections the database sees never changes under load, which
    // is what HikariCP's own sizing advice recommends.
    config.setMaximumPoolSize(settings.poolSize());
    config.setMinimumIdle(settings.poolSize());
    config.setConnectionTimeout(settings.connectTimeout().toMillis());
    config.setValidationTimeout(settings.checkTimeout().toMillis());
    // Negative: start without the database instead of failing; readiness tells the balancer.
    config.setInitializationFailTimeout(-1);
    return new HikariDataSource(config);
  }
}
```

Each setting of the pool.

- `setJdbcUrl`, `setUsername`, `setPassword`: where and as whom to connect.
- `setPoolName(instanceName)`: the pool's name, which appears in its log lines (`seatwave-1 - Starting...`).
- `setMaximumPoolSize(n)` and `setMinimumIdle(n)` with the same number: a **fixed** pool. The comment: the number of connections the database sees never changes under load, "which is what HikariCP's own sizing advice recommends".
- `setConnectionTimeout(ms)`: the longest a request waits to borrow a connection. After that, `getConnection()` throws.
- `setValidationTimeout(ms)`: the longest the pool waits to test that a connection still works.
- `setInitializationFailTimeout(-1)`: a negative number means "start without trying to connect". The comment: start without the database instead of failing; readiness tells the balancer.
- `new HikariDataSource(config)` creates the pool.

Hikari's own limits are stricter than `ServiceSettings`. Hikari throws `IllegalArgumentException("connectionTimeout cannot be less than 250ms")` for any connection timeout under 250 ms, and the same kind of error for a validation timeout under 250 ms. `ServiceSettings` accepts `db.connectTimeoutMillis` from 1. `DatabasePool.open` catches only `IOException`, so a settings file with a value from 1 to 249 starts the program, throws out of `main`, prints a stack trace and exits with status 1. The validation timeout cannot hit the limit, because the settings enforce at least 1,000 ms for it. This is a real defect and a test would catch it; the project has none.

### `seatwave/ServiceMetrics.java`

```java
package io.plainround.seatwave.balancer.seatwave;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
```

`List`, `Locale`, `Map`, `TreeMap` (a map that keeps its keys sorted), `ConcurrentHashMap` and `ConcurrentMap` (maps safe for many threads) and `LongAdder` (a counter safe for many threads).

```java
/**
 * One instance's counters, written in the Prometheus text format at {@code GET /metrics}: requests
 * by route and status, a duration histogram, requests refused because the instance was not ready,
 * requests refused by a full queue, and the lifecycle's state and in-flight count.
 *
 * <p>The labels are the route template and the status, never a raw path or a client address, so the
 * number of series stays small (the guidelines' rule 68). Thread-safe: every field is a concurrent
 * counter.
 */
final class ServiceMetrics {

  // Upper bounds of the duration histogram, in seconds: Module 2's targets fall between them.
  private static final List<Double> BUCKET_SECONDS =
      List.of(0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5);
  private static final double NANOS_PER_SECOND = 1_000_000_000.0;
```

The Javadoc lists what is counted: requests by route and status, a duration histogram, requests refused because the instance was not ready, requests refused by a full queue, and the lifecycle state and in-flight count. The labels are "the route template and the status, never a raw path or a client address, so the number of series stays small". Each distinct label combination is a separate series that the monitoring system stores; a label with a hold id in it would create one series per hold and drown it.

`BUCKET_SECONDS` lists the upper limits of the histogram in seconds: 1 ms, 2.5 ms, 5 ms, 10 ms, 25 ms, 50 ms, 100 ms, 250 ms, 500 ms, 1 s and 2.5 s. `NANOS_PER_SECOND` converts nanoseconds to seconds.

```java
  private final ConcurrentMap<String, LongAdder> requests = new ConcurrentHashMap<>();
  private final LongAdder[] bucketCounts = new LongAdder[BUCKET_SECONDS.size()];
  private final LongAdder durationCount = new LongAdder();
  private final LongAdder durationNanos = new LongAdder();
  private final LongAdder notAdmitted = new LongAdder();
  private final LongAdder rejectedByQueue = new LongAdder();
  private final Lifecycle lifecycle;

  ServiceMetrics(Lifecycle lifecycle) {
    this.lifecycle = lifecycle;
    for (int bucketIndex = 0; bucketIndex < bucketCounts.length; bucketIndex++) {
      bucketCounts[bucketIndex] = new LongAdder();
    }
  }
```

- `requests` maps the text `route|status` to its counter.
- `bucketCounts` is an array of counters, one for each limit.
- `durationCount` and `durationNanos` count the observations and add up their time.
- `notAdmitted` and `rejectedByQueue` are the two refusal counters.
- `LongAdder` is faster than a plain atomic number when many threads add at once, because it spreads the additions over several cells and adds the cells up when you call `sum()`.
- The constructor stores the lifecycle (for the state and in-flight gauges) and fills the array with new counters. A Java array of objects starts full of `null`.

```java
  void finished(String route, int status, long elapsedNanos) {
    requests.computeIfAbsent(route + "|" + status, key -> new LongAdder()).increment();
    durationCount.increment();
    durationNanos.add(elapsedNanos);
    double seconds = elapsedNanos / NANOS_PER_SECOND;
    for (int bucketIndex = 0; bucketIndex < bucketCounts.length; bucketIndex++) {
      if (seconds <= BUCKET_SECONDS.get(bucketIndex)) {
        bucketCounts[bucketIndex].increment();
      }
    }
  }
```

`finished` is called once per answered request.

- `requests.computeIfAbsent(key, ...)` finds the counter for `route|status` or makes it, and `increment()` adds one. `computeIfAbsent` is safe when two threads make the same key at once.
- It adds one to the count and the elapsed time to the sum.
- Then for each bucket: if the duration in seconds is at or below the limit, add one. A Prometheus histogram is **cumulative**: the bucket for 10 ms counts everything at or below 10 ms, including what is in the 1 ms bucket.

```java
  void notAdmitted() {
    notAdmitted.increment();
  }

  void rejectedByQueue() {
    rejectedByQueue.increment();
  }

  long notAdmittedCount() {
    return notAdmitted.sum();
  }
```

Three tiny methods: count one request that was not admitted, count one that the full queue rejected, and read the not-admitted total (used for the last log line when the instance stops).

```java
  String render() {
    StringBuilder text = new StringBuilder();
    text.append("# TYPE seatwave_requests_total counter\n");
    Map<String, Long> sorted = new TreeMap<>();
    requests.forEach((key, count) -> sorted.put(key, count.sum()));
    sorted.forEach((key, count) -> {
      int bar = key.lastIndexOf('|');
      text.append(
          String.format(
              Locale.ROOT,
              "seatwave_requests_total{route=\"%s\",status=\"%s\"} %d%n",
              key.substring(0, bar),
              key.substring(bar + 1),
              count));
    });
    text.append("# TYPE seatwave_request_seconds histogram\n");
```

`render` builds the `/metrics` text with a `StringBuilder`. First the request counters. They are copied into a `TreeMap`, so the lines come out in sorted order every time. For each entry, `key.lastIndexOf('|')` finds the last bar (the route has spaces and slashes but no bar), and `String.format` writes `seatwave_requests_total{route="POST /v1/holds",status="503"} 3`. `Locale.ROOT` fixes the number formatting. `%d` is a whole number, `%s` a text, `%n` the platform's line break.

```java
    for (int bucketIndex = 0; bucketIndex < bucketCounts.length; bucketIndex++) {
      text.append(
          String.format(
              Locale.ROOT,
              "seatwave_request_seconds_bucket{le=\"%s\"} %d%n",
              BUCKET_SECONDS.get(bucketIndex),
              bucketCounts[bucketIndex].sum()));
    }
    long count = durationCount.sum();
```

The histogram lines, one for each limit: `seatwave_request_seconds_bucket{le="0.001"} 0`. The label `le` means "less than or equal". Java's text for the double `1.0` is `1.0`, so the limit prints as `1.0`.

```java
    text.append(
        String.format(Locale.ROOT, "seatwave_request_seconds_bucket{le=\"+Inf\"} %d%n", count));
    text.append(
        String.format(
            Locale.ROOT,
            "seatwave_request_seconds_sum %.6f%n",
            durationNanos.sum() / NANOS_PER_SECOND));
    text.append(String.format(Locale.ROOT, "seatwave_request_seconds_count %d%n", count));
    line(text, "counter", "seatwave_not_admitted_total", notAdmitted.sum());
```

- The last bucket, `le="+Inf"`, always equals the total count.
- `seatwave_request_seconds_sum` is the total time in seconds with six decimals (`%.6f`), and `seatwave_request_seconds_count` is the number of observations. A monitoring system divides the two to get an average.
- The next lines write the two refusal counters through the helper `line`.

```java
    line(text, "counter", "seatwave_rejected_queue_full_total", rejectedByQueue.sum());
    line(text, "gauge", "seatwave_in_flight", lifecycle.inFlight());
    text.append("# TYPE seatwave_state gauge\n");
```

The queue-full counter and the in-flight gauge (`lifecycle.inFlight()`) are written by `line`.

```java
    LifecycleState current = lifecycle.state();
    for (LifecycleState state : LifecycleState.values()) {
      text.append(
          String.format(
              Locale.ROOT,
              "seatwave_state{state=\"%s\"} %d%n",
              state.name().toLowerCase(Locale.ROOT),
              state == current ? 1 : 0));
    }
    return text.toString();
  }
```

The state gauge. For every value of `LifecycleState`, one line with a value of 1 for the current state and 0 for the others: `seatwave_state{state="ready"} 1`. A dashboard can show the state as a colored bar.

```java
  private static void line(StringBuilder text, String type, String name, long value) {
    text.append("# TYPE ").append(name).append(' ').append(type).append('\n');
    text.append(name).append(' ').append(value).append('\n');
  }
}
```

`line` writes a `# TYPE` line (which tells the reader whether the metric is a counter or a gauge) and one value line. It joins pieces with `append`. A note for a reader who cares: this helper ends lines with `\n`, while the others use `%n`. On Linux and macOS they are the same character. On Windows `%n` is a carriage return plus a line feed, which the Prometheus text format does not allow, so the two styles should be one.

One more note: the buckets, the count and the sum are read at slightly different moments, so a scrape taken during traffic can show a bucket and a total that disagree by a request or two. That is normal for counters read without a lock.

### `seatwave/SeatWaveCli.java`

```java
package io.plainround.seatwave.balancer.seatwave;

import com.zaxxer.hikari.HikariDataSource;
import io.plainround.seatwave.balancer.config.SettingsFiles;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;
```

The imports: `HikariDataSource`, `SettingsFiles` from the `config` package, `PrintStream` (a place to print text, like `System.out`), `CountDownLatch` (a gate that opens after a count reaches zero) and `Pattern`.

```java
/**
 * Command-line entry point of one SeatWave instance: reads the settings, opens the database pool,
 * starts the listeners, marks itself ready, and runs until it is told to stop (Ctrl-C, or {@code
 * docker stop}'s SIGTERM). Then it drains: it refuses new requests, waits for the ones in flight up
 * to {@code drain.deadlineMillis}, and only then closes. Exit status 2 means bad usage or an
 * invalid settings file.
 */
public final class SeatWaveCli {

  // Exit status 2 is the shell convention for "the command was used incorrectly".
  static final int EXIT_BAD_USAGE = 2;
  // The name shows up in X-Served-By, in the balancer's server list and in log lines.
  private static final Pattern INSTANCE_NAME = Pattern.compile("[a-z][a-z0-9-]{0,31}");
```

The class comment gives the whole life of the program: read the settings, open the pool, start the listeners, mark ready, run until told to stop (Ctrl-C, or the SIGTERM of `docker stop`), then drain. Exit status 2 means bad usage or an invalid settings file.

- `EXIT_BAD_USAGE = 2`: "the shell convention for 'the command was used incorrectly'".
- `INSTANCE_NAME` is a lowercase letter followed by up to 31 lowercase letters, digits or hyphens. The name goes into `X-Served-By`, into the balancer's server list and into log lines, so it must be tidy.

```java
  private SeatWaveCli() {}

  /**
   * Runs one instance.
   *
   * @param arguments the settings file and the instance name, for example {@code
   *     /app/config/seatwave-container.properties seatwave-1}
   * @throws InterruptedException if the main thread is interrupted while it waits
   */
  public static void main(String[] arguments) throws InterruptedException {
    Optional<Running> running = start(arguments, System.out, System.err);
    if (running.isEmpty()) {
      System.exit(EXIT_BAD_USAGE);
      return;
    }
    CountDownLatch stopped = new CountDownLatch(1);
    // The JVM runs this hook on SIGTERM and waits for it, so the drain finishes before the process
    // exits; docker stop's grace period (compose.yaml) is longer than the drain deadline.
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(() -> {
              stop(running.get(), System.out);
              stopped.countDown();
            }));
    stopped.await();
  }
```

`main` is the method Java runs first.

1. `start(arguments, System.out, System.err)` returns an `Optional<Running>`. Empty means it printed why it could not start, so `main` calls `System.exit(2)`.
2. `CountDownLatch stopped = new CountDownLatch(1)` is a gate that opens after one `countDown()`.
3. `Runtime.getRuntime().addShutdownHook(new Thread(...))` registers a thread that the JVM starts when the process is asked to stop. The comment explains why this is enough: the JVM runs the hook on SIGTERM and waits for it, so the drain finishes before the process exits; and `docker stop` waits longer than the drain deadline (`stop_grace_period: 30s` in `compose.yaml`, against `drain.deadlineMillis=20000`), so Docker does not kill the instance in the middle of a drain.
4. `stopped.await()` blocks the main thread until the hook has finished the drain, so `main` returns only after the instance has stopped. The hook opens the gate after `stop` is done.

The program stays alive until SIGTERM. The status then is 143, the number of the signal plus 128: the shell's way to report "killed by signal 15".

```java
  /**
   * Checks the arguments and the settings, opens the pool and starts the listeners.
   *
   * @param arguments the command line
   * @param out where the listening addresses are printed
   * @param err where problems are printed
   * @return the running instance, or empty after printing why it cannot start
   */
  static Optional<Running> start(String[] arguments, PrintStream out, PrintStream err) {
    if (arguments.length != 2 || !INSTANCE_NAME.matcher(arguments[1]).matches()) {
      err.println("usage: serve <seatwave.properties> <instance name, such as seatwave-1>");
      return Optional.empty();
    }
```

`start` is separate from `main` so a test can call it. The Javadoc says it checks the arguments and the settings, opens the pool and starts the listeners. First check: exactly two arguments and a name that fits `INSTANCE_NAME`, else print the usage line and return empty. (The usage line begins with the word `serve`, a name from an earlier project; nothing in this project is called `serve`. The container runs the class directly. It is a small misleading word in the message.)

```java
    Optional<ServiceSettings> loaded =
        SettingsFiles.load(Path.of(arguments[0]), ServiceSettings::from, err);
    if (loaded.isEmpty()) {
      return Optional.empty();
    }
    ServiceSettings settings = loaded.get();
    String instanceName = arguments[1];
    HikariDataSource pool;
    try {
      pool = DatabasePool.open(settings.database(), instanceName);
    } catch (IOException cannotRead) {
      err.println("SeatWave cannot start: " + cannotRead.getMessage());
      return Optional.empty();
    }
```

- `SettingsFiles.load(Path.of(arguments[0]), ServiceSettings::from, err)` loads the file and checks it. Any problem is printed by `SettingsFiles`, and an empty `Optional` comes back.
- `DatabasePool.open(...)` builds the pool; an `IOException` (an unreadable password file) prints "SeatWave cannot start: ..." and returns empty.

```java
    try {
      HoldStore store =
          new JdbcHoldStore(pool, Clock.systemUTC(), settings.database().checkTimeout());
      SeatWaveServer server =
          SeatWaveServer.start(settings, instanceName, store, Clock.systemUTC());
      server.markReady();
      String address = settings.bindAddress().getHostAddress();
      out.println(instanceName + " serving on " + address + ":" + server.port());
      out.println(
          instanceName
              + " checks (/health, /ready, /metrics) on "
              + address
              + ":"
              + server.adminPort());
      return Optional.of(new Running(server, pool, settings, instanceName));
    } catch (IOException cannotStart) {
      pool.close();
      err.println("SeatWave cannot start: " + cannotStart.getMessage());
      return Optional.empty();
    }
  }
```

- A real `JdbcHoldStore` is built on the pool, with the system clock in UTC (`Clock.systemUTC()`), and the readiness timeout from the settings.
- `SeatWaveServer.start` opens the ports; `server.markReady()` lets requests in.
- Two lines are printed to standard output: `<name> serving on <address>:<port>` and `<name> checks (/health, /ready, /metrics) on <address>:<port>`. The ports come from the server, which matters when the settings said 0. The scripts and the tests read these lines.
- If a port cannot be bound (an `IOException`), the pool is closed and "SeatWave cannot start: ..." is printed. The pool is closed in that branch so a failed start leaves no connections open.

```java
  /**
   * Drains and stops a running instance, then closes its pool.
   *
   * @param running the instance
   * @param out where the drain's result is printed
   */
  static void stop(Running running, PrintStream out) {
    Lifecycle.DrainResult result =
        running.server().drainAndStop(running.settings().drainDeadline());
    running.pool().close();
    out.println(
        running.instanceName()
            + (result.isClean() ? " drained cleanly: " : " drain deadline passed: ")
            + result.inFlightLeft()
            + " in flight left after "
            + result.waitedMillis()
            + " ms; "
            + running.server().notAdmittedCount()
            + " requests refused while not ready; stopped");
  }
```

`stop` drains the server with the deadline from the settings, closes the pool and prints one line:

`seatwave-local drained cleanly: 0 in flight left after 0 ms; 0 requests refused while not ready; stopped`

or, when the deadline passed with requests still running, `drain deadline passed: N in flight left`. The text is built by joining pieces with `+` and choosing the first word with a conditional. The order matters: the server is drained first, and only then is the pool closed, so requests still running keep their database connections.

```java
  /** A started instance and what must be closed with it. */
  record Running(
      SeatWaveServer server,
      HikariDataSource pool,
      ServiceSettings settings,
      String instanceName) {}
}
```

`Running` is a record that holds the four things `stop` needs: the server, the pool, the settings and the name.

## Try it yourself

All commands run in the project folder; timings and ids differ on every run. These ran without Docker. The first step does not need the earlier parts' instance.

1. Run the store's tests. They run the real SQL on H2 (see `postgresql-and-h2.md`) and include sixteen threads racing for one seat.

```bash
./gradlew --offline cleanTest test --tests '*JdbcHoldStoreTest' 2>&1 | grep -E "FAILED|BUILD"
```

The only line printed is `BUILD SUCCESSFUL`. Thirteen tests ran; leave out the `grep` to see their names.

2. See the connection timeout rough edge. This needs the files from `./gradlew --offline -q stackFiles`, and a settings file as made in part 2 (`build/try/local.properties`).

```bash
sed -e 's#^db.connectTimeoutMillis=.*#db.connectTimeoutMillis=100#' build/try/local.properties > build/try/fast.properties
java -cp 'build/stack/lib/*' io.plainround.seatwave.balancer.seatwave.SeatWaveCli build/try/fast.properties seatwave-local
```

```text
Exception in thread "main" java.lang.IllegalArgumentException: connectionTimeout cannot be less than 250ms
	at com.zaxxer.hikari.HikariConfig.setConnectionTimeout(HikariConfig.java:195)
	at io.plainround.seatwave.balancer.seatwave.DatabasePool.open(DatabasePool.java:48)
	at io.plainround.seatwave.balancer.seatwave.SeatWaveCli.start(SeatWaveCli.java:76)
```

The shell's exit status for this run is 1, not the documented 2.

3. Start the instance with no database (as in part 2), send three holds, and read the metrics. Each request waits for the pool's connection timeout (1,000 ms in this file) before it fails, so all three land in the 2.5 second bucket and the sum is a little over three seconds.

```bash
java -cp 'build/stack/lib/*' io.plainround.seatwave.balancer.seatwave.SeatWaveCli build/try/local.properties seatwave-local > build/try/out.txt 2>&1 &
PID=$!
sleep 3
for i in 1 2 3; do curl -s -o /dev/null -H 'Content-Type: application/json' -H "Idempotency-Key: \"k$i\"" --data '{"eventId":1,"seat":"A-7","customer":"fan-1"}' http://127.0.0.1:22080/v1/holds; done
curl -s http://127.0.0.1:22081/metrics | grep -E 'seconds_(bucket\{le="(1.0|2.5|\+Inf)"\}|sum|count)'
kill -TERM $PID; wait $PID; echo "exit $?"
```

```text
seatwave_request_seconds_bucket{le="1.0"} 0
seatwave_request_seconds_bucket{le="2.5"} 3
seatwave_request_seconds_bucket{le="+Inf"} 3
seatwave_request_seconds_sum 3.104767
seatwave_request_seconds_count 3
exit 143
```

4. Count which lines of the store no test runs. The coverage report is made by the normal build. This command prints the missed line numbers of the store.

```bash
./gradlew --offline -q check
python3 -c "
import xml.etree.ElementTree as ET
t=ET.parse('build/reports/jacoco/test/jacocoTestReport.xml').getroot()
for p in t.findall('package'):
    for sf in p.findall('sourcefile'):
        if sf.get('name')=='JdbcHoldStore.java':
            print([int(l.get('nr')) for l in sf.findall('line') if int(l.get('mi'))>0])
"
```

```text
[98, 99, 100, 101, 105, 106, 107, 108, 109]
```

Those are the lines of the duplicate-key recovery in `hold`.

## Mistakes people make

- **Checking, then changing, in two statements.** `SELECT` the seat, see it free, then `UPDATE` it: two instances can both see "free". Here one `UPDATE` with the rule in its `WHERE` clause does both.
- **Gluing values into SQL text.** `"... WHERE hold_id = '" + id + "'"` allows injection. Every statement here uses `?` marks.
- **Reading a unique violation from the message.** Messages differ between databases. The SQLState `23505` is the same.
- **Forgetting the rollback in PostgreSQL.** After a failed statement the transaction is unusable until it is rolled back; reading before the rollback would fail again.
- **Reading times in the machine's time zone.** The store passes `OffsetDateTime` at UTC and reads `OffsetDateTime` back.
- **A pool that grows with load.** More connections than the database can serve makes everything slower. This pool is fixed, and `ServiceSettings` refuses a pool larger than the worker threads.
- **A settings rule looser than the library's.** See step 2 above.
- **Metrics labels with unbounded values.** The label is the route template, never the path.
- **Closing the pool before the drain ends.** `stop` drains first.

## Check yourself

1. Two instances run the `UPDATE` for the same seat at the same instant. What happens to the second?
   Answer: it waits for the first's row lock; when the first commits, the second re-checks the `WHERE` clause, finds the seat held, changes zero rows, and the store answers `SeatTaken`.
2. A retried request carries the same key as a finished one. What does the store answer, and what is stored again?
   Answer: it finds the key first and answers with the same hold, flagged as a replay. Nothing is written.
3. Why is `isReachable` a boolean and not an exception?
   Answer: "no" is a normal answer for a readiness check.
4. Why does the pool start when the database is down?
   Answer: `setInitializationFailTimeout(-1)` skips the first connection attempt. The instance then starts, its readiness check fails, and the balancer sends it nothing until the database answers.
5. What does exit status 143 mean?
   Answer: the process ended because of SIGTERM (128 plus 15). For this program it is the normal result of a drain started by `docker stop`.

## Further reading

- Java SE 25 API, `java.sql.Connection` (transactions, `setAutoCommit`, `commit`, `rollback`, `isValid`): https://docs.oracle.com/en/java/javase/25/docs/api/java.sql/java/sql/Connection.html
- Java SE 25 API, `java.sql.PreparedStatement`: https://docs.oracle.com/en/java/javase/25/docs/api/java.sql/java/sql/PreparedStatement.html
- PostgreSQL 18 documentation, transaction isolation (READ COMMITTED): https://www.postgresql.org/docs/18/transaction-iso.html
- PostgreSQL 18 documentation, error codes (class 23, `23505`): https://www.postgresql.org/docs/18/errcodes-appendix.html
- PostgreSQL 18 documentation, `UPDATE`: https://www.postgresql.org/docs/18/sql-update.html
- HikariCP README and configuration reference: https://github.com/brettwooldridge/HikariCP
- Prometheus, metric types and the text exposition format: https://prometheus.io/docs/concepts/metric_types/ and https://prometheus.io/docs/instrumenting/exposition_formats/
- Java SE 25 API, `Runtime.addShutdownHook`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Runtime.html
