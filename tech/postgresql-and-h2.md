# PostgreSQL, H2 and JDBC: the shared store

## What it is

Three names that go together.

- **PostgreSQL** is a database **server**: a program that runs all the time, owns a folder of data files, listens on a network port (5432 by default) and answers requests in SQL. Other programs connect to it, send SQL and read the results. It is free, open source, and widely used.
- **H2** is a database **library** written in Java. It runs inside the Java program that uses it, so there is no server to start and no network. It can keep its data only in memory and throw it away when the program ends. This project uses it only in tests.
- **JDBC** (Java Database Connectivity) is the standard set of Java interfaces for talking to any relational database: `Connection`, `Statement`, `PreparedStatement`, `ResultSet`. A **JDBC driver** is the library that implements those interfaces for one database. PostgreSQL's driver speaks PostgreSQL's network protocol. H2's driver is H2 itself.

A **JDBC URL** tells JDBC which database to use. Its parts are separated by colons and slashes:

- `jdbc:postgresql://10.210.10.5:5432/seatwave`: use the PostgreSQL driver, connect to the machine `10.210.10.5` on port 5432, and open the database named `seatwave`.
- `jdbc:h2:mem:seatwave-1234;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`: use H2, keep it in memory (`mem`) under the name `seatwave-1234`, and apply two options after the semicolons.

Other terms: a **transaction** is a group of statements that succeed or fail together; **isolation** is how much one transaction sees of the others; a **row lock** makes a second writer of the same row wait (see `java-seatwave-walkthrough-3.md`, which uses all three).

## Why this repository uses it

**Why the holds moved out of memory.** In Module 9 there was one SeatWave backend, and it kept its holds in a table inside its own memory (the class `HoldLedger`). One process, one table: nothing could go wrong. Module 10 runs three instances. If each kept its own holds, a fan could hold seat A-7 through instance 1 and another fan could hold the same seat through instance 2, and a `GET` of a hold would only work on the instance that made it. The balancer sends requests wherever it likes, so the answer must not depend on where. The fix is to take every piece of state out of the instance and put it in one place that all instances share. That place is PostgreSQL.

A price comes with the fix. The database is now a single point of failure: if it stops, every instance fails its readiness check and the balancer has nowhere to send traffic. The README names this and leaves it to later modules (13 to 19).

**Why PostgreSQL.** The architecture note says it: "PostgreSQL is the database later modules use." It has row locks, transactions and a unique-constraint error code that is exactly what this module needs, and it is the database a real service of this kind would run.

**Why H2 in the tests.** `./gradlew check` must run on a machine without Docker. The tests run the same `db/schema.sql` on H2 in its PostgreSQL mode, so the store's real SQL runs on every build. The rules that only a real PostgreSQL can prove are checked by the stack tests (`StackTest`, run with `./gradlew stackTest` against the running Compose stack; see `tests-stack-walkthrough.md`).

## Where it appears

| File | What it does with the database |
|---|---|
| `db/schema.sql` | The tables and the 20,000 seats (see `sql-schema.md`). |
| `compose.yaml` | The `postgres` service: image, user, database name, password file, schema mount, health check, fixed address `10.210.10.5`. |
| `config/seatwave-container.properties` | `db.url`, `db.user`, `db.passwordFile`, `db.poolSize`, `db.connectTimeoutMillis`. |
| `seatwave/ServiceSettings.java` | Accepts only two kinds of JDBC URL: PostgreSQL and in-memory H2. |
| `seatwave/DatabasePool.java` | Opens the connection pool (see `hikaricp.md`). |
| `seatwave/JdbcHoldStore.java` | All SQL. Depends on the `DataSource` interface, not on a database. |
| `seatwave/TestDatabase.java` | Makes a fresh H2 database from `db/schema.sql` for each test. |
| `build.gradle.kts` | `runtimeOnly(libs.postgresql)` (the driver is needed only when the program runs) and `testImplementation(libs.h2)`. |
| `gradle/libs.versions.toml` | Pins `postgresql` 42.7.14 and `h2` 2.5.252. |

## How it works underneath

**PostgreSQL as a server.** The image `postgres:18.6` starts one main process, which starts a helper process for each client connection. A client opens a TCP connection, sends the user and database names and a password, and then sends SQL text. The server parses it, runs it, and sends rows back. Every connection costs the server memory and a process, which is why this project uses a small fixed pool of connections per instance: 16 each, 48 in all, under PostgreSQL's default limit of 100.

**Transactions and locks.** `JdbcHoldStore.hold` switches autocommit off, so its statements form one transaction. When the `UPDATE` changes a seat's row, PostgreSQL locks that row until commit or rollback. A second transaction that wants the same row waits, and when it gets its turn it re-reads the row as committed and applies its `WHERE` clause to the new values. This is how READ COMMITTED, the default, makes "check and set" one step. The isolation level is not set anywhere in the code; both databases use it by default, and the class comment says so.

