# seatwave-balancer

SeatWave, the ticket-booking service of the course System Design Fundamentals, scaled to three stateless instances behind a redundant pair of HAProxy load balancers, with a kill test that measures failover time and a drain test that proves zero failed requests during a rolling deploy. This is the project of **Module 10, "Spreading the load"**.

## What it shows

- Three SeatWave instances that keep no state in memory: every hold lives in a shared PostgreSQL database, so any instance answers for any hold, and a retried `POST /v1/holds` with the same `Idempotency-Key` replays the same hold on any instance.
- Two HAProxy 3.4.7 balancers sharing the floating address 10.210.10.100, moved by keepalived (VRRP) when the holder dies.
- Health checks of a `/ready` endpoint that is separate from `/health`; a passive check; slow start; retries only of requests SeatWave did not serve.
- A graceful shutdown that admits no new request once it starts draining and stops only when in-flight requests reach zero or a deadline passes.
- A Java harness that runs 3,000 requests per second from inside the lab network and reports traffic share, failures, detection time and failover gaps; and a rolling deploy tool.

Measured on the run below: a killed instance behind active checks alone cost 11,777 failed requests and was taken out after 11.82 s; behind the PRODUCTION backend the same kill cost 0 and took 13.4 ms; a rolling deploy of all three instances cost 0; killing the active balancer left a 2.78 s gap.

## Prerequisites

