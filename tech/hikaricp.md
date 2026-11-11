# HikariCP and connection pools, from zero

## What it is

A **database connection** is an open line between a program and a database. Opening one is slow: the program makes a network connection, the database starts work for it, and the two exchange a user name, a password and some settings. That can cost milliseconds or more, and the database can only hold a limited number of connections at once.

A **connection pool** solves this. It opens a few connections early and keeps them. When code needs the database, it **borrows** a connection from the pool, uses it, and **gives it back** by calling `close()`. The connection is not really closed: it goes back to the pool for the next request. **HikariCP** (version 7.1.0 here) is a connection pool library for Java. Its name is the Japanese word for "light", and it is known for being small and fast.

Terms you need.

- The pool has a **size**: the number of connections it keeps. A pool of 16 lets at most 16 requests talk to the database at the same moment. A 17th request **waits** until one comes back.
- A **timeout** is the longest a thing may wait. If a request waits longer than the connection timeout, the pool gives up and throws an error.
- A **DataSource** is the Java interface for "something that gives me connections". A `HikariDataSource` is a pool that implements it.
- **Autocommit** is a connection mode in which every statement is its own transaction. `JdbcHoldStore` turns it off to group several statements into one transaction.
- **Validation** means checking that a borrowed connection still works, because the database or a network device may have closed it during a long idle time.

## Why this repository uses it

Three SeatWave instances, each running up to 64 request threads, share one PostgreSQL. Without a pool, a request would open its own connection. Under load that makes thousands of connections, far over PostgreSQL's default limit of 100, and every one costs time. With a pool:

- The number of connections the database sees is fixed: 16 per instance, 48 for three. The number does not rise when traffic does.
- A request borrows a connection in microseconds when one is free.
- When all 16 are busy, a request waits at most one second (`db.connectTimeoutMillis=1000`) and then fails fast with a `503`, which the balancer can retry elsewhere. A fast failure is better than an instance that holds a thread for 30 seconds, which is HikariCP's own default wait.

## Where it appears

| File | What it does with the pool |
|---|---|
| `seatwave/DatabasePool.java` | Creates the `HikariDataSource` from the settings (see `java-seatwave-walkthrough-3.md`). |
| `seatwave/SeatWaveCli.java` | Calls `DatabasePool.open` at start and `pool.close()` at stop. |
| `seatwave/ServiceSettings.java` | Reads and checks `db.poolSize`, `db.connectTimeoutMillis`, `readiness.checkTimeoutMillis`. |
| `seatwave/JdbcHoldStore.java` | Borrows a connection per call; knows only the `DataSource` interface. |
| `config/seatwave-container.properties` | The shipped numbers: pool 16, connect timeout 1000 ms, readiness check 1000 ms. |
| `src/main/resources/logback.xml` | Keeps Hikari's own `INFO` lines (pool start and stop) and drops its `DEBUG` chatter. |
| `gradle/libs.versions.toml`, `build.gradle.kts` | Pin `com.zaxxer:HikariCP` 7.1.0 as an `implementation` dependency. |

## How it works underneath

**Inside the pool.** The pool keeps a list of connections and marks each one *idle* (free) or *in use*. `getConnection()` returns an idle one at once. If none is idle and the pool is not full, it opens a new one. If it is full, the caller waits for a connection to be returned, up to the connection timeout. The object it hands back is a thin **proxy** around the real connection. The proxy notices when code changes the connection's mode, for example turning autocommit off, and **resets** it when the caller closes the proxy. So the next borrower always gets a connection in the normal state. I checked this: after `setAutoCommit(false)` and `close()`, the next borrow of the same connection reports `true` (see Try it yourself).

**A fixed pool.** HikariCP has two sizes: `maximumPoolSize`, the most connections, and `minimumIdle`, the fewest idle ones it tries to keep. By default `minimumIdle` equals the maximum, which makes the pool a fixed size. `DatabasePool` sets both explicitly to the same number. HikariCP's documentation recommends exactly this for the best response to sudden load.

**Housekeeping.** A background thread retires connections after `maxLifetime` (30 minutes by default) and replaces them, so no connection lives so long that a network device closes it silently. In a fixed pool, retired connections are replaced one at a time with a little random spread.

**Starting without the database.** By default, the pool tries to open one connection at start and throws an error if it cannot (`initializationFailTimeout` is 1 by default, which means: try once, and throw if it fails). A negative value skips that attempt. The pool then starts at once and keeps trying to fill itself in the background. Callers that borrow a connection while the database is down get an error after the connection timeout. This module uses that: an instance whose database is down starts, says "not ready" and waits.

## Walkthrough of this repository's use

### `DatabasePool.open`

