package io.plainround.seatwave.balancer.seatwave;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;

/**
 * A fresh in-memory H2 database in its PostgreSQL mode, made from the project's own db/schema.sql,
 * the file PostgreSQL runs in the stack. One per test, named at random, so tests never share rows.
 */
final class TestDatabase {

  private TestDatabase() {}

  static JdbcDataSource create() {
    JdbcDataSource dataSource = new JdbcDataSource();
    // DB_CLOSE_DELAY=-1 keeps the database while no connection is open; the name keeps tests apart.
    dataSource.setURL(
        "jdbc:h2:mem:seatwave-"
            + UUID.randomUUID()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String sql : statements()) {
        statement.execute(sql);
      }
    } catch (SQLException | IOException failure) {
      throw new IllegalStateException("the test database could not be made", failure);
    }
    return dataSource;
  }

  private static String[] statements() throws IOException {
    String schema =
        Files.readString(Path.of(System.getProperty("seatwave.schema")), StandardCharsets.UTF_8);
    // Comment lines go first, then the file splits on the semicolons that end its statements.
    String withoutComments =
        schema
            .lines()
            .filter(line -> !line.startsWith("--"))
            .reduce("", (all, line) -> all + line + "\n");
    return Arrays.stream(withoutComments.split(";"))
        .map(String::strip)
        .filter(sql -> !sql.isEmpty())
        .toArray(String[]::new);
  }
}
