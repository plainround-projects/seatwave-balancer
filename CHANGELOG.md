# Changelog

All notable changes to this project are written here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

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
