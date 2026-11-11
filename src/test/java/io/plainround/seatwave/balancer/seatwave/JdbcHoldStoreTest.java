package io.plainround.seatwave.balancer.seatwave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcHoldStoreTest {

  private static final Instant START = Instant.parse("2026-11-11T09:00:00Z");
  private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(1);
  private static final String SHA_A = "a".repeat(64);
  private static final String SHA_B = "b".repeat(64);
  private static final int RACERS = 16;
  private static final long WAIT_SECONDS = 30;

  private JdbcDataSource dataSource;
  private MutableClock clock;
  private JdbcHoldStore store;

  @BeforeEach
  void freshDatabase() {
    dataSource = TestDatabase.create();
    clock = new MutableClock(START);
    store = new JdbcHoldStore(dataSource, clock, CHECK_TIMEOUT);
  }

  @Test
  void grantsAFreeSeatForThreeHundredSeconds() throws Exception {
    GrantedHold granted = granted(store.hold(command("\"k-1\"", SHA_A, "A-7")));

    assertThat(granted.isReplay()).isFalse();
    assertThat(granted.hold().seat()).isEqualTo("A-7");
    assertThat(granted.hold().expiresAt()).isEqualTo(START.plusSeconds(300));
  }

  @Test
  void refusesASeatAnotherActiveHoldOwns() throws Exception {
    store.hold(command("\"k-1\"", SHA_A, "A-7"));

    Result<GrantedHold, HoldRefusal> second = store.hold(command("\"k-2\"", SHA_B, "A-7"));

    assertThat(second).isEqualTo(Result.error(new HoldRefusal.SeatTaken()));
  }

  @Test
  void grantsTheSeatAgainOnceTheHoldHasExpired() throws Exception {
    store.hold(command("\"k-1\"", SHA_A, "A-7"));
    clock.advance(Duration.ofSeconds(300));

    GrantedHold again = granted(store.hold(command("\"k-2\"", SHA_B, "A-7")));

    assertThat(again.isReplay()).isFalse();
  }

  @Test
  void replaysTheSameHoldForARetriedKey() throws Exception {
    GrantedHold first = granted(store.hold(command("\"k-1\"", SHA_A, "A-7")));

    GrantedHold retry = granted(store.hold(command("\"k-1\"", SHA_A, "A-7")));

    assertThat(retry.isReplay()).isTrue();
    assertThat(retry.hold()).isEqualTo(first.hold());
  }

  @Test
  void refusesAKeyReusedForAnotherRequest() throws Exception {
    store.hold(command("\"k-1\"", SHA_A, "A-7"));

    Result<GrantedHold, HoldRefusal> reused = store.hold(command("\"k-1\"", SHA_B, "A-8"));

    assertThat(reused).isEqualTo(Result.error(new HoldRefusal.KeyReused()));
  }

  @Test
  void reportsASeatTheDatabaseDoesNotHave() throws Exception {
    Result<GrantedHold, HoldRefusal> missing =
        store.hold(new HoldCommand("\"k-1\"", SHA_A, 2, "A-7", "fan-1"));

    assertThat(missing).isEqualTo(Result.error(new HoldRefusal.NoSuchSeat()));
  }

  @Test
  void findsAHoldByItsIdentifier() throws Exception {
    GrantedHold granted = granted(store.hold(command("\"k-1\"", SHA_A, "A-7")));

    assertThat(store.find(granted.hold().holdId())).contains(granted.hold());
    assertThat(store.find("00000000-0000-0000-0000-000000000000")).isEmpty();
  }

  @Test
  void letsExactlyOneOfManyRacersHoldOneSeat() throws Exception {
    List<Result<GrantedHold, HoldRefusal>> outcomes =
        race(racer -> command("\"race-" + racer + "\"", SHA_A, "B-1"));

    long winners =
        outcomes.stream().filter(outcome -> outcome instanceof Result.Success<?, ?>).count();
    assertThat(winners).isEqualTo(1);
    assertThat(outcomes)
        .filteredOn(outcome -> outcome instanceof Result.Error<?, ?>)
        .allMatch(outcome -> outcome.equals(Result.error(new HoldRefusal.SeatTaken())));
  }

  @Test
  void answersRacingRetriesOfOneKeyWithOneHold() throws Exception {
    List<Result<GrantedHold, HoldRefusal>> outcomes =
        race(racer -> command("\"same-key\"", SHA_A, "C-1"));

    Set<String> holdIds = new HashSet<>();
    for (Result<GrantedHold, HoldRefusal> outcome : outcomes) {
      holdIds.add(granted(outcome).hold().holdId());
    }
    assertThat(holdIds).hasSize(1);
  }

  @Test
  void answersTheLoserOfAKeyRaceWithTheWinnersHoldAndReleasesItsSeat() throws Exception {
    // Two requests with one key take two different seats, so no row lock orders them; both reach
    // the INSERT, and the unique key refuses the second (SQLState 23505). The same fingerprint
    // makes the loser a retry of the winner, so it must answer the winner's hold.
    AtomicInteger rollbacks = new AtomicInteger();
    JdbcHoldStore racing =
        new JdbcHoldStore(
            JdbcStandIns.meetingAtTheInsert(dataSource, 2, rollbacks), clock, CHECK_TIMEOUT);

    List<Result<GrantedHold, HoldRefusal>> outcomes =
        race(racing, 2, racer -> command("\"one-key\"", SHA_A, racer == 0 ? "D-1" : "D-2"));

    GrantedHold first = granted(outcomes.get(0));
    GrantedHold second = granted(outcomes.get(1));
    assertThat(first.hold().holdId()).isEqualTo(second.hold().holdId());
    assertThat(List.of(first.isReplay(), second.isReplay())).containsExactlyInAnyOrder(true, false);
    assertThat(rollbacks.get()).isEqualTo(1);
    assertThat(heldSeats()).isEqualTo(1);
  }

  @Test
  void refusesTheLoserOfAKeyRaceWithAnotherBodyAndHoldsOneSeat() throws Exception {
    AtomicInteger rollbacks = new AtomicInteger();
    JdbcHoldStore racing =
        new JdbcHoldStore(
            JdbcStandIns.meetingAtTheInsert(dataSource, 2, rollbacks), clock, CHECK_TIMEOUT);

    List<Result<GrantedHold, HoldRefusal>> outcomes =
        race(
            racing,
            2,
            racer ->
                command("\"one-key\"", racer == 0 ? SHA_A : SHA_B, racer == 0 ? "E-1" : "E-2"));

    assertThat(outcomes).filteredOn(outcome -> outcome instanceof Result.Success<?, ?>).hasSize(1);
    assertThat(outcomes).contains(Result.error(new HoldRefusal.KeyReused()));
    assertThat(rollbacks.get()).isEqualTo(1);
    assertThat(heldSeats()).isEqualTo(1);
  }

  @Test
  void saysTheDatabaseIsReachableWhenItAnswers() {
    assertThat(store.isReachable()).isTrue();
  }

  @Test
  void saysTheDatabaseIsUnreachableWhenNoConnectionCanBeHad() {
    JdbcHoldStore broken = new JdbcHoldStore(unreachable(), clock, CHECK_TIMEOUT);

    assertThat(broken.isReachable()).isFalse();
  }

  @Test
  void turnsADatabaseFailureIntoStoreUnavailable() {
    JdbcHoldStore broken = new JdbcHoldStore(unreachable(), clock, CHECK_TIMEOUT);

    assertThatThrownBy(() -> broken.hold(command("\"k-1\"", SHA_A, "A-7")))
        .isInstanceOf(StoreUnavailableException.class)
        .hasMessageContaining("A-7");
    assertThatThrownBy(() -> broken.find("00000000-0000-0000-0000-000000000000"))
        .isInstanceOf(StoreUnavailableException.class);
  }

  @Test
  void rejectsAReadinessTimeoutUnderOneSecond() {
    assertThatThrownBy(() -> new JdbcHoldStore(dataSource, clock, Duration.ofMillis(999)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least 1 s");
  }

  private List<Result<GrantedHold, HoldRefusal>> race(RacerCommand commands) throws Exception {
    return race(store, RACERS, commands);
  }

  private static List<Result<GrantedHold, HoldRefusal>> race(
      JdbcHoldStore store, int racers, RacerCommand commands) throws Exception {
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Result<GrantedHold, HoldRefusal>>> futures = new ArrayList<>();
    try (ExecutorService threads = Executors.newFixedThreadPool(racers)) {
      for (int racer = 0; racer < racers; racer++) {
        HoldCommand command = commands.forRacer(racer);
        Callable<Result<GrantedHold, HoldRefusal>> attempt =
            () -> {
              go.await();
              return store.hold(command);
            };
        futures.add(threads.submit(attempt));
      }
      go.countDown();
      List<Result<GrantedHold, HoldRefusal>> outcomes = new ArrayList<>();
      for (Future<Result<GrantedHold, HoldRefusal>> future : futures) {
        outcomes.add(future.get(WAIT_SECONDS, TimeUnit.SECONDS));
      }
      return outcomes;
    }
  }

  private static HoldCommand command(String key, String fingerprint, String seat) {
    return new HoldCommand(key, fingerprint, 1, seat, "fan-1");
  }

  private static GrantedHold granted(Result<GrantedHold, HoldRefusal> outcome) {
    return switch (outcome) {
      case Result.Success<GrantedHold, HoldRefusal>(GrantedHold granted) -> granted;
      case Result.Error<GrantedHold, HoldRefusal>(HoldRefusal refusal) ->
          throw new AssertionError("expected a granted hold, got " + refusal);
    };
  }

  /** Makes one racer's command. */
  @FunctionalInterface
  private interface RacerCommand {
    HoldCommand forRacer(int racer);
  }

  private long heldSeats() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery("SELECT COUNT(*) FROM seats WHERE status = 'HELD'")) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private static DataSource unreachable() {
    return JdbcStandIns.unreachable();
  }
}
