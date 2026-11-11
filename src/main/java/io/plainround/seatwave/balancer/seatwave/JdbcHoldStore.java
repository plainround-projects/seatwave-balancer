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

  // Module 1's hold: 300 seconds.
  static final Duration HOLD_DURATION = Duration.ofSeconds(300);
  // The SQL standard's class 23, "integrity constraint violation", code 505 "unique violation";
  // PostgreSQL and H2 both report it for a duplicate key.
  private static final String UNIQUE_VIOLATION = "23505";

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
  // The WHERE clause is the whole rule: free, or held with an expired hold.
  private static final String TAKE_SEAT =
      """
      UPDATE seats SET status = 'HELD', hold_id = ?, hold_customer = ?, hold_expires_at = ?
      WHERE event_id = ? AND seat_label = ?
        AND (status = 'AVAILABLE' OR (status = 'HELD' AND hold_expires_at <= ?))\
      """;
  private static final String RECORD_HOLD =
      """
      INSERT INTO holds (hold_id, idempotency_key, body_sha256, event_id, seat_label, customer,
                         expires_at)
      VALUES (?, ?, ?, ?, ?, ?, ?)\
      """;
  private static final String SEAT_EXISTS =
      "SELECT 1 FROM seats WHERE event_id = ? AND seat_label = ?";

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
    } catch (SQLException failure) {
      throw new StoreUnavailableException("holding seat " + command.seat() + " failed", failure);
    }
  }

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

  @Override
  public boolean isReachable() {
    try (Connection connection = dataSource.getConnection()) {
      return connection.isValid(reachableTimeoutSeconds);
    } catch (SQLException unreachable) {
      // The readiness check's whole job is to answer "no" here; the pool logs the cause itself.
      return false;
    }
  }

  private Result<GrantedHold, HoldRefusal> holdInTransaction(
      Connection connection, HoldCommand command) throws SQLException {
    Optional<StoredHold> earlier = findByKey(connection, command.idempotencyKey());
    if (earlier.isPresent()) {
      return replay(earlier.get(), command);
    }
    // Milliseconds: both databases keep microseconds, so a replay read back from the table must
    // print the same expiry as the first answer did.
    Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    Hold hold =
        new Hold(
            UUID.randomUUID().toString(),
            command.eventId(),
            command.seat(),
            command.customer(),
            now.plus(HOLD_DURATION));
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
    recordHold(connection, hold, command);
    return Result.success(new GrantedHold(hold, false));
  }

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

  private static Result<GrantedHold, HoldRefusal> replay(StoredHold stored, HoldCommand command) {
    // Module 7: the same key with a different request is refused, never answered with the other
    // request's hold.
    return stored.fingerprint().equals(command.fingerprint())
        ? Result.success(new GrantedHold(stored.hold(), true))
        : Result.error(new HoldRefusal.KeyReused());
  }

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

  /** A hold with the fingerprint of the request that made it. */
  private record StoredHold(Hold hold, String fingerprint) {}
}
