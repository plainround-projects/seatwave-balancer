# seatwave-balancer

SeatWave, the ticket-booking service of the course System Design Fundamentals, scaled to three stateless instances behind a redundant pair of HAProxy load balancers, with a kill test that measures failover time and a drain test that proves zero failed requests during a rolling deploy. This is the project of **Module 10, "Spreading the load"**.

## What it shows

- Three SeatWave instances that keep no state in memory: every hold lives in a shared PostgreSQL database, so any instance answers for any hold, and a retried `POST /v1/holds` with the same `Idempotency-Key` replays the same hold on any instance.
- Two HAProxy 3.4.7 balancers sharing the floating address 10.210.10.100, moved by keepalived (VRRP) when the holder dies.
- Health checks of a `/ready` endpoint that is separate from `/health`; a passive check; slow start; retries only of what is safe to repeat: a request that never reached SeatWave, one SeatWave refused with `503` having done nothing, and one whose connection closed before any byte of an answer (that last one may have been served, and is safe for a hold only because the hold carries an `Idempotency-Key`).
- A graceful shutdown that admits no new request once it starts draining and stops only when in-flight requests reach zero or a deadline passes.
- A Java harness that runs 3,000 requests per second from inside the lab network and reports traffic share, failures, detection time and failover gaps; and a rolling deploy tool.

Measured on the run below: a killed instance behind active checks alone cost 7,653 failed requests and was taken out after 10.87 s; behind the PRODUCTION backend the same kill cost 0 and took 52.7 ms; a rolling deploy of all three instances cost 0; killing the active balancer left a 3.07 s gap.

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

Run on 11 November 2026 on an Apple M2 Max (12 cores, 32 GB, macOS 27.2) with other programs running; containers in Docker Desktop's Linux virtual machine; the harness inside the lab network. The stack runs below were taken between 08:33 and 08:45 UTC, while other work on the machine kept the 1-minute load average between about 4 and 7 (5.98 when they began). Local paths are shortened to `.` and `~`. Your numbers will differ.

```bash
./gradlew clean check                      # format, compile with warnings as errors, tests, coverage
scripts/stack.sh up --run --yes            # password, jars, images, start, wait until ready
scripts/stack.sh status                    # read-only
scripts/lab.sh cpu --run --yes             # Section 8: CPU per 1,000 requests of lb1 and one instance
scripts/lab.sh share --run --yes           # Lab 1
scripts/lab.sh kill-active --run --yes     # Lab 2, also kill-production, stop-active, stop-production
scripts/lab.sh deploy-production --run --yes   # Lab 3, also deploy-active
scripts/lab.sh pair --run --yes            # Lab 3, the balancer pair
./gradlew stackTest                        # the tests against the running stack
scripts/stack.sh down --run --yes          # down -v, and a check that nothing is left
./gradlew -q deploy --args='config/deploy.properties'   # the deploy's plan, a dry run
```

Every script is a dry run without `--run` (a dry run needs no Docker and changes nothing), asks before it changes anything unless `--yes` is given, touches only the Compose project `seatwave-balancer`, and appends an audit line to `build/stack/audit.log`. Before and after every lab run, `scripts/lab.sh` gives every held seat back (one `UPDATE` in the lab's own database), so each run starts with all 20,000 seats free and its mix is the one its label says; the report's `mix:` line prints what really ran.

`scripts/stack.sh up` writes a throwaway database password to `build/secrets/db.password` with mode `rw-r--r--`. Owner-only would break on a Linux host, where the secret is a bind mount that keeps the file's owner and mode and is read by other users inside the containers (PostgreSQL's entrypoint, SeatWave as uid 10001). So any account on the machine can read this lab's password; it is new for every fresh stack, ignored by git, never copied into an image, and deleted by `scripts/stack.sh down`.

```text
$ ./gradlew clean check
(the run printed 152 lines that end in PASSED, and none that end in FAILED or SKIPPED; these are its last lines)

BUILD SUCCESSFUL in 8s
10 actionable tasks: 10 executed
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.9.1/userguide/configuration_cache_enabling.html
```

