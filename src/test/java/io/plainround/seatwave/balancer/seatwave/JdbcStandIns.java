package io.plainround.seatwave.balancer.seatwave;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

/**
 * Data sources for the tests of {@link JdbcHoldStore} that a real database cannot give on demand:
 * one that never connects, and one that holds every racer at the INSERT of a hold until all have
 * reached it, so a race between two requests with one key happens every time, not by chance.
 */
final class JdbcStandIns {

  private static final long BARRIER_SECONDS = 10;

  private JdbcStandIns() {}

  /**
   * A data source whose every connection attempt fails, as a database that is down does.
   *
   * @return the data source
   */
  static DataSource unreachable() {
    return (DataSource)
        Proxy.newProxyInstance(
            JdbcStandIns.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("getConnection")) {
                throw new SQLException("the test database is down", "08001");
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }

  /**
   * Wraps a data source so that the INSERT of a hold waits until {@code racers} connections have
   * reached it, and counts rollbacks.
   *
   * @param real the database
   * @param racers how many connections must meet at the INSERT
   * @param rollbacks counts every rollback on every connection
   * @return the wrapped data source
   */
  static DataSource meetingAtTheInsert(DataSource real, int racers, AtomicInteger rollbacks) {
    CyclicBarrier barrier = new CyclicBarrier(racers);
    return (DataSource)
        Proxy.newProxyInstance(
            JdbcStandIns.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, arguments) -> {
              Object result = invoke(real, method, arguments);
              return method.getName().equals("getConnection")
                  ? wrapped((Connection) result, barrier, rollbacks)
                  : result;
            });
  }

  private static Connection wrapped(
      Connection real, CyclicBarrier barrier, AtomicInteger rollbacks) {
    InvocationHandler handler =
        (proxy, method, arguments) -> {
          if (method.getName().equals("prepareStatement")
              && ((String) arguments[0]).startsWith("INSERT INTO holds")) {
            meet(barrier);
          }
          if (method.getName().equals("rollback")) {
            rollbacks.incrementAndGet();
          }
          return invoke(real, method, arguments);
        };
    return (Connection)
        Proxy.newProxyInstance(
            JdbcStandIns.class.getClassLoader(), new Class<?>[] {Connection.class}, handler);
  }

  private static void meet(CyclicBarrier barrier) throws SQLException {
    try {
      barrier.await(BARRIER_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new SQLException("interrupted at the test barrier", interrupted);
    } catch (BrokenBarrierException | TimeoutException notMet) {
      throw new SQLException("the racers never met at the INSERT", notMet);
    }
  }

  private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
    try {
      return method.invoke(target, arguments);
    } catch (InvocationTargetException thrown) {
      // The real exception, such as the database's own SQLException, not the reflection wrapper.
      throw thrown.getCause();
    }
  }
}
