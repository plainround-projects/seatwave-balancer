# Changelog

All notable changes to this project are written here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [1.1.0] - 2026-11-11

The review pass: every finding of the four readers of version 1.0.0 was checked; the defects are
fixed, each with a test that fails without the fix, and every load run was measured again.

### Fixed

- The harness: each request's start time is computed from its index, so at 3,000 requests per
  second the first measured request is no longer dropped with the warm-up (`sent 90000`, not
  `89999`); every planned request ends as exactly one outcome, a fault of the harness itself
  included; the mix is decided by the index, so a "50% holds" run is 50% holds even when every
  hold is refused; the `k`-th hold asks for seat `(seed + k x 7,919) mod 20,000`, so the holds
  reach all 20,000 seats; a 201 whose body cannot be read is counted; the Little's Law line
  multiplies out as printed; a status change is stamped when the balancer's answer arrived.
- `scripts/lab.sh` gives every held seat back before each run and, through an `EXIT` trap, on every
  way out; it checks both checked backends before it starts, puts the stack back also when the disruption fails, and exits
  non-zero then.
- The deploy: a failing audit line after a finished deploy no longer says "deploy stopped"; a
  failing first audit line starts nothing; an instance already failing a check (`UP 1/3`) stops
  the deploy before it changes anything; `docker compose` output is read while it runs, and that
  read is bounded by the same time limit.
- Settings: a rule between two keys runs only when both were read (no second, misleading problem
  line); `db.connectTimeoutMillis` starts at 250, HikariCP's own minimum, instead of a stack trace;
  a short or padded address (`127.0.1`, port `09999`, port 0, port 99999) is refused.
- The instance: a whole-body problem has no leading space; a repeated field and text after the
  JSON object are named (`JsonCodec` from the Module 9 project's review revision); `HEAD` is
  answered like `GET` without a body; the request body of a refused request is read whole; the
  metrics end every line with `\n`; the usage message names `SeatWaveCli`.
- `haproxy.cfg`: `timeout http-request 5s`; the comments on `timeout check` and on `retry-on` say
  what the manual and the log say. keepalived: the comment no longer claims lb1 holds the address
  first. `Dockerfile`: an ordinary user 10001 with the home `/nonexistent`. `scripts/stack.sh`: a
  dry run needs no Docker, the plan shows `--quiet-pull`, the leftover check lists one id per line,
  and the Docker timeout is one constant.
- The test task declares every file a test reads as an input, so a changed `compose.yaml`,
  Dockerfile or settings file runs the tests again.

### Added

- `scripts/lab.sh cpu`: the CPU lb1 and seatwave-1 use per 1,000 requests, from their control
  groups, the measurement behind the module's balancer sizing.
- The report's `mix:` line, and `StatRow.isFullyUp` and `secondsSinceChange`.
- Tests: `LoadRunTest`, `BalancerWatchTest`, `DeploySettingsTest`, `DockerComposeTest`,
  `JsonCodecTest`, the `23505` race of `JdbcHoldStore` (forced by a barrier at the INSERT), the
  shutdown margin against `stop_grace_period`, the pin of every `FROM` line and image, and stack
  tests that force the one-owner race onto two instances, wait for slow start to end, read every
  port from `compose.yaml`, and race one key on PostgreSQL. Tests that review found weak (drain
  times, the admission race, the settle and seen-down waits, nearest rank, the removal bounds, the
  hold share, the stop-timeout rule, the last watch reading, the 4xx classification) now fail for
  the defect they name.

## [1.0.0] - 2026-11-11

### Added

- A Compose stack (`compose.yaml`) under the project name `seatwave-balancer`: PostgreSQL 18.6 as the
  shared database, three SeatWave instances, two HAProxy 3.4.7 balancers, a keepalived 2.3.4 beside
  each balancer holding the floating address 10.210.10.100, and a `harness` service for the labs;
  every image pinned by tag and digest, only balancer ports published, on 127.0.0.1 in 21000 to
  21099, nothing privileged.
- The balancer configuration (`haproxy/haproxy.cfg`): PRODUCTION's backend with an active `/ready`
  check every 2 s (down after 3 failures, up after 2), a passive check, slow start over 10 s and
  retries only for requests SeatWave did not serve; an active-only backend; and backends for least
  connections, source hashing and weighted round robin that track PRODUCTION's checks.
- A stateless SeatWave instance on the JDK's HTTP server: Module 7's `POST /v1/holds` and
  `GET /v1/holds/{holdId}` with holds in the shared database, `/health` and `/ready` as two separate
  questions on their own listener, and a graceful shutdown that admits nothing new once it starts
  draining and stops only when in-flight requests reach zero or a deadline passes.
- The kill-and-drain harness: open-loop load from inside the lab network, one watch per balancer
  over the runtime API, and a report of traffic share, failures, latency, detection time, the
  longest gap without an answer, the balancer's own retries, and a timeline.
- The rolling deploy tool, a dry run by default, which drains, stops, restarts and readies each
  instance on every balancer in turn.
- `scripts/stack.sh` and `scripts/lab.sh`, each a dry run by default, with a confirmation, a fixed
  target, a check before and after, and an audit line.
- Tests for every rule, including the configuration rules of `haproxy.cfg` and `compose.yaml`
  (mutation-checked), and a `stackTest` task against the running stack, kept out of `./gradlew check`.