- JDK 25 (the build's toolchain asks for 25; it was run on Eclipse Temurin 25.0.2; the images run 25.0.5).
- Docker with Compose v2 for the stack (run on Docker Desktop, engine 24.0.6, Compose v2.23.0-desktop.1). `./gradlew check` needs no Docker.
- `curl` and `nc` on the machine for the scripts.

## Dependencies

Every version is pinned in `gradle/libs.versions.toml`, transitive versions in `gradle.lockfile`; all were checked on Maven Central on 11 November 2026, and OSV listed no vulnerability for any of the 34 locked jars on that day.

| Coordinates | Version | Checked | License | Why the JDK alone is not enough |
|---|---|---|---|---|
| `com.github.sviperll:result4j` | 1.2.0 | 11 Nov 2026 | Apache-2.0 | A success-or-typed-error type for expected failures. |
| `com.fasterxml.jackson.core:jackson-databind` (via `jackson-bom`) | 2.23.0 | 11 Nov 2026 | Apache-2.0 | JSON bound only to known records. |
| `org.slf4j:slf4j-api`, `ch.qos.logback:logback-classic` | 2.0.20, 1.6.6 | 11 Nov 2026 | MIT; EPL-2.0 or LGPL-2.1 | Logging with a correlation id. |
| `com.zaxxer:HikariCP` | 7.1.0 | 11 Nov 2026 | Apache-2.0 | A fixed connection pool to the shared database. |
| `org.postgresql:postgresql` (runtime) | 42.7.14 | 11 Nov 2026 | BSD-2-Clause | The PostgreSQL JDBC driver. |
| `com.h2database:h2` (test) | 2.5.252 | 11 Nov 2026 | MPL-2.0 or EPL-1.0 | Runs the same `db/schema.sql` in PostgreSQL mode, so `check` needs no Docker. |
| `org.junit:junit-bom`, `org.assertj:assertj-core`, `org.awaitility:awaitility` (test) | 6.1.3, 3.27.7, 4.3.0 | 11 Nov 2026 | EPL-2.0, Apache-2.0, Apache-2.0 | Tests, assertions, waiting without sleeping. |
| Spotless, google-java-format, JaCoCo (build) | 8.11.0, 2.0.0, 0.8.16 | 11 Nov 2026 | Apache-2.0, Apache-2.0, EPL-2.0 | Enforced format and a 0.80 line-coverage floor. |

Images, each pinned by tag and digest in `compose.yaml` or a `Dockerfile`, checked on Docker Hub on 11 November 2026, each with an `arm64` variant: `haproxy:3.4.7`, `postgres:18.6`, `alpine:3.24.4` (with the package `keepalived=2.3.4-r2`), `eclipse-temurin:25.0.5_8-jdk-noble`, `ubuntu:noble`. Gradle 9.9.1 through the wrapper; its jar and distribution checksums match services.gradle.org.

## Commands and what they printed

Run on 11 November 2026 on an Apple M2 Max (12 cores, 32 GB, macOS 27.2) with other programs running; containers in Docker Desktop's Linux virtual machine; the harness inside the lab network. Local paths are shortened to `.` and `~`. Your numbers will differ.

```bash
./gradlew clean check                      # format, compile with warnings as errors, tests, coverage
scripts/stack.sh up --run --yes            # password, jars, images, start, wait until ready
scripts/stack.sh status                    # read-only
scripts/lab.sh share --run --yes           # Lab 1
scripts/lab.sh kill-active --run --yes     # Lab 2, also kill-production, stop-active, stop-production
scripts/lab.sh deploy-production --run --yes   # Lab 3, also deploy-active
scripts/lab.sh pair --run --yes            # Lab 3, the balancer pair
./gradlew stackTest                        # the tests against the running stack
scripts/stack.sh down --run --yes          # down -v, and a check that nothing is left
./gradlew -q deploy --args='config/deploy.properties'   # the deploy's plan, a dry run
```

Every script is a dry run without `--run`, asks before it changes anything unless `--yes` is given, touches only the Compose project `seatwave-balancer`, and appends an audit line to `build/stack/audit.log`.

```text
$ ./gradlew clean check
(the run printed 98 lines that end in PASSED, and none that end in FAILED or SKIPPED; these are its last lines)

BUILD SUCCESSFUL in 19s
10 actionable tasks: 10 executed
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.9.1/userguide/configuration_cache_enabling.html
```

Line coverage: 0.893 (1,433 of 1,604 lines). The whole test task also ran 40 times in a row beside 8 busy loops on the 12 cores: 0 failures.

```text
$ ./gradlew -q stackFiles && docker compose -p seatwave-balancer build --no-cache --progress plain seatwave-1 2>&1 | grep -E 'linking modules' | sed 's/^#[0-9]* [0-9.]* //'
#10 [seatwave-1 runtime 3/3] RUN modules="$(jdeps --print-module-deps --ignore-missing-deps --multi-release 25         --class-path '/app/lib/*' /app/lib/seatwave-balancer-1.0.0.jar /app/lib/postgresql-42.7.14.jar)"     && echo "linking modules: ${modules}"     && jlink --add-modules "${modules}" --strip-debug --no-man-pages --no-header-files         --output /opt/seatwave-java
linking modules: java.base,java.desktop,java.management,java.naming,java.net.http,java.security.jgss,java.sql,jdk.httpserver

$ scripts/stack.sh up --run --yes 2>&1 | tail -1
stack is up after 5 s: 3 of 3 instances up on lb1 and lb2; 10.210.10.100 on keepalived-2

$ docker compose -p seatwave-balancer ps --format '{{.Service}}  {{.Image}}  {{.Status}}'
keepalived-1  seatwave-balancer-keepalived:1.0.0  Up 8 seconds
keepalived-2  seatwave-balancer-keepalived:1.0.0  Up 8 seconds
lb1  haproxy:3.4.7@sha256:f6d2ee87b47192d9cc8c22e88fef405122ff339859644883edc2a7d1827f87b3  Up 8 seconds
lb2  haproxy:3.4.7@sha256:f6d2ee87b47192d9cc8c22e88fef405122ff339859644883edc2a7d1827f87b3  Up 8 seconds
postgres  postgres:18.6@sha256:e3d24f6906d0760bf4da0dae872415391ededefad23fd471c47dc53b0ddf3c42  Up 8 seconds (healthy)
seatwave-1  seatwave-balancer-seatwave:1.0.0  Up 5 seconds
seatwave-2  seatwave-balancer-seatwave:1.0.0  Up 5 seconds
seatwave-3  seatwave-balancer-seatwave:1.0.0  Up 5 seconds

$ docker image ls --format '{{.Repository}}:{{.Tag}}  {{.Size}}' | grep -E '^seatwave-balancer-'
seatwave-balancer-seatwave:1.0.0  203MB
seatwave-balancer-keepalived:1.0.0  10.7MB
$ scripts/stack.sh status | tail -3
lb1: 3 of 3 instances up in backend seatwave
lb2: 3 of 3 instances up in backend seatwave
floating address on: keepalived-2
```

```text
$ curl -s -D - -H 'Content-Type: application/json' -H 'Idempotency-Key: "session-1"' --data '{"eventId":1,"seat":"A-7","customer":"fan-1"}' http://127.0.0.1:21000/v1/holds | grep -v -i '^date'; echo
HTTP/1.1 201 Created
x-served-by: seatwave-1
content-type: application/json
content-length: 128
location: /v1/holds/580ec039-fe3e-4ae2-a95c-6dd0c0cab0e4
cache-control: no-store
x-balancer: lb1

{"holdId":"580ec039-fe3e-4ae2-a95c-6dd0c0cab0e4","eventId":1,"seat":"A-7","state":"held","expiresAt":"2026-11-11T06:55:57.681Z"}


$ curl -s -D - -H 'Content-Type: application/json' -H 'Idempotency-Key: "session-1"' --data '{"eventId":1,"seat":"A-7","customer":"fan-1"}' http://127.0.0.1:21010/v1/holds | grep -v -i '^date'; echo
HTTP/1.1 201 Created
x-served-by: seatwave-2
content-type: application/json
content-length: 128
location: /v1/holds/580ec039-fe3e-4ae2-a95c-6dd0c0cab0e4
cache-control: no-store
x-balancer: lb2

{"holdId":"580ec039-fe3e-4ae2-a95c-6dd0c0cab0e4","eventId":1,"seat":"A-7","state":"held","expiresAt":"2026-11-11T06:55:57.681Z"}


$ curl -s -D - -H 'Content-Type: application/json' -H 'Idempotency-Key: "session-2"' --data '{"eventId":1,"seat":"A-7","customer":"fan-2"}' http://127.0.0.1:21000/v1/holds | grep -v -i '^date'; echo
HTTP/1.1 409 Conflict
x-served-by: seatwave-2
content-type: application/problem+json
content-length: 213
cache-control: no-store
x-balancer: lb1

{"type":"https://seatwave.example/problems/seat-unavailable","title":"The seat is held or sold","status":409,"detail":"the seat is not available","instance":"urn:seatwave:request:e0dadd4bac35498c8fa89a6a18c8ac01"}
```

```text
share by instance: seatwave-1 33.3% (20000), seatwave-2 33.3% (20000), seatwave-3 33.3% (19999)
share by instance: seatwave-1 33.5% (20110), seatwave-2 33.3% (19960), seatwave-3 33.2% (19929)
share by instance: seatwave-1 50.0% (30000), seatwave-2 25.0% (15000), seatwave-3 25.0% (14999)
share by instance: seatwave-2 100.0% (59999)
```

```text
+10 s: docker compose -p seatwave-balancer kill -s KILL seatwave-2
 Container seatwave-balancer-seatwave-2-1  Killing
 Container seatwave-balancer-seatwave-2-1  Killed
run kill-active: http://10.210.10.21:8082, 3000 requests/s for 30.0 s after a 5.0 s warm-up, 50% holds; client timeouts: connect 1000 ms, request 5000 ms
sent 89999, answered 78222, failed 11777 (13.086%), not sent 0
statuses: 409 78222, 502 1, 503 3305
failures: HTTP 502 1, HTTP 503 3305, IOException: HTTP/1.1 header parser received no bytes 8471
latency of answered requests: p50 0.8 ms, p99 26.6 ms, max 126.1 ms
share by instance: seatwave-1 43.8% (34238), seatwave-2 12.5% (9746), seatwave-3 43.8% (34238)
share by balancer: lb1 100.0% (78222)
balancer status changes (backend seatwave_active_only):
  +13.598 s lb1 seatwave-2: UP -> UP 2/3
  +17.580 s lb1 seatwave-2: UP 2/3 -> UP 1/3
  +21.575 s lb1 seatwave-2: UP 1/3 -> DOWN
balancer lb1 during the run (backend seatwave_active_only): 105000 requests passed, 0 retries, 0 redispatches
removal of seatwave-2:
  last answer from seatwave-2: +9.750 s
  taken out by lb1 at +21.575 s: 11824.2 ms after its last answer
  failed requests sent in between: 11773; after: 0; Little's Law without retries: 3000 requests/s x 0.333 share x 11.824 s = 11824
longest gap between two answers: 115.8 ms, from +11.866 s to +11.982 s (117 failed requests sent inside it)
```

```text
+10 s: docker compose -p seatwave-balancer kill -s KILL seatwave-2
 Container seatwave-balancer-seatwave-2-1  Killing
 Container seatwave-balancer-seatwave-2-1  Killed
run kill-production: http://10.210.10.21:8080, 3000 requests/s for 30.0 s after a 5.0 s warm-up, 50% holds; client timeouts: connect 1000 ms, request 5000 ms
sent 89999, answered 89999, failed 0 (0.000%), not sent 0
statuses: 409 89999
failures: none
latency of answered requests: p50 0.8 ms, p99 4.1 ms, max 16.9 ms
share by instance: seatwave-1 44.8% (40335), seatwave-2 10.4% (9330), seatwave-3 44.8% (40334)
share by balancer: lb1 100.0% (89999)
balancer status changes (backend seatwave):
  +9.769 s lb1 seatwave-2: UP -> DOWN
balancer lb1 during the run (backend seatwave): 105000 requests passed, 6 retries, 9 redispatches
removal of seatwave-2:
  last answer from seatwave-2: +9.756 s
  taken out by lb1 at +9.769 s: 13.4 ms after its last answer
  failed requests sent in between: 0; after: 0; Little's Law without retries: 3000 requests/s x 0.319 share x 0.013 s = 13
longest gap between two answers: 6.7 ms, from +7.264 s to +7.271 s (0 failed requests sent inside it)
```

```text
+8 s: ./gradlew -q deploy --args=config/deploy.properties --run --yes
plan: restart [seatwave-1, seatwave-2, seatwave-3] of project seatwave-balancer one at a time, on balancers [lb1, lb2], backends [seatwave, seatwave_active_only]
  each: drain, wait for 0 sessions, stop (SIGTERM), wait until seen down, start, ready, wait until up, settle 10000 ms
+     31.5 ms  seatwave-1: drain set on 2 balancer(s)
+     43.6 ms  seatwave-1: no session left on any balancer
+    267.4 ms  seatwave-1: stopped (SIGTERM; the instance drained itself)
+  11935.8 ms  seatwave-1: seen down by every balancer's checks
+  12647.1 ms  seatwave-1: started
+  12651.9 ms  seatwave-1: ready set; the checks decide when it gets traffic again
+  15999.5 ms  seatwave-1: up on every balancer; slow start ramps its weight
+  26006.1 ms  seatwave-2: drain set on 2 balancer(s)
+  26010.3 ms  seatwave-2: no session left on any balancer
+  26220.2 ms  seatwave-2: stopped (SIGTERM; the instance drained itself)
+  36516.5 ms  seatwave-2: seen down by every balancer's checks
+  37239.2 ms  seatwave-2: started
+  37244.2 ms  seatwave-2: ready set; the checks decide when it gets traffic again
+  40501.2 ms  seatwave-2: up on every balancer; slow start ramps its weight
+  50513.7 ms  seatwave-3: drain set on 2 balancer(s)
+  50517.0 ms  seatwave-3: no session left on any balancer
+  50739.7 ms  seatwave-3: stopped (SIGTERM; the instance drained itself)
+  62376.1 ms  seatwave-3: seen down by every balancer's checks
+  63108.6 ms  seatwave-3: started
+  63117.4 ms  seatwave-3: ready set; the checks decide when it gets traffic again
+  66714.5 ms  seatwave-3: up on every balancer; slow start ramps its weight
deploy finished: 3 instances restarted, all up
run deploy-production: http://10.210.10.21:8080, 3000 requests/s for 100.0 s after a 5.0 s warm-up, 50% holds; client timeouts: connect 1000 ms, request 5000 ms
sent 299999, answered 299999, failed 0 (0.000%), not sent 0
statuses: 200 150000, 201 9997, 409 140002
failures: none
latency of answered requests: p50 0.7 ms, p99 4.1 ms, max 132.2 ms
share by instance: seatwave-1 33.2% (99694), seatwave-2 33.6% (100936), seatwave-3 33.1% (99369)
share by balancer: lb1 100.0% (299999)
balancer status changes (backend seatwave):
  +8.356 s lb1 seatwave-1: UP -> DRAIN
  +11.891 s lb1 seatwave-1: DRAIN -> DRAIN 2/3
  +15.930 s lb1 seatwave-1: DRAIN 2/3 -> DRAIN 1/3
  +19.869 s lb1 seatwave-1: DRAIN 1/3 -> DOWN
  +21.891 s lb1 seatwave-1: DOWN -> DOWN 1/2
  +23.912 s lb1 seatwave-1: DOWN 1/2 -> UP
  +34.301 s lb1 seatwave-2: UP -> DRAIN
  +36.620 s lb1 seatwave-2: DRAIN -> DRAIN 2/3
  +40.654 s lb1 seatwave-2: DRAIN 2/3 -> DRAIN 1/3
  +44.587 s lb1 seatwave-2: DRAIN 1/3 -> DOWN
  +46.606 s lb1 seatwave-2: DOWN -> DOWN 1/2
  +48.623 s lb1 seatwave-2: DOWN 1/2 -> UP
  +58.816 s lb1 seatwave-3: UP -> DRAIN
  +62.648 s lb1 seatwave-3: DRAIN -> DRAIN 2/3
  +66.579 s lb1 seatwave-3: DRAIN 2/3 -> DRAIN 1/3
  +70.617 s lb1 seatwave-3: DRAIN 1/3 -> DOWN
  +72.634 s lb1 seatwave-3: DOWN -> DOWN 1/2
  +74.649 s lb1 seatwave-3: DOWN 1/2 -> UP
balancer lb1 during the run (backend seatwave): 315000 requests passed, 0 retries, 0 redispatches
longest gap between two answers: 117.7 ms, from +77.330 s to +77.447 s (0 failed requests sent inside it)
```

```text
floating address before: keepalived-2, so the balancer to kill is lb2
+12 s: docker compose -p seatwave-balancer kill -s KILL lb2
 Container seatwave-balancer-lb2-1  Killing
 Container seatwave-balancer-lb2-1  Killed
floating address after the kill: keepalived-1
run pair: http://10.210.10.100:8080, 3000 requests/s for 40.0 s after a 5.0 s warm-up, 50% holds; client timeouts: connect 1000 ms, request 5000 ms
sent 119999, answered 111684, failed 8315 (6.929%), not sent 0
statuses: 200 55850, 409 55834
failures: ConnectException 2, HttpConnectTimeoutException: HTTP connect timed out 8283, IOException: HTTP/1.1 header parser received no bytes 30
latency of answered requests: p50 0.7 ms, p99 1.5 ms, max 1014.3 ms
share by instance: seatwave-1 33.3% (37228), seatwave-2 33.3% (37228), seatwave-3 33.3% (37228)
share by balancer: lb1 68.4% (76368), lb2 31.6% (35316)
balancer status changes (backend seatwave):
  +11.785 s lb2 *: ANSWERING -> UNREACHABLE
balancer lb1 during the run (backend seatwave): 76368 requests passed, 0 retries, 0 redispatches
balancer lb2 during the run (backend seatwave): 50052 requests passed, 0 retries, 0 redispatches
longest gap between two answers: 2777.1 ms, from +11.773 s to +14.550 s (8313 failed requests sent inside it)
floating address after the kill: keepalived-1
run pair: http://10.210.10.100:8080, 3000 requests/s for 40.0 s after a 5.0 s warm-up, 50% holds; client timeouts: connect 1000 ms, request 5000 ms
floating address after lb2 came back: keepalived-1
```

```text
$ ./gradlew stackTest 2>&1 | grep -E 'PASSED|FAILED|BUILD'
StackTest > letsOneOfTwoInstancesWinOneSeat() PASSED
StackTest > bothBalancersSeeThreeInstancesUp() PASSED
StackTest > readsAHoldThroughEveryInstanceWhicheverMadeIt() PASSED
StackTest > sendsNothingNewToADrainedInstance() PASSED
BUILD SUCCESSFUL in 1s
$ scripts/stack.sh down --run --yes 2>&1 | tail -1
stack is down: no container, network or volume of seatwave-balancer is left

$ docker ps -a --filter label=com.docker.compose.project=seatwave-balancer --format '{{.Names}}' | wc -l | tr -d ' '; docker network ls --filter label=com.docker.compose.project=seatwave-balancer --format '{{.Name}}' | wc -l | tr -d ' '; docker volume ls --filter label=com.docker.compose.project=seatwave-balancer --format '{{.Name}}' | wc -l | tr -d ' '
0
0
0
```

## Ports

All on 127.0.0.1 only. lb1: 21000 (PRODUCTION, round robin), 21001 (least connections), 21002 (active checks only, no retries), 21003 (source hashing), 21004 (status page `/stats` and `/healthz`), 21005 (runtime API, admin level, for the deploy tool). lb2: the same on 21010 to 21015. Port 8084 (weighted) and the floating address are reachable only from the lab network. No SeatWave instance and not the database publish a port.

## Project structure

See `ARCHITECTURE.md` for packages, the dependency rule, a data-flow diagram, walk-throughs, decisions and invariants.

```text
compose.yaml, Dockerfile, keepalived/, haproxy/haproxy.cfg, db/schema.sql
config/          settings for the instances, every lab run and the deploy
scripts/         stack.sh, lab.sh, common.sh
src/main/java/io/plainround/seatwave/balancer/
  seatwave/      one instance: server, lifecycle, routes, hold store, settings, metrics
  haproxy/       the runtime API client
  harness/       the load, the watches, the report
  deploy/        the rolling deploy
  config/, json/ from the Module 9 project
```

## Where things came from

Adapted from the Module 9 project `seatwave-proxy` (revision `e0fb70f`), not depended on at build time: `config/SettingsReader.java`, `config/SettingsFiles.java` (with a catch for malformed escapes added), `json/JsonCodec.java` and its test of the settings reader, unchanged except the package; `Problems.java` and `Reply.java` (simplified); the two-stage `Dockerfile`; the shape and guardrails of `scripts/stack.sh`; the session and audit tooling of the course. The review findings of Module 9 were applied here: file paths in settings accept spaces and long folders, `Content-Type` is compared by media type, and configuration-rule tests are mutation-checked.

## What is simplified

- One shared PostgreSQL container: a single point of failure this module names and leaves to Modules 13 to 19.
- Plain HTTP inside one private network; TLS at the balancer is Module 9's.
- Both balancers and all instances run on one Docker machine and one network, so the pair's independence is far smaller than two zones'.
- The "deploy" restarts the same image; a real deploy starts a new version.
- The runtime API is a TCP socket at admin level on the lab network; production would bind it to a management network or a UNIX socket.
- Only two routes, `POST /v1/holds` and `GET /v1/holds/{holdId}`.

## No CI on purpose

This repository has no `.github/` folder and no CI workflow, on purpose: the course's owner verifies every project locally, with the commands above.

## The Compose path

`compose.yaml` holds all the infrastructure under the project name `seatwave-balancer`; `scripts/stack.sh` starts it, tests it and brings it down with `down -v`, then checks that no container, network or volume of the project is left.

## License

MIT, see `LICENSE`.