The method is quoted in full in `java-seatwave-walkthrough-3.md`. Here only the lines that touch the pool.

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
```

Line by line.

1. `HikariConfig config = new HikariConfig();` makes an empty configuration with HikariCP's defaults.
2. `setJdbcUrl(...)`, `setUsername(...)`, `setPassword(password)`: where to connect and as whom. The URL comes from `db.url`; the password was read from the file.
3. `setPoolName(instanceName)`: names the pool `seatwave-1`. The name appears in Hikari's log lines (`seatwave-1 - Starting...`) and in its error messages, so with three instances writing logs, you can tell them apart.
4. `setMaximumPoolSize(settings.poolSize())` and `setMinimumIdle(settings.poolSize())`: the same number, 16 in the shipped file. A fixed pool. The comment: "the number of connections the database sees never changes under load, which is what HikariCP's own sizing advice recommends".
5. `setConnectionTimeout(settings.connectTimeout().toMillis())`: how long a borrower waits. 1000 ms in the shipped file. HikariCP's default is 30,000 ms.
6. `setValidationTimeout(settings.checkTimeout().toMillis())`: how long Hikari waits when it tests that a connection still works. The shipped file has 1000 ms (from `readiness.checkTimeoutMillis`); the default is 5,000 ms.
7. `setInitializationFailTimeout(-1)`: start without the database.
8. `return new HikariDataSource(config);` builds the pool. It logs `seatwave-1 - Starting...` and `seatwave-1 - Start completed.`

Everything not set keeps HikariCP's default. The three that matter here: `maxLifetime` 30 minutes, `keepaliveTime` 2 minutes (a periodic test of idle connections), and `autoCommit` true.

HikariCP refuses some values. A connection timeout below 250 ms is an error: `IllegalArgumentException: connectionTimeout cannot be less than 250ms`. A validation timeout below 250 ms is refused the same way. `ServiceSettings` keeps both inside those limits. It accepts `db.connectTimeoutMillis` from 250 (`MIN_CONNECT_MILLIS`, whose comment names `HikariConfig` 7.1.0 as the source of the floor) and `readiness.checkTimeoutMillis` from 1,000. A file with `db.connectTimeoutMillis=100` is refused with one problem line, `db.connectTimeoutMillis must be from 250 to 600000, but was 100`, and exit status 2. Version 1.0.0 accepted it from 1 ms and crashed at start with a stack trace and exit status 1. `ServiceSettingsTest.opensThePoolWithTheShortestConnectTimeoutTheSettingsAllow` opens a real pool with 250 and reads the timeout back.

### The pool's place in start and stop

```java
    HikariDataSource pool;
    try {
      pool = DatabasePool.open(settings.database(), instanceName);
    } catch (IOException cannotRead) {
      err.println("SeatWave cannot start: " + cannotRead.getMessage());
      return Optional.empty();
    }
```

`DatabasePool.open` can throw `IOException` for a missing password file. The catch prints the message and returns. Only `IOException` is caught, which is why the unchecked `IllegalArgumentException` above reaches the user as a stack trace.

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
```

On stop, the server drains first, and only then does `running.pool().close()` run. `close()` stops the housekeeping and closes the real connections. In the run in `java-seatwave-walkthrough-2.md`, the log shows `seatwave-local - Shutdown initiated...` and `seatwave-local - Shutdown completed.`

### Why the sizes are 16 and 64

`http.workerThreads=64` request threads share a pool of 16 connections. Not every request touches the database for its whole life, so fewer connections than threads can be enough, and the database sees a calm 48 connections from three instances. The project did not measure other pool sizes; 16 is the shipped starting point. `ServiceSettings` refuses a pool larger than the worker count: a connection no worker can use would sit open at the database for nothing.

## Try it yourself

All commands run in the project folder. Run `./gradlew --offline -q stackFiles` first, which fills `build/stack/lib` with HikariCP and the libraries. The `find` locates H2's jar in Gradle's cache (run `./gradlew --offline test` once if it finds nothing). JShell is Java's interactive console; the `grep` keeps the answers.

1. Borrow, change and return a connection, and let a second borrower time out.

```bash
H2=$(find ~/.gradle -name 'h2-2.5.252.jar' | head -1)
cat > build/pool-try.jsh <<'EOF'
import com.zaxxer.hikari.*;
var defaults = new HikariConfig();
defaults.getMaximumPoolSize()
defaults.getConnectionTimeout()
defaults.getMaxLifetime()
var config = new HikariConfig();
config.setPoolName("demo");
config.setJdbcUrl("jdbc:h2:mem:demo");
config.setMaximumPoolSize(1);
config.setMinimumIdle(1);
config.setConnectionTimeout(250);
var pool = new HikariDataSource(config);
var first = pool.getConnection();
first.setAutoCommit(false);
first.getAutoCommit()
first.close();
var second = pool.getConnection();
second.getAutoCommit()
pool.getConnection()
second.close();
pool.close();
/exit
EOF
jshell --class-path "build/stack/lib/*:$H2" < build/pool-try.jsh 2>&1 | grep -E '\$[0-9]+ ==>|Exception' | sed 's/^jshell> //'
```

