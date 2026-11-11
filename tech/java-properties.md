# The `.properties` format and the settings files under `config/`

## What it is

A **properties file** is a plain text file of settings. Each setting is one line with a **key** (the name) and a **value**, joined by an equals sign: `http.port=8080`. The format comes from Java. The JDK class `java.util.Properties` reads it into a table of text keys and text values.

Everything in a properties file is text. The program decides that `8080` is a number and converts it. If the text is not a number, the conversion fails, and the program must say so. In this repository the class `SettingsReader` does the conversion and the saying (`java-config-json-walkthrough.md`), and each program has a settings class that lists its keys and the range of each (`ServiceSettings`, `HarnessSettings`, `DeploySettings`).

## Why this repository uses it

Every command reads its numbers from a file in `config/`, not from the code. There are three reasons. A person can change a number without compiling anything. Every number has a comment that says why it has that value. And there are **no defaults in code**: a key that is missing is reported as a problem, never replaced silently by a hidden value, so the file is the only place a number lives. For a repository that measures a balancer, this matters: the 3,000 requests per second, the 5 s warm-up and the client timeouts of a lab are written in a file, and a reader can change one and run the lab again.

## Where it appears

| File | Read by | What it sets |
|---|---|---|
| `config/seatwave-container.properties` | `SeatWaveCli`, as the settings of every instance container | One SeatWave instance: its two ports, the worker pool, the database and its pool, the readiness check and the drain deadline. |
| `config/share-roundrobin.properties`, `share-leastconn.properties`, `share-weighted.properties`, `share-source.properties` | `HarnessCli` (the `load` task, or the `harness` container) | Lab 1: 20 s of load against one balancing algorithm each, no disruption. |
| `config/kill-active-only.properties`, `kill-production.properties`, `stop-active-only.properties`, `stop-production.properties` | `HarnessCli` | Lab 2: 30 s of load while `seatwave-2` is killed or stopped, through the active-only backend or PRODUCTION's. |
| `config/deploy-active-only.properties`, `deploy-production.properties` | `HarnessCli` | Lab 3: 100 s of load while the rolling deploy restarts all three instances. |
| `config/pair.properties` | `HarnessCli` | Lab 3: 40 s of load to the floating address while one balancer is killed. |
| `config/deploy.properties` | `DeployCli` (the `deploy` task) | The rolling deploy: which project, which instances, which balancers, and every timeout. |

Thirteen files in all. Not one of them uses a feature the others lack: they are all `key=value` lines and `#` comments.

## How it works underneath

**The format.** The reader goes through the file line by line. I ran a small program on JDK 25 to check each rule.

- A line that starts with `#` or `!` is a **comment** and is ignored. These files use `#`.
- A blank line is ignored.
- A line like `key=value` sets a key. The separator may be `=`, `:` or white space. These files use `=`. I loaded `c: z` and got the key `c` with the value `z`.
- White space around the key and before the value is dropped. White space at the **end** of a value is kept. I loaded `sp = a b ` and got `a b ` with a trailing space. That is why the settings reader calls `strip()`.
- A `#` in the middle of a line is **not** a comment: it is part of the value. I loaded `b = x # not` and got the value `x # not`. So a comment must be on a line of its own, as in every file here.
- If a key appears twice, the last one wins. I loaded `a=1` then `a=2` and got `2`.
- A backslash at the very end of a line **continues** the value on the next line, and the white space at the start of the next line is dropped. I loaded `list=one,` then a backslash, then `    two` on the next line, and got `one,two`. None of the thirteen files uses this; I searched them for a line that ends in a backslash.
- Other backslashes start an escape (`\t` is a tab). A broken `\u` escape makes `Properties.load` throw `IllegalArgumentException` with the text `Malformed \uxxxx encoding.`, which `SettingsFiles` catches and reports as a settings problem (`java-config-json-walkthrough.md`).
- Dots in a key mean nothing to the reader. `load.ratePerSecond` is just that name. The dots are a naming habit that groups related keys: `http.` for the listeners, `db.` for the database, `load.` for the load, `watch.` for the balancer watch.
- A lookup of a key that is not there returns `null`. I printed it and got `null`. The settings reader turns that into the problem line `<key> is missing`.