**H2 pretending to be PostgreSQL.** H2 can accept much of PostgreSQL's way of writing SQL. `MODE=PostgreSQL` turns that on. `DATABASE_TO_LOWER=TRUE` makes H2 store unquoted names (`seats`) in lower case, as PostgreSQL does, and not in upper case, which H2 does by default. `DB_CLOSE_DELAY=-1` keeps an in-memory database alive while no connection is open, because H2 would otherwise delete it when the last connection closes. A random name per test keeps tests apart.

What H2 does **not** prove: its row locking is its own implementation, its error messages differ, and its performance has no bearing on PostgreSQL's. What it does prove is that the SQL is valid, that the Java code maps the results correctly, and that the rule "one winner among sixteen racing threads" holds on a database with READ COMMITTED row locking. Both databases report a duplicate key with the same SQLState `23505`, which the store reads.

## Walkthrough of this repository's use

### The `postgres` service in `compose.yaml`

These lines belong to the stack notes (`docker-compose.md`); they are read here for what they say about the database.

```text
  postgres:
    <<: *hardening
    image: postgres:18.6@sha256:e3d24f6906d0760bf4da0dae872415391ededefad23fd471c47dc53b0ddf3c42
    environment:
      POSTGRES_USER: seatwave
      POSTGRES_DB: seatwave
      # The password is a file generated at run time into build/secrets (scripts/stack.sh).
      POSTGRES_PASSWORD_FILE: /run/secrets/db_password
    secrets:
      - db_password
    # The entrypoint starts as root to own the data folder, then runs PostgreSQL as its own user.
```

- `image: postgres:18.6@sha256:...` pins the exact image by version and by content hash. `InfrastructureRulesTest.pinsEveryImageByTagAndDigest` checks it.
- `POSTGRES_USER` and `POSTGRES_DB` tell the image's start-up script to create the user `seatwave` and the database `seatwave` on first start.
- `POSTGRES_PASSWORD_FILE` points to a file with the password, in the container at `/run/secrets/db_password`. The comment says where it comes from: a file generated at run time into `build/secrets` by `scripts/stack.sh`. The password is never in a file that is committed.
- `secrets:` mounts that file into the container.

```text
    volumes:
      - pgdata:/var/lib/postgresql
      # Runs once, when the data volume is new: the tables and Module 1's 20,000 seats.
      - ./db/schema.sql:/docker-entrypoint-initdb.d/schema.sql:ro
    healthcheck:
      test: ["CMD", "pg_isready", "-U", "seatwave", "-d", "seatwave"]
      interval: 2s
      timeout: 2s
      retries: 30
    networks:
      lab:
        ipv4_address: 10.210.10.5
```

- `pgdata:/var/lib/postgresql` keeps the data in a named volume, so it survives a container restart.
- The `schema.sql` line mounts `db/schema.sql` into `/docker-entrypoint-initdb.d`, read-only (`:ro`). The comment: it "runs once, when the data volume is new: the tables and Module 1's 20,000 seats".
- The `healthcheck` runs `pg_isready` every 2 seconds. Compose starts the SeatWave instances only after it passes (`depends_on: condition: service_healthy`), so an instance never starts before its database can answer.
- The service has the fixed address `10.210.10.5` on the lab network. That is the address in `db.url`.

### The URL rule in `ServiceSettings`

```java
  // Only the two databases this project runs: PostgreSQL in the stack, H2 in a reader's test.
  private static final Pattern JDBC_URL =
      Pattern.compile("jdbc:(postgresql://[A-Za-z0-9.-]{1,100}:[0-9]{1,5}/[a-z_]{1,32}|h2:mem:.*)");
```

The first line is a comment. The pattern accepts only two forms, joined by `|` (or): `jdbc:postgresql://host:port/name` with a host of letters, digits, dots and hyphens, a port of one to five digits and a name of lowercase letters and underscores; and `jdbc:h2:mem:` followed by anything. Any other URL, such as `jdbc:mysql://...`, is a problem, which `ServiceSettingsTest.refusesAJdbcUrlForAnotherDatabase` checks.

### The test database in `TestDatabase`

```java
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
```

- `JdbcDataSource` is H2's own implementation of the `DataSource` interface. It opens a new plain connection each time; there is no pool in the tests.
- The URL is `jdbc:h2:mem:seatwave-` plus a random UUID, then the three options. The comment says: `DB_CLOSE_DELAY=-1` "keeps the database while no connection is open; the name keeps tests apart".
- The `try (Connection ...; Statement ...)` runs each statement of the schema in turn. Any failure becomes `IllegalStateException("the test database could not be made")`.

```java
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
```

`statements()` turns the file into a list of statements. It reads the file named by the system property `seatwave.schema` (Gradle sets it to the absolute path of `db/schema.sql`), drops every line that starts with `--`, joins the rest and cuts at each `;`. Blank pieces are dropped. As the other notes warn, this works because no statement has a semicolon inside a text value.

Every test that needs a database therefore starts with 20,000 free seats and nothing held.

## Try it yourself