Line coverage: 0.934 (1,610 of 1,723 lines). The whole test task also ran 40 times in a row beside 8 busy loops on the 12 cores: 0 failures. `./gradlew clean check` also passed in a copy of the repository at a path of 399 characters and in one whose folder names hold a space (324 characters). 45 one-line mutations of the code and the configuration, each undoing a fix of the review pass or breaking a rule a test names, were each caught by a failing test.

```text
$ scripts/lab.sh cpu --run --yes
plan: lab cpu on the Compose project seatwave-balancer: one 20 s round-robin run at 3,000 requests/s, with lb1's and seatwave-1's CPU read before and after; nothing is disrupted
run share-rr: http://10.210.10.21:8080, 3000 requests/s for 20.0 s after a 5.0 s warm-up, 50% holds; client timeouts: connect 1000 ms, request 5000 ms
sent 60000, answered 60000, failed 0 (0.000%), not sent 0
mix: 30000 holds (POST), 30000 reads (GET), of which 0 of a hold not made yet; 0 hold replies the harness could not read
latency of answered requests: p50 0.7 ms, p99 9.8 ms, max 70.1 ms
balancer lb1 during the run (backend seatwave): 75000 requests passed, 0 retries, 0 redispatches
cpu: lb1 used 5226.5 ms of CPU for 75000 requests passed: 69.7 ms per 1,000 requests
cpu: seatwave-1 used 8818.8 ms of CPU for 25000 requests: 352.8 ms per 1,000 requests
```

The CPU lines divide the CPU time each container used between two readings by the requests the balancer passed between the same two readings (warm-up included); lb1's number also holds its own health checks and the harness's `show stat` every 100 ms, and seatwave-1's holds the checks of both balancers.

```text
$ ./gradlew -q stackFiles && docker compose -p seatwave-balancer build --no-cache --progress plain seatwave-1 2>&1 | grep -E 'linking modules' | sed 's/^#[0-9]* [0-9.]* //'
#10 [seatwave-1 runtime 3/3] RUN modules="$(jdeps --print-module-deps --ignore-missing-deps --multi-release 25         --class-path '/app/lib/*' /app/lib/seatwave-balancer-1.1.0.jar /app/lib/postgresql-42.7.14.jar)"     && echo "linking modules: ${modules}"     && jlink --add-modules "${modules}" --strip-debug --no-man-pages --no-header-files         --output /opt/seatwave-java
linking modules: java.base,java.desktop,java.management,java.naming,java.net.http,java.security.jgss,java.sql,jdk.httpserver

$ scripts/stack.sh up --run --yes 2>&1 | tail -1
stack is up after 4 s: 3 of 3 instances up on lb1 and lb2; 10.210.10.100 on keepalived-1

$ docker compose -p seatwave-balancer ps --format '{{.Service}}  {{.Image}}  {{.Status}}'
keepalived-1  seatwave-balancer-keepalived:1.1.0  Up 7 seconds
keepalived-2  seatwave-balancer-keepalived:1.1.0  Up 7 seconds
lb1  haproxy:3.4.7@sha256:f6d2ee87b47192d9cc8c22e88fef405122ff339859644883edc2a7d1827f87b3  Up 7 seconds
lb2  haproxy:3.4.7@sha256:f6d2ee87b47192d9cc8c22e88fef405122ff339859644883edc2a7d1827f87b3  Up 7 seconds
postgres  postgres:18.6@sha256:e3d24f6906d0760bf4da0dae872415391ededefad23fd471c47dc53b0ddf3c42  Up 7 seconds (healthy)
seatwave-1  seatwave-balancer-seatwave:1.1.0  Up 4 seconds
seatwave-2  seatwave-balancer-seatwave:1.1.0  Up 4 seconds
seatwave-3  seatwave-balancer-seatwave:1.1.0  Up 4 seconds

$ docker image ls --format '{{.Repository}}:{{.Tag}}  {{.Size}}' | grep -E '^seatwave-balancer-(seatwave|keepalived):1\.1\.0 '
seatwave-balancer-seatwave:1.1.0  203MB
seatwave-balancer-keepalived:1.1.0  10.7MB
$ scripts/stack.sh status | tail -3
lb1: 3 of 3 instances up in backend seatwave
lb2: 3 of 3 instances up in backend seatwave
floating address on: keepalived-1
```