```text
$3 ==> 10
$4 ==> 30000
$5 ==> 1800000
$15 ==> false
$18 ==> true
|  Exception java.sql.SQLTransientConnectionException: demo - Connection is not available, request timed out after 251ms (total=1, active=1, idle=0, waiting=0)
|        at HikariPool.createTimeoutException (HikariPool.java:714)
```

What it shows, in order: the defaults (a pool of 10, a 30 second wait, a 30 minute lifetime, in milliseconds); autocommit is `false` while the first connection is borrowed; after it is returned, the next borrow of the same connection shows `true` (Hikari reset it); and a second borrower, while the only connection is in use, waits for 250 ms and then gets `Connection is not available, request timed out`. The 251 ms and the numbers like `$15` differ from run to run.

2. See the 250 ms floor that HikariCP enforces.

```bash
jshell --class-path 'build/stack/lib/*' <<'EOF' 2>&1 | grep -E 'Exception' | sed 's/^jshell> //'
new com.zaxxer.hikari.HikariConfig().setConnectionTimeout(249)
/exit
EOF
```

```text
|  Exception java.lang.IllegalArgumentException: connectionTimeout cannot be less than 250ms
```

3. Start an instance with a database address where nothing listens (the steps are in `java-seatwave-walkthrough-2.md`) and read its log. The pool says `Start completed` at once, with no database, and a request that needs the database fails after the connect timeout with a `503`. In the run for these notes, three such requests together took 3.06 seconds, one second each plus a few milliseconds.

4. Run the pool-related tests.

```bash
./gradlew --offline cleanTest test --tests '*SeatWaveCliTest' 2>&1 | grep -E "PASSED|FAILED|BUILD"
```

Three tests pass: one of them starts a real instance on port 0, with a Hikari pool over in-memory H2, and stops it with a clean drain.

## Mistakes people make

- **Making the pool bigger to go faster.** More connections do not make a database faster; past a point they make it slower. Keep it small and fixed.
- **Leaving a connection open.** A borrowed connection that is never closed is never returned; after 16 such leaks, the pool is empty and every request times out. Every use here is inside a try-with-resources block.
- **Waiting too long for a connection.** A 30 second wait ties up a thread for 30 seconds, which makes an overload worse. A short wait and a quick `503` lets the balancer try another instance.
- **Starting the pool before the database exists and failing.** The default start check would stop the instance; the negative initialization timeout makes it wait instead.
- **Setting a limit the library refuses.** The 250 ms floor above. The settings reader now uses the library's floor as its own minimum, so the mistake becomes a problem line, not a crash.
- **Letting two pools share a name.** Three instances run in three containers, each with its own pool; the pool name is the instance name, so log lines are told apart.
- **Closing the pool before the work ends.** `SeatWaveCli.stop` drains the server first.

## Check yourself

1. What does `close()` do to a connection borrowed from a pool?
   Answer: it gives the connection back to the pool for the next borrower; the real connection stays open. The pool also resets changes such as autocommit.
2. Why are `maximumPoolSize` and `minimumIdle` the same number here?
   Answer: to make a fixed pool, so the number of connections the database sees does not change with load.
3. What happens to a request when all 16 connections are busy?
   Answer: it waits up to the connection timeout (1,000 ms), and then `getConnection` throws; the store turns that into `StoreUnavailableException`, and the route answers `503` with `Retry-After`.
4. What does `setInitializationFailTimeout(-1)` allow?
   Answer: the pool starts without a connection, so an instance can start while the database is down; readiness then reports `database-unreachable`.
5. What is the smallest connection timeout HikariCP accepts, and what does this project's settings reader accept?
   Answer: HikariCP accepts 250 ms or more, and so does this project's settings reader (`MIN_CONNECT_MILLIS` is 250). A value from 1 to 249 is reported as a settings problem before the pool is built.

## Further reading

- HikariCP README: configuration options, defaults and the sizing advice: https://github.com/brettwooldridge/HikariCP
- HikariCP wiki, About Pool Sizing: https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing
- Java SE 25 API, `javax.sql.DataSource`: https://docs.oracle.com/en/java/javase/25/docs/api/java.sql/javax/sql/DataSource.html
- PostgreSQL 18 documentation, connection settings (`max_connections`): https://www.postgresql.org/docs/18/runtime-config-connection.html