All commands run in the project folder. There is no PostgreSQL on the machine used for these notes, so nothing here ran against PostgreSQL itself; the Compose stack was not run for these notes either. Timings differ on every run.

1. Run the store's tests, which run the real SQL on H2.

```bash
./gradlew --offline cleanTest test --tests '*JdbcHoldStoreTest' 2>&1 | grep -E "FAILED|BUILD"
```

The only line printed is `BUILD SUCCESSFUL`.

2. See what `DATABASE_TO_LOWER=TRUE` does. The same schema is loaded three times with different URL options, and the table names are listed. (The `find` locates H2's jar in Gradle's cache.)

```bash
mkdir -p build/h2
H2=$(find ~/.gradle -name 'h2-2.5.252.jar' | head -1)
for opts in "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE" "MODE=PostgreSQL" ""; do
  echo "=== options: [$opts]"
  U="jdbc:h2:./build/h2/t$RANDOM;$opts"
  java -cp "$H2" org.h2.tools.RunScript -url "$U" -script db/schema.sql
  java -cp "$H2" org.h2.tools.Shell -url "$U" -sql "SELECT table_name FROM information_schema.tables WHERE table_schema ILIKE 'public' ORDER BY 1"
done
rm -rf build/h2
```

```text
=== options: [MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE]
table_name
events
holds
seats
(3 rows, 20 ms)
=== options: [MODE=PostgreSQL]
TABLE_NAME
EVENTS
HOLDS
SEATS
(3 rows, 20 ms)
=== options: []
TABLE_NAME
EVENTS
HOLDS
SEATS
(3 rows, 16 ms)
```

Only the first run names the tables as PostgreSQL would. Note that the schema also loaded in plain H2 mode; the options matter for names and for behavior at the edges, not for this file's syntax.

3. Run the whole test suite, which makes a fresh database for each store, server and route test.

```bash
./gradlew --offline cleanTest test 2>&1 | grep -cE "PASSED"
```

It prints `152`, the number of tests of version 1.1.0 (I ran it in a scratch copy on 11 November 2026).

4. Ask the instance for readiness with a database that is not there (the steps are in `java-seatwave-walkthrough-2.md`). `/health` answers `200` and `/ready` answers `503` with `database-unreachable`. The pool starts without the database, which is the property this module needs from it.

## Mistakes people make

- **Keeping state in the process.** The classic way to break a scaled-out service. Here the instance holds no hold in memory.
- **Believing a test on a different database proves everything.** H2's row locking is not PostgreSQL's. This project uses H2 for the logic and the real database for the stack tests; it says so.
- **Running a schema file twice.** PostgreSQL's start-up folder runs files once per volume. The tables use `CREATE TABLE` without `IF NOT EXISTS`, so a second run would fail on "already exists".
- **Mixing time zones.** The code passes `OffsetDateTime` values in UTC. A plain `java.sql.Timestamp` is read in the JVM's zone.
- **Opening a connection per request.** Each costs a network round trip and a server process. A pool lends connections instead.
- **Too many connections.** 16 per instance times three is 48, under PostgreSQL's default 100. The settings refuse a pool above 100 for one instance.
- **Putting a password in the settings file.** The settings name a file instead, and Compose mounts it as a secret.

## Check yourself

1. Why did the holds have to leave the instance's memory in Module 10?
   Answer: because three instances answer requests and the balancer sends each request to any of them; a hold kept in one instance's memory would be missing on the others.
2. What is the difference between PostgreSQL and H2 as the project uses them?
   Answer: PostgreSQL is a separate server in the Compose stack, shared by all instances. H2 is a library inside the test process, in memory, used so that `./gradlew check` needs no Docker.
3. What does `DB_CLOSE_DELAY=-1` do?
   Answer: it keeps an in-memory H2 database alive while no connection is open, so it is not deleted between the schema load and the test.
4. Which error code does the store read to recognise a duplicate idempotency key, and why is it reliable across both databases?
   Answer: SQLState `23505`, the SQL standard's code for a unique violation; both PostgreSQL and H2 report it.
5. When does PostgreSQL run `db/schema.sql`?
   Answer: once, when its data volume is new.

## Further reading

- PostgreSQL 18 documentation, transaction isolation: https://www.postgresql.org/docs/18/transaction-iso.html
- PostgreSQL 18 documentation, explicit locking (row-level locks): https://www.postgresql.org/docs/18/explicit-locking.html
- PostgreSQL 18 documentation, error codes: https://www.postgresql.org/docs/18/errcodes-appendix.html
- PostgreSQL JDBC driver documentation (42.7): https://jdbc.postgresql.org/documentation/
- H2 2.5 documentation, compatibility modes and database URL options: https://h2database.com/html/features.html
- Java SE 25 API, package `java.sql` and `javax.sql.DataSource`: https://docs.oracle.com/en/java/javase/25/docs/api/java.sql/java/sql/package-summary.html
- The PostgreSQL image on Docker Hub (`POSTGRES_PASSWORD_FILE`, initialization scripts): https://hub.docker.com/_/postgres