```text
$ curl -s -D - -H 'Content-Type: application/json' -H 'Idempotency-Key: "session-1"' --data '{"eventId":1,"seat":"A-7","customer":"fan-1"}' http://127.0.0.1:21000/v1/holds | grep -v -i '^date'; echo
HTTP/1.1 201 Created
x-served-by: seatwave-1
content-type: application/json
content-length: 128
location: /v1/holds/86e2143c-f860-42eb-8779-004eb9ee9960
cache-control: no-store
x-balancer: lb1

{"holdId":"86e2143c-f860-42eb-8779-004eb9ee9960","eventId":1,"seat":"A-7","state":"held","expiresAt":"2026-11-11T08:38:45.954Z"}


$ curl -s -D - -H 'Content-Type: application/json' -H 'Idempotency-Key: "session-1"' --data '{"eventId":1,"seat":"A-7","customer":"fan-1"}' http://127.0.0.1:21010/v1/holds | grep -v -i '^date'; echo
HTTP/1.1 201 Created
x-served-by: seatwave-3
content-type: application/json
content-length: 128
location: /v1/holds/86e2143c-f860-42eb-8779-004eb9ee9960
cache-control: no-store
x-balancer: lb2

{"holdId":"86e2143c-f860-42eb-8779-004eb9ee9960","eventId":1,"seat":"A-7","state":"held","expiresAt":"2026-11-11T08:38:45.954Z"}


$ curl -s -D - -H 'Content-Type: application/json' -H 'Idempotency-Key: "session-2"' --data '{"eventId":1,"seat":"A-7","customer":"fan-2"}' http://127.0.0.1:21000/v1/holds | grep -v -i '^date'; echo
HTTP/1.1 409 Conflict
x-served-by: seatwave-2
content-type: application/problem+json
content-length: 213
cache-control: no-store
x-balancer: lb1

{"type":"https://seatwave.example/problems/seat-unavailable","title":"The seat is held or sold","status":409,"detail":"the seat is not available","instance":"urn:seatwave:request:7d15e2dba70d4ebdbeb684c589d6e3c5"}
```

```text
share by instance: seatwave-1 33.3% (20000), seatwave-2 33.3% (20000), seatwave-3 33.3% (20000)
share by instance: seatwave-1 33.3% (19958), seatwave-2 33.6% (20134), seatwave-3 33.2% (19908)
share by instance: seatwave-1 50.0% (30000), seatwave-2 25.0% (15000), seatwave-3 25.0% (15000)
share by instance: seatwave-2 100.0% (60000)
```

How to read these reports:

- `+10 s:` is the script's clock: it sleeps 5 s of warm-up and 10 s after starting the harness, whose own clock starts a moment later, so the report shows the kill as the victim's last answer, a little before +10 s.
- `UP 2/3` means up, with one failed check counted: two more failures take it down. `UP 1/3`: one more failure does. `DOWN 1/2`: down, with one of the two passing checks it needs to come up. The checks run every 2 s (`inter`), and a check of a dead instance fails after its 2 s connect wait (with `timeout check` set, the connect waits `min(timeout connect, inter)`, here 2 s, as the HAProxy log's `Layer4 timeout, check duration: 2000ms` shows); the next check starts 2 s after one ends, so failed checks come about 4 s apart and three take about 12 s after the first one starts.
- The Little's Law line multiplies the factors exactly as printed: rate, the victim's share of answers before its last answer, and the detection time.
- The harness's own client retries once, as a fan's app would: any request whose connection was refused, and a `GET` (never a `POST`) whose reused connection closed before any byte of the answer (the JDK's `MultiExchange`, read in the 25.0.2 source).

```text
+10 s: docker compose -p seatwave-balancer kill -s KILL seatwave-2
 Container seatwave-balancer-seatwave-2-1  Killing
 Container seatwave-balancer-seatwave-2-1  Killed
run kill-active: http://10.210.10.21:8082, 3000 requests/s for 30.0 s after a 5.0 s warm-up, 50% holds; client timeouts: connect 1000 ms, request 5000 ms
sent 90000, answered 82347, failed 7653 (8.503%), not sent 0
mix: 45000 holds (POST), 45000 reads (GET), of which 0 of a hold not made yet; 0 hold replies the harness could not read
statuses: 200 42761, 201 12501, 409 27085, 502 2, 503 2882
failures: HTTP 502 2, HTTP 503 2882, IOException: HTTP/1.1 header parser received no bytes 4769
latency of answered requests: p50 0.7 ms, p99 2000.7 ms, max 2138.3 ms
share by instance: seatwave-1 44.2% (36368), seatwave-2 11.7% (9611), seatwave-3 44.2% (36368)
share by balancer: lb1 100.0% (82347)
balancer status changes (backend seatwave_active_only):
  +12.459 s lb1 seatwave-2: UP -> UP 2/3
  +16.502 s lb1 seatwave-2: UP 2/3 -> UP 1/3
  +20.483 s lb1 seatwave-2: UP 1/3 -> DOWN
balancer lb1 during the run (backend seatwave_active_only): 109379 requests passed, 0 retries, 0 redispatches
removal of seatwave-2:
  last answer from seatwave-2: +9.611 s
  taken out by lb1 at +20.483 s: 10871.8 ms after its last answer
  failed requests sent in between: 7653; after: 0; Little's Law without retries: 3000 requests/s x 0.3333 share x 10.872 s = 10871
longest gap between two answers: 107.7 ms, from +14.963 s to +15.070 s (101 failed requests sent inside it)
```

```text
+10 s: docker compose -p seatwave-balancer kill -s KILL seatwave-2
 Container seatwave-balancer-seatwave-2-1  Killing
 Container seatwave-balancer-seatwave-2-1  Killed
run kill-production: http://10.210.10.21:8080, 3000 requests/s for 30.0 s after a 5.0 s warm-up, 50% holds; client timeouts: connect 1000 ms, request 5000 ms
sent 90000, answered 90000, failed 0 (0.000%), not sent 0
mix: 45000 holds (POST), 45000 reads (GET), of which 0 of a hold not made yet; 0 hold replies the harness could not read
statuses: 200 45000, 201 12500, 409 32500
failures: none
latency of answered requests: p50 0.7 ms, p99 4.7 ms, max 25.8 ms
share by instance: seatwave-1 44.8% (40343), seatwave-2 10.3% (9314), seatwave-3 44.8% (40343)
share by balancer: lb1 100.0% (90000)
balancer status changes (backend seatwave):
  +9.644 s lb1 seatwave-2: UP -> DOWN
balancer lb1 during the run (backend seatwave): 105000 requests passed, 13 retries, 18 redispatches
removal of seatwave-2:
  last answer from seatwave-2: +9.591 s
  taken out by lb1 at +9.644 s: 52.7 ms after its last answer
  failed requests sent in between: 0; after: 0; Little's Law without retries: 3000 requests/s x 0.3237 share x 0.053 s = 51
longest gap between two answers: 8.0 ms, from +4.209 s to +4.217 s (0 failed requests sent inside it)
```

```text
+8 s: ./gradlew -q deploy --args=config/deploy.properties --run --yes
plan: restart [seatwave-1, seatwave-2, seatwave-3] of project seatwave-balancer one at a time, on balancers [lb1, lb2], backends [seatwave, seatwave_active_only]
  each: drain, wait for 0 sessions, stop (SIGTERM), wait until seen down, start, ready, wait until up, settle 10000 ms
+     35.7 ms  seatwave-1: drain set on 2 balancer(s)
+     48.6 ms  seatwave-1: no session left on any balancer
+    290.4 ms  seatwave-1: stopped (SIGTERM; the instance drained itself)
+  12076.8 ms  seatwave-1: seen down by every balancer's checks
+  12802.0 ms  seatwave-1: started
+  12808.8 ms  seatwave-1: ready set; the checks decide when it gets traffic again
+  17095.0 ms  seatwave-1: up on every balancer; slow start ramps its weight
+  27101.2 ms  seatwave-2: drain set on 2 balancer(s)
+  27104.7 ms  seatwave-2: no session left on any balancer
+  27329.5 ms  seatwave-2: stopped (SIGTERM; the instance drained itself)
+  38716.5 ms  seatwave-2: seen down by every balancer's checks
+  39472.1 ms  seatwave-2: started
+  39477.5 ms  seatwave-2: ready set; the checks decide when it gets traffic again
+  42759.0 ms  seatwave-2: up on every balancer; slow start ramps its weight
+  52764.8 ms  seatwave-3: drain set on 2 balancer(s)
+  52768.2 ms  seatwave-3: no session left on any balancer
+  53020.2 ms  seatwave-3: stopped (SIGTERM; the instance drained itself)
+  63933.7 ms  seatwave-3: seen down by every balancer's checks
+  64658.3 ms  seatwave-3: started
+  64663.2 ms  seatwave-3: ready set; the checks decide when it gets traffic again
+  67910.3 ms  seatwave-3: up on every balancer; slow start ramps its weight
deploy finished: 3 instances restarted, all up
run deploy-production: http://10.210.10.21:8080, 3000 requests/s for 100.0 s after a 5.0 s warm-up, 50% holds; client timeouts: connect 1000 ms, request 5000 ms
sent 300000, answered 300000, failed 0 (0.000%), not sent 0
mix: 150000 holds (POST), 150000 reads (GET), of which 0 of a hold not made yet; 0 hold replies the harness could not read
statuses: 200 150000, 201 12850, 409 137150
failures: none
latency of answered requests: p50 0.8 ms, p99 38.9 ms, max 179.7 ms
share by instance: seatwave-1 32.8% (98351), seatwave-2 33.4% (100315), seatwave-3 33.8% (101334)
share by balancer: lb1 100.0% (300000)
balancer status changes (backend seatwave):
  +8.193 s lb1 seatwave-1: UP -> DRAIN
  +11.025 s lb1 seatwave-1: DRAIN -> DRAIN 2/3
  +14.962 s lb1 seatwave-1: DRAIN 2/3 -> DRAIN 1/3
  +19.005 s lb1 seatwave-1: DRAIN 1/3 -> DOWN
  +22.958 s lb1 seatwave-1: DOWN -> DOWN 1/2
  +24.976 s lb1 seatwave-1: DOWN 1/2 -> UP
  +35.208 s lb1 seatwave-2: UP -> DRAIN
  +38.351 s lb1 seatwave-2: DRAIN -> DRAIN 2/3
  +42.376 s lb1 seatwave-2: DRAIN 2/3 -> DRAIN 1/3
  +46.416 s lb1 seatwave-2: DRAIN 1/3 -> DOWN
  +48.433 s lb1 seatwave-2: DOWN -> DOWN 1/2
  +50.358 s lb1 seatwave-2: DOWN 1/2 -> UP
  +60.880 s lb1 seatwave-3: UP -> DRAIN
  +63.709 s lb1 seatwave-3: DRAIN -> DRAIN 2/3
  +67.743 s lb1 seatwave-3: DRAIN 2/3 -> DRAIN 1/3
  +71.689 s lb1 seatwave-3: DRAIN 1/3 -> DOWN
  +73.710 s lb1 seatwave-3: DOWN -> DOWN 1/2
  +75.732 s lb1 seatwave-3: DOWN 1/2 -> UP
balancer lb1 during the run (backend seatwave): 314278 requests passed, 0 retries, 0 redispatches
longest gap between two answers: 120.0 ms, from +77.525 s to +77.645 s (0 failed requests sent inside it)
```

```text
floating address before: keepalived-1, so the balancer to kill is lb1
+12 s: docker compose -p seatwave-balancer kill -s KILL lb1
 Container seatwave-balancer-lb1-1  Killing
 Container seatwave-balancer-lb1-1  Killed
floating address after the kill: keepalived-2
run pair: http://10.210.10.100:8080, 3000 requests/s for 40.0 s after a 5.0 s warm-up, 50% holds; client timeouts: connect 1000 ms, request 5000 ms
sent 120000, answered 110785, failed 9215 (7.679%), not sent 0
mix: 60000 holds (POST), 60000 reads (GET), of which 0 of a hold not made yet; 0 hold replies the harness could not read
statuses: 200 55385, 201 12500, 409 42900
failures: ConnectException 18, HttpConnectTimeoutException: HTTP connect timed out 9174, IOException: HTTP/1.1 header parser received no bytes 23
latency of answered requests: p50 0.7 ms, p99 14.0 ms, max 72.7 ms
share by instance: seatwave-1 33.3% (36929), seatwave-2 33.3% (36929), seatwave-3 33.3% (36927)
share by balancer: lb1 31.4% (34781), lb2 68.6% (76004)
balancer status changes (backend seatwave):
  +11.655 s lb1 *: ANSWERING -> UNREACHABLE
balancer lb1 during the run (backend seatwave): 49661 requests passed, 0 retries, 0 redispatches
balancer lb2 during the run (backend seatwave): 76004 requests passed, 0 retries, 0 redispatches
longest gap between two answers: 3073.9 ms, from +11.594 s to +14.668 s (9214 failed requests sent inside it)
floating address after the kill: keepalived-2
run pair: http://10.210.10.100:8080, 3000 requests/s for 40.0 s after a 5.0 s warm-up, 50% holds; client timeouts: connect 1000 ms, request 5000 ms
floating address after lb1 came back: keepalived-2
```

```text
$ ./gradlew stackTest 2>&1 | grep -E 'PASSED|FAILED|BUILD'
StackTest > letsOneOfTwoInstancesWinOneSeat() PASSED
StackTest > answersTwoRacingCopiesOfOneRequestWithOneHoldOnPostgresql() PASSED
StackTest > refusesARacingKeyReusedForAnotherSeatAndKeepsThatSeatFreeOnPostgresql() PASSED
StackTest > readsAHoldThroughEveryInstanceWhicheverMadeIt() PASSED
StackTest > bothBalancersSeeThreeInstancesFullyUpInBothCheckedBackends() PASSED
StackTest > sendsNothingNewToADrainedInstance() PASSED
BUILD SUCCESSFUL in 16s
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

Adapted from the Module 9 project `seatwave-proxy`, not depended on at build time. From its first revision `e0fb70f`: `Problems.java` and `Reply.java` (simplified), `config/SettingsFiles.java`, the two-stage `Dockerfile`, the shape and guardrails of `scripts/stack.sh`, and the session and audit tooling of the course. From its review revision `2a75197` (version 1.0.1), taken in this project's version 1.1.0, unchanged except the package: `config/SettingsReader.java` (with `filePath` and the broken-escape catch in `load`), its test `SettingsReaderTest.java`, `json/JsonCodec.java` (a repeated field and text after the object are named) and its test `JsonCodecTest.java`. Taken as a pattern from that revision and written here for this project's own code: a rule between two settings keys checked only when both were read (`HarnessSettings`, `DeploySettings`, `ServiceSettings`), no leading space before a whole-body problem (`HoldRoutes`), the `useradd` line of the `Dockerfile`, a dry run that needs no Docker and `--quiet-pull` in the plan of `scripts/stack.sh`, and every file a test reads declared as an input of the test task. `config/SettingsFiles.java` no longer has a catch of its own: `SettingsReader.load` reports a broken escape, and an exception from the caller's own function is a bug that is not caught.

## What is simplified

- One shared PostgreSQL container: a single point of failure this module names and leaves to Modules 13 to 19.
- Plain HTTP inside one private network; TLS at the balancer is Module 9's.
- Both balancers and all instances run on one Docker machine and one network, so the pair's independence is far smaller than two zones'.
- The "deploy" restarts the same image; a real deploy starts a new version.
- The runtime API is a TCP socket at admin level on the lab network; production would bind it to a management network or a UNIX socket.
- Only two routes, `POST /v1/holds` and `GET /v1/holds/{holdId}` (also answered for `HEAD`).
- The labs give the seats back with one `UPDATE` between runs; a real sale never resets its seats.

## No CI on purpose

This repository has no `.github/` folder and no CI workflow, on purpose: the course's owner verifies every project locally, with the commands above.

## The Compose path

`compose.yaml` holds all the infrastructure under the project name `seatwave-balancer`; `scripts/stack.sh` starts it, tests it and brings it down with `down -v`, then checks that no container, network or volume of the project is left.

## License

MIT, see `LICENSE`.