**What the program does with it.** `SettingsReader.load` opens the file as UTF-8 text and passes the reader to `Properties.load`. A path in a settings file is relative to the folder the program starts in. Each Gradle run task sets `workingDir = rootDir` (`gradle.md`), so `build/stack/audit.log` means the folder `build/stack` of the project. Inside the containers the files sit under `/app/config` and `db.passwordFile` is an absolute path.

**Units are in the key name.** A key that ends in `Millis` is a number of milliseconds, a key that ends in `Percent` is a percentage, and `Port` is a port number. A reader never has to guess a unit. The settings reader turns a `Millis` key into a `Duration`.

**A value can be a list.** A comma-separated value is read as a list: `deploy.instances=seatwave-1,seatwave-2,seatwave-3` is three names, and `deploy.balancers=lb1=127.0.0.1:21005,lb2=127.0.0.1:21015` is two entries of the form `name=address:port`. The reader strips the spaces around each item.

**Every key is required.** The first comment of most files says so. Delete a line and the command prints `<key> is missing` and exits with status 2. One run reports all problems at once. I ran this with a damaged copy of `kill-production.properties` (the rate set to 0, the `report.victim` line deleted, and the request timeout lowered to 500, below the connect timeout of 1000):

```
the settings in ../demo/bad.properties have 3 problem(s):
  load.ratePerSecond must be from 1 to 20000, but was 0
  report.victim is missing
  load.requestTimeoutMillis must be longer than load.connectTimeoutMillis, or no request that needs a new connection can finish
```

Gradle then reports `finished with non-zero exit value 2`. With a file that does not exist the program prints `the settings file config/missing.properties does not exist`.

## Walkthrough of this repository's use

### `config/seatwave-container.properties`

The whole file:

```properties
# One SeatWave instance inside its container (compose.yaml); the instance name is the second
# argument. Every key is required; there are no defaults in code. It binds every address inside the
# container, and compose.yaml publishes none of its ports: only the balancers reach it.
http.bindAddress=0.0.0.0
# Requests from the balancers.
http.port=8080
# /health, /ready and /metrics on their own thread; the balancers check /ready here.
http.adminPort=8081
http.backlog=1024
# Module 4's 64 worker threads, and a bounded queue in front of them.
http.workerThreads=64
http.queueCapacity=1024
# The shared database of compose.yaml, and the password file Compose mounts from build/secrets.
db.url=jdbc:postgresql://10.210.10.5:5432/seatwave
db.user=seatwave
db.passwordFile=/run/secrets/db_password
# 16 connections per instance, 48 for three, under PostgreSQL's default limit of 100.
db.poolSize=16
# At least 250: HikariCP refuses a shorter connection timeout.
db.connectTimeoutMillis=1000
readiness.checkTimeoutMillis=1000
# The longest a shutdown waits for in-flight requests. With the 5 s the workers get after it, a stop
# takes 25 s at most, inside compose.yaml's stop_grace_period of 30 s.
drain.deadlineMillis=20000
```

`SeatWaveCli` reads this file, with the instance name (`seatwave-1`, `seatwave-2` or `seatwave-3`) as its second argument, and `ServiceSettings.from` checks every key. The three comment lines at the top say that the instance binds every address inside its container and that `compose.yaml` publishes none of its ports.

