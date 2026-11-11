# Measuring a load balancer, from zero

## What it is

To **measure** a load balancer is to answer questions with numbers instead of feelings. When one of three servers dies, how many requests fail? How long until the balancer stops sending requests to the dead server? Can you replace every server one after another without a single failed request? If the balancer itself dies, how long are the users without service?

Each question needs a way to produce the numbers and a way to read them. This note explains both, from zero, for the tools of this repository: the `harness` package (it sends load and writes a report), the `haproxy` package (it reads the balancer's own table), and the `deploy` package (it replaces the servers). The numbers quoted here come from the README's captured runs on the Docker stack. I did not run Docker; the stack-only tools are explained from the README's captured output. Where I could, I ran the same harness code against a small stand-in on my own computer, and I say so.

## Why this repository uses it

Module 10 is built on a claim you can check: spreading the load over several servers behind a redundant pair of balancers survives a failure without hurting users, if the balancer is set up well. A claim without a number is an opinion. The module gives each claim a number, and it makes the numbers repeatable, so that you can change one setting and see the number move.

Four numbers carry the module:

| Question | Number | Where it comes from |
|---|---|---|
| A server is killed. How many requests fail? | Failed requests | `failed 11777` and `failed 0` in the README |
| How long until the balancer stops using it? | Detection time | `11824.2 ms` and `13.4 ms` in the README |
| Can all servers be replaced with no failure? | Failed requests during a deploy | `failed 0` over 300,000 requests |
| The balancer dies. How long is the service gone? | The failover gap | `2777.1 ms` in the README |

## Where it appears

| File | What it does for measuring |
|---|---|
| `harness/LoadRun.java` | Sends requests at a fixed rate and records each outcome. |
| `harness/BalancerWatch.java` | Reads the balancer's table ten times a second and notes status changes. |
| `harness/RunReport.java` | Computes failures, shares, latency, detection time and the longest gap. |
| `deploy/RollingDeploy.java` | Replaces the servers one by one and logs each step with a time. |
| `config/*.properties` | One settings file per lab (rate, duration, victim). |
| `scripts/lab.sh` | Does the disruption (kill, stop, deploy) at a fixed time inside a run. |
| `haproxy/haproxy.cfg` | The balancer settings the labs compare. |
| `keepalived/lb1.conf`, `keepalived/lb2.conf` | The floating address that moves when a balancer dies. |
| `README.md` | The captured output of every lab. |

## How it works underneath

### 1. What to measure

For a service behind a balancer, four kinds of numbers matter.

- **Correctness of the outcome**: did the request get an answer a user can use? Here an answer below status 500 counts. Status 409 ("the seat is taken") is the service working. A 502 or 503, a timeout or a closed connection counts as a failure.
- **Time**: how long a request took, and how long a repair took.
- **Share**: how the requests were divided among the servers.
- **Cost**: how much processor time the work took. This repository does not measure it; see section 7.

### 2. Open loop and closed loop

A **load** is a stream of requests sent on purpose. There are two ways to send it.

In a **closed loop**, a fixed number of clients each send a request, wait for the answer, and send the next one. If the server slows down, the clients slow down too, because they wait. The load the server sees falls exactly when the server is in trouble. This hides a stall: the requests that would have been delayed are never sent. The effect has a name, **coordinated omission**.

In an **open loop**, requests are started at planned moments, one every 333,333 nanoseconds for 3,000 per second, whatever the answers do. If the server stalls, requests pile up, and the stall shows as latency and as failures. This is what a crowd of fans does: they do not wait for each other. The harness is open-loop (`LoadRun`).

An open loop needs a guard. If the server never answers, the number of waiting requests grows without limit. So the harness has `load.maxInFlight` (4,000 in the lab files). A request that is due when 4,000 are waiting is counted as "not sent" and shown in the report. In every captured run, `not sent` is 0, so the guard never fired.

The harness also **times a request from the moment it was due**, not from the moment it was sent. If the harness itself was late, the lateness counts as latency, and it cannot hide.

### 3. Warm-up

A Java program is slow in its first seconds: its code is still being compiled to fast machine code, and no connection is open yet. So every lab sends 5 seconds of the same load first (`load.warmupMillis=5000`) and leaves them out. Time zero is the end of the warm-up. In the report, `+10 s` means 10 seconds after time zero.

### 4. Failed requests during a kill

A **kill** here is `docker compose kill -s KILL seatwave-2`: the process is ended at once, with no chance to say goodbye. For the balancer, the server simply stops answering. The balancer does not know yet. It keeps sending its share of requests, and each of those requests fails, until the balancer decides the server is dead.

That gives a rule of thumb:

> failed requests = request rate x the dead server's share x the time until the balancer notices

The module calls this "Little's Law without retries" (the report prints it). It is really arrival rate times time. The report prints the forecast next to the real number, so you can see whether they agree. In the active-checks run: 3,000 x 0.333 x 11.824 s = 11,824, and the real count of failures sent in between was 11,773. The forecast is 0.4 percent too high. It fits because that backend does not retry.

Two settings change the answer, and the module compares them. Both backends are in `haproxy.cfg`:

```text
backend seatwave
  balance roundrobin
  option httpchk
  http-check send meth GET uri /ready
  http-check expect status 200
  # Two more attempts, each on another instance (redispatch on every retry).
  retries 2
  option redispatch 1
  # Retried only when SeatWave did no work (no connection, or its 503 "not ready, nothing done")
  # or when the connection died before any byte of an answer. A hold carries an Idempotency-Key,
  # so even the last case cannot hold a seat twice: the shared database replays the first hold.
  retry-on conn-failure empty-response 503
  default-server check port 8081 inter 2s fall 3 rise 2 slowstart 10s observe layer4 error-limit 3 on-error mark-down
  server seatwave-1 10.210.10.11:8080
  server seatwave-2 10.210.10.12:8080
  server seatwave-3 10.210.10.13:8080
```

```text
backend seatwave_active_only
  balance roundrobin
  option httpchk
  http-check send meth GET uri /ready
  http-check expect status 200
  retries 0
  retry-on none
  default-server check port 8081 inter 2s fall 3 rise 2
  server seatwave-1 10.210.10.11:8080
```

- The backend `seatwave_active_only` has only the **active check**: every 2 seconds (`inter 2s`) HAProxy asks the server `GET /ready`; three failures in a row (`fall 3`) mark it `DOWN`; two successes (`rise 2`) mark it `UP`. There are no retries (`retries 0`, `retry-on none`).
- The backend `seatwave` (production) adds a **passive check** and **retries**. `observe layer4 error-limit 3 on-error mark-down` means: watch the real traffic; if three connections in a row to the server fail, mark it down at once, without waiting for the active check. `retries 2` with `retry-on conn-failure empty-response 503` means: if a request could not connect, got no answer, or got a 503 from the server, send it again, to another server (`option redispatch 1`). `slowstart 10s` means: when a server comes back, raise its share step by step over 10 seconds.

Retrying a request is safe here only because a hold carries an `Idempotency-Key`. If HAProxy resends a `POST` whose answer was lost, the database replays the first hold and does not hold a second seat. The comment in the file says so.

### 5. Detection time

**Detection time** is the time from the moment the server stopped answering to the moment the balancer took it out. The harness measures it from two clocks. The load records when the victim's last answer arrived. The watch records when the balancer's table changed the victim to `DOWN`. The report subtracts. The caveat: the table is read every 100 ms, and a reading is stamped when it started, so the removal time can be early by up to the length of one reading.

The active-checks arithmetic: 3 failed checks at 2 seconds apart should take about 6 seconds. The README measured 11.8. The balancer's own settings suggest why: `timeout check 1s` and `timeout connect 2s` mean a check against a dead address waits before it counts as failed, and the next check starts after the interval. That makes each failed check last about 4 seconds instead of 2. This is my reading, not something I observed: the README does not explain the 11.8 seconds, and I did not run the stack. The status changes in the README fit it, with 4 seconds between each: `UP -> UP 2/3` at +13.598 s, `UP 2/3 -> UP 1/3` at +17.580 s, `UP 1/3 -> DOWN` at +21.575 s.

The passive check needs no waiting: three failed connections, which at 3,000 requests per second happen in a millisecond or two, mark the server down. The README's production run measured 13.4 ms.

### 6. The pair of balancers, and the failover gap

One balancer is a single point of failure, so the module runs two (`lb1` and `lb2`) and one **floating address**, 10.210.10.100. The clients use only that address. At any moment one balancer owns it. A program called **keepalived** runs beside each balancer and speaks **VRRP**, the Virtual Router Redundancy Protocol: the owner announces "I am alive" once a second (`advert_int 1`). If the backup hears nothing for three announcements plus a small extra (the standard, RFC 5798, gives `(256 - priority) / 256` of a second), it takes the address. For `lb1`, with priority 101, that is about 3.6 seconds after the last announcement.

The **failover gap** is the stretch in which nobody owns the address and so nobody answers. Packets sent to it are lost; a client's connection attempt gets no reply and waits for its connect timeout. The harness measures it as the longest time between two consecutive answers (`longest gap between two answers`), and counts the requests that failed inside it.

### 7. Cost: processor time per thousand requests

A balancer that is fast but eats a whole processor is expensive. The usual number is **CPU milliseconds per thousand requests**: the processor time a container used during a run, divided by the number of requests, times a thousand. Smaller is cheaper. It needs two readings of the container's processor counter, one before and one after the run.

This repository does not do that. I searched the code: the harness records latency, failures and shares, and no processor time. What it does is make such a number meaningful if you take it. Each balancer runs one thread (`nbthread 1` in `haproxy.cfg`) on one processor (`cpus: 1` in `compose.yaml`), and the comment says "so every balancer number in the module is per core". The Module 9 project, `seatwave-proxy`, has a bench that measures processor time per thousand requests for a proxy, and its note `measuring-a-proxy.md` explains how. Do not read a CPU figure into the numbers of this note.

### 8. What can mislead you

- **One run is one sample.** The README shows one run per lab. A second run would differ in the last digits. The relations (production is far better than active-only, a deploy fails nothing) are not in doubt; the third digit is.
- **The Docker machine is shared.** The balancers, the instances, the database and the harness share one virtual machine with 12 processors. The README says other programs were running.
- **A status other than 5xx is an answer.** The reports show only 409 for the kill labs. That is not an error.
- **The mix is not always the stated mix.** The settings say 50 percent holds. A read can only be sent after the run has created a hold. The kill labs in the README show `409` only, so they sent only `POST` requests (see `java-harness-walkthrough-2.md`).
- **One request less than planned.** The README prints `sent 89999` for 30 seconds at 3,000 per second. The tool rounds the interval down. It is a rounding effect of one request.
- **Averages hide.** The report prints p50, p99 and the maximum.

## Walkthrough of this repository's use

Each block below is the README's captured output of one lab, with my reading. I did not run any of them: they need the Docker stack. I checked the arithmetic of each with a calculator.

### Lab 1: how the load is shared

```text
share by instance: seatwave-1 33.3% (20000), seatwave-2 33.3% (20000), seatwave-3 33.3% (19999)
share by instance: seatwave-1 33.5% (20110), seatwave-2 33.3% (19960), seatwave-3 33.2% (19929)
share by instance: seatwave-1 50.0% (30000), seatwave-2 25.0% (15000), seatwave-3 25.0% (14999)
share by instance: seatwave-2 100.0% (59999)
```

Four runs of 20 seconds at 3,000 requests per second. The settings files are `share-roundrobin`, `share-leastconn`, `share-weighted` and `share-source`. Round robin gives each instance a third. Least connections gives a third each, within a percent: the instances answer equally fast, so the counts stay level. The weighted run has weights 2, 1 and 1 (see `haproxy.cfg`) and gives 50, 25 and 25 percent. Source hashing sends every request of one client address to one instance, and there is only one client address (the harness), so one instance gets 100 percent. This proves what the algorithm does when there is a single client. It does not mean source hashing is bad.

### Lab 2: kill an instance, active checks only

```text
+10 s: docker compose -p seatwave-balancer kill -s KILL seatwave-2
 Container seatwave-balancer-seatwave-2-1  Killing
 Container seatwave-balancer-seatwave-2-1  Killed
run kill-active: http://10.210.10.21:8082, 3000 requests/s for 30.0 s after a 5.0 s warm-up, 50% holds; client timeouts: connect 1000 ms, request 5000 ms
sent 89999, answered 78222, failed 11777 (13.086%), not sent 0
statuses: 409 78222, 502 1, 503 3305
failures: HTTP 502 1, HTTP 503 3305, IOException: HTTP/1.1 header parser received no bytes 8471
latency of answered requests: p50 0.8 ms, p99 26.6 ms, max 126.1 ms
```

The first line is the lab script's: at +10 s it runs `docker compose kill -s KILL seatwave-2`. Then the harness's summary:

- `run kill-active: http://10.210.10.21:8082, 3000 requests/s for 30.0 s after a 5.0 s warm-up, 50% holds; client timeouts: connect 1000 ms, request 5000 ms`. Port 8082 is the active-only door of `lb1`.
- `sent 89999, answered 78222, failed 11777 (13.086%), not sent 0`. 11,777 of 89,999 is 13.086 percent.
- `statuses: 409 78222, 502 1, 503 3305`. Every answer was a 409 (the seat is taken). The failures are 3,305 answers of 503 and 1 of 502, and
- `failures: ... IOException: HTTP/1.1 header parser received no bytes 8471`: requests whose connection closed without an answer. The README does not say why some failures are 503 and some are closed connections; I can only report both.
- `latency of answered requests: p50 0.8 ms, p99 26.6 ms, max 126.1 ms`: the middle request took 0.8 ms and 99 in 100 took 26.6 ms or less.

```text
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

- `share by instance`: `seatwave-2` answered 12.5 percent (9,746 answers). It carried a third of 3,000 requests per second, about 1,000 a second, until its last answer at +9.75 s, and it answered nothing after that. `share by balancer: lb1 100.0%`: only `lb1` was used.
- `balancer status changes`: `+13.598 s UP -> UP 2/3`, `+17.580 s UP 2/3 -> UP 1/3`, `+21.575 s UP 1/3 -> DOWN`. Four seconds apart, as discussed above.
- `105000 requests passed, 0 retries, 0 redispatches`: `lb1` passed 105,000 requests during the run (30 s plus the 5 s warm-up at 3,000 per second). With `retries 0`, none were retried.
- `removal of seatwave-2`: last answer at +9.750 s, taken out at +21.575 s, 11,824.2 ms later; 11,773 failed requests sent in between and 0 after. The forecast is 11,824.
- `longest gap between two answers: 115.8 ms`: the service never went silent. Two thirds of the requests still got answers, so the gap between two answers is small, and 117 failed requests were sent inside it.

### Lab 2: the same kill behind production

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

The only change is port 8080, the production backend. `failed 0`, `statuses: 409 89999`. The status table: `+9.769 s lb1 seatwave-2: UP -> DOWN`, 13.4 ms after the last answer at +9.756 s. `6 retries, 9 redispatches` are the balancer's own work: requests it sent again to another server. The users saw none of it. The worst latency was 16.9 ms, against 126.1 ms in the active-only run.

### Lab 3: a rolling deploy

```text
+8 s: ./gradlew -q deploy --args=config/deploy.properties --run --yes
plan: restart [seatwave-1, seatwave-2, seatwave-3] of project seatwave-balancer one at a time, on balancers [lb1, lb2], backends [seatwave, seatwave_active_only]
  each: drain, wait for 0 sessions, stop (SIGTERM), wait until seen down, start, ready, wait until up, settle 10000 ms
```

The deploy tool prints its plan, then each step with the elapsed time. Read the first instance:

```text
+     31.5 ms  seatwave-1: drain set on 2 balancer(s)
+     43.6 ms  seatwave-1: no session left on any balancer
+    267.4 ms  seatwave-1: stopped (SIGTERM; the instance drained itself)
+  11935.8 ms  seatwave-1: seen down by every balancer's checks
+  12647.1 ms  seatwave-1: started
+  12651.9 ms  seatwave-1: ready set; the checks decide when it gets traffic again
+  15999.5 ms  seatwave-1: up on every balancer; slow start ramps its weight
```

- `+ 31.5 ms drain set on 2 balancer(s)`: the `DRAIN` command was sent to `lb1` and `lb2`.
- `+ 43.6 ms no session left on any balancer`: no request was running on `seatwave-1`.
- `+ 267.4 ms stopped (SIGTERM; the instance drained itself)`: `docker compose stop` took 224 ms.
- `+ 11935.8 ms seen down by every balancer's checks`: 11.7 seconds after the stop. This is the active check again: three failed checks, 4 seconds each. The instance is drained, so no request goes there while the balancer learns that it is gone.
- `+ 12647.1 ms started`, `+ 12651.9 ms ready set`, `+ 15999.5 ms up on every balancer`: the start took 0.7 s, and the checks needed 3.3 seconds to see two good results.
- The next instance starts at +26006.1 ms: 10 seconds after `up`, which is the settle time (`deploy.settleMillis=10000`) and the length of HAProxy's slow start.

The other two instances follow the same pattern:

```text
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
```

The three instances took 66.7 seconds in total, and `deploy finished: 3 instances restarted, all up` is the tool's last line.

```text
run deploy-production: http://10.210.10.21:8080, 3000 requests/s for 100.0 s after a 5.0 s warm-up, 50% holds; client timeouts: connect 1000 ms, request 5000 ms
sent 299999, answered 299999, failed 0 (0.000%), not sent 0
statuses: 200 150000, 201 9997, 409 140002
failures: none
latency of answered requests: p50 0.7 ms, p99 4.1 ms, max 132.2 ms
share by instance: seatwave-1 33.2% (99694), seatwave-2 33.6% (100936), seatwave-3 33.1% (99369)
share by balancer: lb1 100.0% (299999)
```

The harness ran at the same time (100 seconds at 3,000 per second). `sent 299999, answered 299999, failed 0`. Statuses 200 (reads), 201 (holds created) and 409. The 9,997 holds created are close to the 10,000 distinct seats that the hold requests can reach (see `java-harness-walkthrough-2.md`). The shares are 33.2, 33.6 and 33.1 percent: every instance served about a third even though each was out of rotation for roughly 20 seconds, because the share is counted over the whole run.

```text
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
```

These are the status changes seen from `lb1` for the three instances. For `seatwave-1`: `UP -> DRAIN` at +8.356 s (the deploy's drain), `DRAIN -> DRAIN 2/3` and `DRAIN 2/3 -> DRAIN 1/3` as the checks failed after the stop, `DRAIN -> ... -> DOWN` at +19.869 s, then after the start `DOWN -> DOWN 1/2` and `DOWN 1/2 -> UP` at +23.912 s. The same sequence follows for the other two. Note that no line says `UP -> DOWN`: a server was never taken out by failure, it was drained first. That is why nothing failed.

```text
balancer lb1 during the run (backend seatwave): 315000 requests passed, 0 retries, 0 redispatches
longest gap between two answers: 117.7 ms, from +77.330 s to +77.447 s (0 failed requests sent inside it)
```

`315000 requests passed, 0 retries, 0 redispatches`: even the retry mechanism had nothing to do. The longest gap between two answers was 117.7 ms, with no failed request inside it.

The claim "a rolling deploy costs zero failed requests" is therefore measured twice: the harness counts `failed 0` from the outside, and `lb1` counts `0 retries` from the inside.

### Lab 3: kill the balancer that holds the floating address

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

- The script first finds which balancer owns the address (`keepalived-2`, so it kills `lb2`) and at +12 s kills it.
- `sent 119999, answered 111684, failed 8315 (6.929%)`.
- `failures: ConnectException 2, HttpConnectTimeoutException: HTTP connect timed out 8283, IOException: HTTP/1.1 header parser received no bytes 30`. Almost all failures are connect timeouts: while nobody owned the address, a connection attempt got no reply and waited for its one-second timeout.
- `latency ... max 1014.3 ms`: a request that began during the gap and connected after the takeover, one second later.
- `share by balancer: lb1 68.4%, lb2 31.6%`: `lb2` answered until it was killed.
- `+11.785 s lb2 *: ANSWERING -> UNREACHABLE`: the watch of `lb2` noticed that its runtime API no longer answered. The star stands for "the balancer itself".
- `longest gap ... 2777.1 ms, from +11.773 s to +14.550 s (8313 failed requests sent inside it)`. At 3,000 requests per second, 2.777 seconds is 8,331 requests, and 8,313 failed. The two numbers agree: during the gap, every request failed.

The second `run pair` line and `floating address after lb2 came back: keepalived-1` show that the address stayed on `lb1` when `lb2` returned. That is the effect of `nopreempt` in the keepalived files: a returning balancer does not take the address back, so the service has one move for one failure, not two.

## Try it yourself

Run these in the repository folder. They need no Docker. They use a small stand-in written in Python that plays three SeatWave instances behind one balancer, plus a balancer runtime API, on the loopback address. It kills `seatwave-2` at a known moment, so the report shows a removal you can check by hand. Timings differ on every run; counts such as `failed 150` follow from the plan and repeat.

**1. Save the stand-in.** Make the folder and save this as `build/try/toy.py` (`build/` is ignored by git). It needs `python3`, which comes with macOS developer tools and most Linux systems.

```bash
mkdir -p build/try
```

```python
# A toy world on loopback: one fake "balancer" web port and one fake runtime API port.
import json, socketserver, sys, threading, time, uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HTTP_PORT, API_PORT = int(sys.argv[1]), int(sys.argv[2])
KILL_AFTER, SEEN_AFTER, WARMUP = 3.0, 4.5, 1.0     # seconds into the measured part
state = {"t0": None, "n": 0}
lock = threading.Lock()
HEADER = "# pxname,svname,scur,stot,wretr,wredis,status,\n"

def clock():
    return None if state["t0"] is None else time.monotonic() - state["t0"]

class Web(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *a): pass
    def reply(self, code, body, served):
        data = body.encode()
        self.send_response(code)
        if served: self.send_header("X-Served-By", served)
        self.send_header("X-Balancer", "lb1")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers(); self.wfile.write(data)
    def pick(self):
        with lock:
            if state["t0"] is None: state["t0"] = time.monotonic() + WARMUP
            state["n"] += 1; n = state["n"]
        t = clock()
        killed = t is not None and KILL_AFTER <= t
        seen = t is not None and SEEN_AFTER <= t
        names = ["seatwave-1", "seatwave-2", "seatwave-3"]
        if seen: names = ["seatwave-1", "seatwave-3"]
        name = names[n % len(names)]
        if killed and not seen and name == "seatwave-2": return None
        return name
    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", 0)))
        name = self.pick()
        if name is None: return self.reply(503, "no server", "")
        body = {"holdId": str(uuid.uuid4()), "eventId": 1, "seat": "A-7", "state": "held", "expiresAt": "2026-11-11T09:05:00Z"}
        self.reply(201, json.dumps(body), name)
    def do_GET(self):
        name = self.pick()
        if name is None: return self.reply(503, "no server", "")
        body = {"holdId": self.path.rsplit("/", 1)[-1], "eventId": 1, "seat": "A-7", "state": "held", "expiresAt": "2026-11-11T09:05:00Z"}
        self.reply(200, json.dumps(body), name)

class Api(socketserver.StreamRequestHandler):
    def handle(self):
        self.rfile.readline()
        t = clock()
        two = "DOWN" if (t is not None and t >= SEEN_AFTER) else "UP"
        self.wfile.write((HEADER + f"seatwave,BACKEND,0,{state['n']},0,0,UP,\n"
            "seatwave,seatwave-1,0,0,0,0,UP,\n"
            f"seatwave,seatwave-2,0,0,0,0,{two},\n"
            "seatwave,seatwave-3,0,0,0,0,UP,\n").encode())

class ApiServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

threading.Thread(target=ApiServer(("127.0.0.1", API_PORT), Api).serve_forever, daemon=True).start()
ThreadingHTTPServer(("127.0.0.1", HTTP_PORT), Web).serve_forever()
```

The stand-in plays this world: from 3.0 seconds into the measured part, requests that would go to `seatwave-2` get a 503; from 4.5 seconds, the fake balancer reports `seatwave-2` as `DOWN` and requests go only to the other two. Its clock starts at its first request, a little after the harness's time zero, so the times in the report differ from 3.0 and 4.5 by a fraction of a second.

**2. Save the settings.** Save this as `build/try/toy.properties`. It is a harness settings file like `config/kill-production.properties`, with a lower rate and a loopback target.

```properties
run.label=toy-kill
target.url=http://127.0.0.1:18080
load.ratePerSecond=300
load.warmupMillis=1000
load.durationMillis=8000
load.holdPercent=50
load.maxInFlight=500
load.connectTimeoutMillis=1000
load.requestTimeoutMillis=5000
load.seed=1
watch.balancers=lb1=127.0.0.1:19999
watch.backend=seatwave
watch.intervalMillis=100
report.victim=seatwave-2
report.timelineMillis=2000
```

**3. Run the world and the harness.** Start the stand-in in the background, run the harness, stop the stand-in.

```bash
python3 build/try/toy.py 18080 19999 & PID=$!
sleep 1
./gradlew -q load --args='build/try/toy.properties'
kill $PID
```

```text
run toy-kill: http://127.0.0.1:18080, 300 requests/s for 8.0 s after a 1.0 s warm-up, 50% holds; client timeouts: connect 1000 ms, request 5000 ms
sent 2399, answered 2249, failed 150 (6.253%), not sent 0
statuses: 200 1124, 201 1125, 503 150
failures: HTTP 503 150
latency of answered requests: p50 2.2 ms, p99 21.3 ms, max 32.6 ms
share by instance: seatwave-1 42.4% (953), seatwave-2 15.2% (342), seatwave-3 42.4% (954)
share by balancer: lb1 100.0% (2249)
balancer status changes (backend seatwave):
  +4.936 s lb1 seatwave-2: UP -> DOWN
balancer lb1 during the run (backend seatwave): 2700 requests passed, 0 retries, 0 redispatches
removal of seatwave-2:
  last answer from seatwave-2: +3.421 s
  taken out by lb1 at +4.936 s: 1514.5 ms after its last answer
  failed requests sent in between: 150; after: 0; Little's Law without retries: 300 requests/s x 0.333 share x 1.515 s = 151
longest gap between two answers: 27.3 ms, from +5.067 s to +5.094 s (0 failed requests sent inside it)
timeline:
   from s    sent answered  failed    p99 ms  seatwave-1  seatwave-2  seatwave-3
      0.0     600      600       0      15.5         200         200         200
      2.0     600      542      58      21.3         200         142         200
      4.0     600      508      92      23.7         253           0         255
      6.0     599      599       0      23.7         300           0         299
```

Read it with the sections above. `failed 150`: the plan is 300 requests per second, a third to the victim, for 1.5 seconds (3.0 to 4.5): 300 x 0.333 x 1.5 = 150, and the report predicted 151. `sent 2399` is the one-request rounding effect for 8 seconds at 300 per second (2,400 planned). `seatwave-2 15.2%` answered before it died. The status change is the single `UP -> DOWN` of the fake balancer. The removal analysis says how long after its last answer the balancer took it out.

**4. Slow the balancer down and see the failures double.** Make a copy of the stand-in in which the balancer notices the death after 3.0 seconds instead of 1.5, and run the harness again against it, on other ports.

```bash
sed 's/= 3.0, 4.5, 1.0/= 3.0, 6.0, 1.0/' build/try/toy.py > build/try/toy_slow.py
sed -e 's/18080/18082/' -e 's/19999/19997/' build/try/toy.properties > build/try/toy_slow.properties
python3 build/try/toy_slow.py 18082 19997 & PID=$!
sleep 1
./gradlew -q load --args='build/try/toy_slow.properties' 2>&1 | sed -n '1,2p;9,16p'
kill $PID
```

```text
run toy-kill: http://127.0.0.1:18082, 300 requests/s for 8.0 s after a 1.0 s warm-up, 50% holds; client timeouts: connect 1000 ms, request 5000 ms
sent 2399, answered 2099, failed 300 (12.505%), not sent 0
  +6.408 s lb1 seatwave-2: UP -> DOWN
balancer lb1 during the run (backend seatwave): 2700 requests passed, 0 retries, 0 redispatches
removal of seatwave-2:
  last answer from seatwave-2: +3.313 s
  taken out by lb1 at +6.408 s: 3094.9 ms after its last answer
  failed requests sent in between: 300; after: 0; Little's Law without retries: 300 requests/s x 0.333 share x 3.095 s = 309
longest gap between two answers: 25.3 ms, from +1.249 s to +1.274 s (0 failed requests sent inside it)
timeline:
```

The detection time doubled to about 3 seconds and the failed requests doubled to 300. That is the rule of thumb of section 4 at work: failures grow in step with the time the balancer needs to notice. This is the whole point of the module's comparison between active-only checks (about 12 seconds) and the passive check (about 13 ms).

**5. Check the README's arithmetic.** These are the checks I made.

```bash
python3 -c "print(11777/89999*100, 3000*0.3333*11.8242, 3000*2.7771)"
```

```text
13.085700952232804 11823.01758 8331.3
```

The first is the failure percentage of the active-checks kill (13.086), the second is the forecast (11,823 with the share rounded to 0.3333; the report prints 11,824 from the unrounded share), the third is how many requests were due in the 2.777-second failover gap (about 8,331, against 8,313 that failed).

## Mistakes people make

- **Testing with a closed loop.** The tester slows down when the server does, so the problem looks smaller than it is. Send at a rate.
- **Counting the warm-up.** The first seconds are slow for reasons that have nothing to do with the thing you want to measure.
- **Counting the answer 409 as an error.** A conflict is the service saying no correctly.
- **Reading a share as a quality.** Source hashing gave one instance 100 percent in Lab 1 because there was one client address.
- **Measuring detection from the kill command, not from the last answer.** The harness takes the last answer from the victim as the start, because the kill command and the real end of the process can differ.
- **Expecting a deploy to be free by default.** It is free here because of the sequence: drain, wait for zero sessions, stop, wait for "seen down", start, ready, wait for up, settle. Skip a step and a number appears in `failed`.
- **Trusting one run.** Repeat before you believe a digit.
- **Forgetting the guard.** Without `maxInFlight`, a dead target would make the load generator pile up requests until it ran out of memory.

## Check yourself

1. Why is the harness open-loop? *Answer: A closed-loop tester slows down when the server stalls, and the stall hides. An open loop keeps the rate, so the stall shows as latency and failures.*
2. A server dies behind a balancer that retries nothing and checks every 2 seconds, three failures to mark it down. Give the rule of thumb for the number of failed requests. *Answer: Request rate times the dead server's share times the time until the balancer takes it out.*
3. Why did the production backend lose 0 requests in the kill and the active-only backend 11,777? *Answer: Production has a passive check that marks the server down after three failed connections, in about 13 ms, and retries that move a failed request to another server. The active-only backend waits for three failed checks, about 12 seconds.*
4. Why did the rolling deploy lose 0 requests? *Answer: The tool drained each instance first, waited for zero sessions, and only then stopped it, so no request was ever sent to an instance on its way out.*
5. What is in the failover gap and why are the failures there connect timeouts? *Answer: The time with no owner of the floating address; packets get no reply, so a connection attempt waits for its timeout.*

## Further reading

- HAProxy 3.4 Configuration Manual, `option httpchk`, `inter`, `fall`, `rise`, `slowstart`, `observe`, `error-limit`, `on-error`, `retries`, `retry-on`, `option redispatch`: https://docs.haproxy.org/3.4/configuration.html
- HAProxy 3.4 Management Guide, `show stat` and `set server`: https://docs.haproxy.org/3.4/management.html
- RFC 5798, Virtual Router Redundancy Protocol (VRRP) Version 3: https://www.rfc-editor.org/rfc/rfc5798
- keepalived configuration synopsis, `keepalived.conf(5)`: https://keepalived.readthedocs.io/en/latest/configuration_synopsis.html
- Java SE 25 API, `java.net.http.HttpClient`: https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpClient.html