- `http.bindAddress=0.0.0.0` is the address the two listeners accept connections on. `0.0.0.0` means "every address of this machine". Inside a container that is the only way the balancers can reach it: they connect to the container's own address on the lab network. The value must be an IP address; a host name is refused because the server would need DNS before it could start.
- `http.port=8080` is the port for the real requests, `POST /v1/holds` and `GET /v1/holds/{holdId}`. A **port** is a number from 0 to 65,535 that names one door of a machine; 0 would mean "any free port".
- `http.adminPort=8081` is the second listener, for `/health`, `/ready` and `/metrics`. It has a thread of its own, so a busy instance still answers the balancer's checks. The reader refuses two equal non-zero ports.
- `http.backlog=1024` is the **backlog**: how many connections the operating system keeps waiting for each listener until the server accepts them. The range is 1 to 65,535.
- `http.workerThreads=64` is the number of threads that handle requests (Module 4's 64). The range is 1 to 1,024. `http.queueCapacity=1024` is the length of the queue in front of them (1 to 100,000): when all 64 are busy, requests wait there, and when it is full the next one is refused.
- `db.url=jdbc:postgresql://10.210.10.5:5432/seatwave` is where the shared database is. **JDBC** is Java's standard way to talk to a database; the URL names the database program (`postgresql`), its address on the lab network (`10.210.10.5`), its port (`5432`) and the database name (`seatwave`). The reader accepts only a PostgreSQL URL of that shape or an in-memory H2 URL (`postgresql-and-h2.md`).
- `db.user=seatwave` is the database account. The pattern allows lower-case letters, digits and underscores.
- `db.passwordFile=/run/secrets/db_password` is a path, not the password. Docker Compose mounts the password file made by `scripts/stack.sh` at that place (`docker-compose.md`). The program reads the file, so the password is never in a settings file or on a command line.
- `db.poolSize=16` is the number of database connections one instance keeps open (`hikaricp.md`). The comment gives the arithmetic: 3 instances times 16 is 48, under PostgreSQL's default limit of 100 connections. The reader also refuses a pool larger than the worker count, because a connection no worker can use would be held open for nothing.
- `db.connectTimeoutMillis=1000` is the longest a request waits for a free connection from the pool: one second. The comment above it gives the lower bound: HikariCP, the pool library, refuses a connection timeout shorter than 250 milliseconds, so the reader refuses one too (`hikaricp.md`).
- `readiness.checkTimeoutMillis=1000` is the longest the `/ready` check waits for the database. The reader refuses anything under 1000, because the JDBC call it uses counts whole seconds.
- `drain.deadlineMillis=20000` is the longest a shutdown waits for requests in flight: 20 seconds. The two comment lines above it add the rest of a stop: after the drain, the instance gives its worker threads 5 more seconds to finish. So a stop takes 25 s at most, inside `compose.yaml`'s `stop_grace_period` of 30 s, and Docker never kills an instance that is still draining (`docker-compose.md` shows the other half of the arithmetic, and `ServiceSettingsTest` checks it).

### `config/kill-production.properties`, a harness file key by key

All eleven harness files have the same fifteen keys. This is one of them, whole:

```properties
# Lab 2: the same kill behind PRODUCTION's backend (port 8080): passive check and retries.
run.label=kill-production
target.url=http://10.210.10.21:8080
load.ratePerSecond=3000
# 5 s of warm-up first, left out of the report: JIT compilers and the client's connections.
load.warmupMillis=5000
load.durationMillis=30000
load.holdPercent=50
watch.balancers=lb1=10.210.10.21:9999
watch.backend=seatwave
report.victim=seatwave-2
# Client timeouts of a fan's app: 1 s to connect, 5 s for the whole request.
load.connectTimeoutMillis=1000
load.requestTimeoutMillis=5000
load.maxInFlight=4000
load.seed=22
watch.intervalMillis=100
report.timelineMillis=1000
```

`HarnessCli` reads it and `HarnessSettings.from` checks every key.

- `run.label=kill-production` names the run. It appears in the report's first line and is a short word of lower-case letters, digits and hyphens.
- `target.url=http://10.210.10.21:8080` is where the load goes: plain HTTP to an address on the lab network. `10.210.10.21` is the first balancer, `lb1`, and `8080` is its PRODUCTION frontend port. The pattern accepts only `http://` followed by an address and a port.
- `load.ratePerSecond=3000` is how many requests per second the harness starts, whatever the answers do. That is an **open-loop** load (`measuring-a-balancer.md`). The range is 1 to 20,000.
- `load.warmupMillis=5000` is the warm-up the comment explains: 5 seconds first, left out of the report, for the JIT compiler (`java.md`) and the client's connections.
- `load.durationMillis=30000` is the measured part: 30 seconds. The planned number of requests is the rate times warm-up plus duration, so 3,000 times 35 gives 105,000.
- `load.holdPercent=50` is the share of requests that are `POST /v1/holds`; the rest read a recent hold with `GET`. The range is 0 to 100.
- `watch.balancers=lb1=10.210.10.21:9999` is the list of balancers to watch, each as `name=address:port`. Port 9999 is the HAProxy runtime API inside the lab network; the harness asks it for the server table (`java-haproxy-client-walkthrough.md`).
- `watch.backend=seatwave` is the backend whose servers' state changes are recorded.
- `report.victim=seatwave-2` is the instance whose removal the report analyses (the last answer it gave, when the balancer took it out). The word `none` means no analysis.
- `load.connectTimeoutMillis=1000` and `load.requestTimeoutMillis=5000` are the client timeouts the comment calls a fan's app: 1 second to connect, 5 seconds for the whole request. The request timeout must be longer than the connect timeout.
- `load.maxInFlight=4000` is the most requests allowed to be open at once. When all 4,000 slots are in use, a due request is counted as "not sent" and the report says so. The range is 1 to 10,000.
- `load.seed=22` is a number that shifts which seat each request asks for, so two runs do not ask for the same seats. The range is 0 to 1,000,000.
- `watch.intervalMillis=100` is how often the harness asks each balancer for its server table: every 100 ms, with 10 ms as the lowest allowed.
- `report.timelineMillis=1000` is the step of the per-second timeline in the report.

### The other ten harness files

Each differs from `kill-production.properties` in a few lines, so only the lines that differ are quoted, with the first comment line of the file. Every other line is the same as above.

**Lab 1, traffic share.** The harness runs 20 s with no disruption and prints how the requests were spread over the three instances.

`share-roundrobin.properties` aims at PRODUCTION's frontend (port 8080), so its `target.url` and `watch.backend` lines equal the ones above and are not repeated:

```properties
# Lab 1: traffic share under round robin (port 8080, PRODUCTION's backend).
run.label=share-rr
load.durationMillis=20000
report.victim=none
load.seed=11
```

`share-leastconn.properties` aims at the least-connections frontend (8081) and watches its backend:

```properties
# Lab 1: traffic share under least connections (port 8081).
run.label=share-lc
target.url=http://10.210.10.21:8081
load.durationMillis=20000
watch.backend=seatwave_leastconn
report.victim=none
load.seed=12
```

`share-weighted.properties` aims at the weighted frontend (8084), where the servers have weights 2, 1 and 1:

```properties
# Lab 1: traffic share under weighted round robin, weights 2, 1 and 1 (port 8084).
run.label=share-weighted
target.url=http://10.210.10.21:8084
load.durationMillis=20000
watch.backend=seatwave_weighted
report.victim=none
load.seed=13
```

`share-source.properties` aims at the frontend that hashes the client's address (8083), so one client always meets one instance:

```properties
# Lab 1: traffic share when the balancer hashes the client's address (port 8083).
run.label=share-source
target.url=http://10.210.10.21:8083
load.durationMillis=20000
watch.backend=seatwave_source
report.victim=none
load.seed=14
```

In all four, `report.victim=none` says nothing is removed, and the seeds 11 to 14 differ so the runs ask for different seats.

**Lab 2, losing an instance.** `kill-production.properties` is quoted above. The others change the target backend or the way the instance leaves:

```properties
# Lab 2: kill seatwave-2 under load behind the active-only backend (port 8082): no retries.
run.label=kill-active
target.url=http://10.210.10.21:8082
watch.backend=seatwave_active_only
load.seed=21
```

`kill-active-only.properties` points at the active-only frontend (port 8082), whose backend has the health check alone and no retries.

```properties
# Section 9: seatwave-2 stopped (SIGTERM) with no drain at the balancer, port 8082: no retries.
run.label=stop-active
target.url=http://10.210.10.21:8082
watch.backend=seatwave_active_only
load.seed=33
```

`stop-active-only.properties` is the same frontend, but the instance is stopped with SIGTERM instead of being killed, so it drains itself, and the balancer learns about it only from its own checks and from the `503` answers the draining instance gives (the file's first comment says there is no drain at the balancer).

```properties
# Section 9: the same stop through PRODUCTION's backend (port 8080).
run.label=stop-production
load.seed=34
```

`stop-production.properties` is the stop through PRODUCTION's frontend (port 8080).

**Lab 3, a deploy and a balancer failure.**

```properties
# Lab 3: load through PRODUCTION's backend while the rolling deploy restarts all three instances.
run.label=deploy-production
load.durationMillis=100000
report.victim=none
load.seed=31
```

`deploy-production.properties` runs 100 s of load through PRODUCTION's frontend while the rolling deploy restarts all three instances one at a time. `report.victim=none`, because the instances leave one after the other and no single removal is analysed.

```properties
# Lab 3: the same deploy, measured through the active-only backend (port 8082): no retries at all.
run.label=deploy-active
target.url=http://10.210.10.21:8082
load.durationMillis=100000
watch.backend=seatwave_active_only
report.victim=none
load.seed=32
```

`deploy-active-only.properties` is the same deploy measured through the active-only frontend (port 8082).

```properties
# Lab 3: load to the floating address while the balancer that holds it is killed (scripts/lab.sh
# pair reads which one holds it first).
run.label=pair
target.url=http://10.210.10.100:8080
load.durationMillis=40000
watch.balancers=lb1=10.210.10.21:9999,lb2=10.210.10.22:9999
report.victim=none
load.seed=41
```

`pair.properties` sends 40 s of load to the **floating address** `10.210.10.100` and watches both balancers (`lb1` and `lb2`, as two entries in `watch.balancers`). The two comment lines say it exactly: the load goes to the floating address while the balancer that holds it is killed, and `scripts/lab.sh pair` first reads which one holds it. In the re-measured run of version 1.1.0 that was `keepalived-1`, so `lb1` was killed. (Version 1.0.0's comment said "while lb1 is killed", although that run killed `lb2`.)

### `config/deploy.properties`

```properties
# Lab 3's rolling deploy, run on this machine (./gradlew -q deploy --args='config/deploy.properties').
# The only project it touches; Compose service names are also the balancers' server names.
compose.project=seatwave-balancer
deploy.instances=seatwave-1,seatwave-2,seatwave-3
# Backends with their own copy of each server; the others track seatwave and follow it.
deploy.backends=seatwave,seatwave_active_only
# Both balancers' runtime APIs, published on 127.0.0.1 only (compose.yaml).
deploy.balancers=lb1=127.0.0.1:21005,lb2=127.0.0.1:21015
deploy.drainTimeoutMillis=10000
# Longer than the instance's drain.deadlineMillis (20 s), so docker never kills a draining instance.
deploy.stopTimeoutMillis=30000
deploy.upTimeoutMillis=30000
# HAProxy's slowstart (10 s): the next instance waits until this one carries its full share.
deploy.settleMillis=10000
deploy.pollMillis=100
deploy.auditFile=build/stack/audit.log
```

`DeployCli` reads it and `DeploySettings.from` checks every key. The first comment shows the command: `./gradlew -q deploy --args='config/deploy.properties'`.

- `compose.project=seatwave-balancer` names the Compose project the deploy may touch. The reader accepts only that exact name, so the tool cannot restart containers of another project.
- `deploy.instances=seatwave-1,seatwave-2,seatwave-3` is the list of instances to restart, in this order, one at a time. A name twice is refused. The comment explains that the Compose service names are also the balancers' server names.
- `deploy.backends=seatwave,seatwave_active_only` are the HAProxy backends that hold their own copy of each server. The other backends track `seatwave` and follow it (`haproxy-config-walkthrough.md`).
- `deploy.balancers=lb1=127.0.0.1:21005,lb2=127.0.0.1:21015` are the runtime API addresses of both balancers as the host sees them: the container ports are published on `127.0.0.1` only (`compose.yaml`).
- `deploy.drainTimeoutMillis=10000` is how long the deploy waits for the instance's sessions at the balancers to reach zero after it sets the instance to drain.
- `deploy.stopTimeoutMillis=30000` is the stop timeout handed to Docker. The comment says it is longer than the instance's own drain deadline of 20 s (`drain.deadlineMillis` above), so Docker never kills a draining instance. The reader refuses less than 1000, because Docker counts whole seconds.
- `deploy.upTimeoutMillis=30000` is how long the deploy waits for a restarted instance to be seen `UP` by the balancers.
- `deploy.settleMillis=10000` is the pause after an instance is up. The comment ties it to HAProxy's `slowstart` of 10 s: the next instance waits until this one carries its full share.
- `deploy.pollMillis=100` is how often the deploy asks the balancers for their server table. The lowest allowed is 10.
- `deploy.auditFile=build/stack/audit.log` is the file that gets one line for the start and one for the end of a deploy.

## Try it yourself

All commands ran in a scratch copy of this repository on 11 November 2026.

1. Count the keys of each file:

```
for f in config/*.properties; do echo "$f $(grep -c '^[a-z]' $f)"; done
```

   Expected: `15` for each of the eleven harness files, `10` for `config/deploy.properties` and `13` for `config/seatwave-container.properties`.

2. Compare two harness files:

```
diff config/kill-production.properties config/kill-active-only.properties
```

   Expected: differences in the comment (line 1), `run.label`, `target.url`, `watch.backend` and `load.seed`, and in nothing else.

3. Run the load task with a file that does not exist:

```
./gradlew --offline -q load --args='config/missing.properties'
```

   Expected: the line `the settings file config/missing.properties does not exist`, then Gradle's failure report.

4. Break a copy on purpose. Make a copy of `config/kill-production.properties` in a folder outside `config/`, set `load.ratePerSecond=0`, delete the line `report.victim=seatwave-2`, and run `./gradlew --offline -q load --args='<your copy>'`. Expected: the three problem lines shown in "How it works underneath" (the third needs the request timeout lowered to 500 too), and `finished with non-zero exit value 2`. The run stops before it sends a single request.

5. Run the tests that read the container settings file (they need no Docker):

```
./gradlew --offline test --tests '*ServiceSettingsTest' --tests '*SeatWaveCliTest'
```

   Expected: `BUILD SUCCESSFUL` and ten `PASSED` lines. These tests read `config/seatwave-container.properties` from the real folder, so a damaged file there breaks a test. No test reads the harness files or `deploy.properties`; `HarnessSettings` and `DeploySettings` are tested with settings built in the test itself.

## Mistakes people make

- **Putting a comment after a value.** `http.backlog=1024 # queue` makes the value `1024 # queue`, which is not a number. Comments go on their own line.
- **A trailing space after a value.** The JDK keeps it. The settings reader strips it, but another program may not.
- **Using a host name where an address is required.** `http.bindAddress` and the balancer entries take IP addresses only.
- **Changing a lab value without its comment.** The comment holds the reason; a changed number needs a changed reason. The 48-connection arithmetic of `db.poolSize` is an example.
- **Setting a request timeout at or below the connect timeout.** The reader refuses it, for a reason that is in the message.
- **Pointing `deploy.balancers` at the container ports.** The deploy runs on the host, so it uses the published loopback ports 21005 and 21015, not 9999.
- **Expecting a default.** There is none. A missing key is an error.

## Check yourself

1. *Which character starts a comment?* `#` (or `!`), and only at the start of a line.
2. *What does a backslash at the end of a line do?* It continues the value on the next line. None of these files uses it.
3. *Why does the container file bind `0.0.0.0`?* Inside a container only a bound-to-everything address can be reached from the balancers on the lab network.
4. *Why is `db.poolSize` 16?* Three instances times 16 is 48 connections, under PostgreSQL's default limit of 100.
5. *What does `report.victim=none` mean?* The report makes no analysis of one instance's removal; the other lines of the report are the same.

## Further reading

- `java.util.Properties` (JDK 25), including the exact `load` rules: <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Properties.html>
- `java.nio.file.Files` and `Path` (JDK 25): <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html>
