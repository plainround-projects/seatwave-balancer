# Measuring a load balancer, from zero

## What it is

To **measure** a load balancer is to answer questions with numbers instead of feelings. When one of three servers dies, how many requests fail? How long until the balancer stops sending requests to the dead server? Can you replace every server one after another without a single failed request? If the balancer itself dies, how long are the users without service? How much processor time does the balancer spend per request?

Each question needs a way to produce the numbers and a way to read them. This note explains both, from zero, for the tools of this repository: the `harness` package (it sends load and writes a report), the `haproxy` package (it reads the balancer's own table), the `deploy` package (it replaces the servers), and `scripts/lab.sh` (it runs each lab and does the disruption). The numbers quoted here come from the captured runs of version 1.1.0 on the Docker stack, the ones the README prints. I did not run Docker; the stack-only tools are explained from that captured output. Where I could, I ran the same harness code against a small stand-in on my own computer, and I say so.

## Why this repository uses it

Module 10 is built on a claim you can check: spreading the load over several servers behind a redundant pair of balancers survives a failure without hurting users, if the balancer is set up well. A claim without a number is an opinion. The module gives each claim a number, and it makes the numbers repeatable, so that you can change one setting and see the number move.

Five numbers carry the module:

| Question | Number | Where it comes from |
|---|---|---|
| A server is killed. How many requests fail? | Failed requests | `failed 7653` (active checks only) and `failed 0` (production) in the captured runs |
| How long until the balancer stops using it? | Detection time | `10871.8 ms` and `52.7 ms` |
| Can all servers be replaced with no failure? | Failed requests during a deploy | `failed 0` over 300,000 requests, behind both backends |
| The balancer dies. How long is the service gone? | The failover gap | `3073.9 ms` |
| What does a request cost the balancer? | CPU per 1,000 requests | `69.7 ms per 1,000 requests` for `lb1` |

## Where it appears

| File | What it does for measuring |
|---|---|
| `harness/LoadRun.java` | Sends requests at a fixed rate and records each outcome. |
| `harness/BalancerWatch.java` | Reads the balancer's table ten times a second and notes status changes. |
| `harness/RunReport.java` | Computes failures, the mix, shares, latency, detection time and the longest gap. |
| `deploy/RollingDeploy.java` | Replaces the servers one by one and logs each step with a time. |
| `config/*.properties` | One settings file per lab (rate, duration, victim). |
| `scripts/lab.sh` | Runs each lab, does the disruption (kill, stop, deploy) at a fixed time inside a run, and measures CPU (`scripts/lab.sh cpu`). |
| `haproxy/haproxy.cfg` | The balancer settings the labs compare. |
| `keepalived/lb1.conf`, `keepalived/lb2.conf` | The floating address that moves when a balancer dies. |
| `README.md` | The captured output of every lab. |

## How it works underneath

### 1. What to measure

For a service behind a balancer, four kinds of numbers matter.

- **Correctness of the outcome**: did the request get an answer a user can use? Here an answer below status 500 counts. Status 409 ("the seat is taken") is the service working. A 502 or 503, a timeout or a closed connection counts as a failure.
- **Time**: how long a request took, and how long a repair took.
- **Share**: how the requests were divided among the servers.
- **Cost**: how much processor time the work took. Section 7 explains how `scripts/lab.sh cpu` measures it.

### 2. Open loop and closed loop

A **load** is a stream of requests sent on purpose. There are two ways to send it.

In a **closed loop**, a fixed number of clients each send a request, wait for the answer, and send the next one. If the server slows down, the clients slow down too, because they wait. The load the server sees falls exactly when the server is in trouble. This hides a stall: the requests that would have been delayed are never sent. The effect has a name, **coordinated omission**.

In an **open loop**, requests are started at planned moments, 3,000 per second, one every 333,333.3 nanoseconds on average, whatever the answers do. If the server stalls, requests pile up, and the stall shows as latency and as failures. This is what a crowd of fans does: they do not wait for each other. The harness is open-loop (`LoadRun`). It computes each planned moment from the request's number, so a run of 30 seconds at 3,000 per second sends exactly 90,000 measured requests (version 1.0.0 lost one to a rounding error and printed `sent 89999`).

An open loop needs a guard. If the server never answers, the number of waiting requests grows without limit. So the harness has `load.maxInFlight` (4,000 in the lab files). A request that is due when 4,000 are waiting is counted as "not sent" and shown in the report. In every captured run, `not sent` is 0, so the guard never fired.

The harness also **times a request from the moment it was due**, not from the moment it was sent. If the harness itself was late, the lateness counts as latency, and it cannot hide.

### 3. Warm-up

A Java program is slow in its first seconds: its code is still being compiled to fast machine code, and no connection is open yet. So every lab sends 5 seconds of the same load first (`load.warmupMillis=5000`) and leaves them out. Time zero is the end of the warm-up. In the report, `+10 s` means 10 seconds after time zero.

### 4. Failed requests during a kill

A **kill** here is `docker compose kill -s KILL seatwave-2`: the process is ended at once, with no chance to say goodbye. For the balancer, the server simply stops answering. The balancer does not know yet. It keeps sending its share of requests, and each of those requests fails, until the balancer decides the server is dead.

That gives a rule of thumb:

> failed requests = request rate x the dead server's share x the time until the balancer notices

The module calls this "Little's Law without retries" (the report prints it). It is really arrival rate times time. The report prints the forecast next to the real number, so you can see whether they agree, and it multiplies the factors exactly as printed. In the active-checks run: 3,000 x 0.3333 x 10.872 s = 10,871. The real count of failures sent in between was 7,653: 3,218 fewer than the forecast.

The gap is the word "without retries". The balancer's backend `seatwave_active_only` retries nothing, but the fans' client does. The harness uses the JDK's HTTP client, which retries once, as a fan's app would: a request of any method whose connection was refused, and a `GET` (never a `POST`) whose reused connection closed before any byte of the answer arrived (the author checked this in the JDK 25.0.2 source of `MultiExchange`). Round robin sends the second attempt to the next instance, which is alive, so many reads that met the dead instance were answered after all. The balancer's own counter shows the second attempts: `lb1` passed 109,379 requests during the run, 4,379 more than the 105,000 the harness sent (30 seconds plus the 5-second warm-up at 3,000 per second). In version 1.0.0 the kill labs sent almost no reads, because the seats were all taken and every request became a `POST`, so the forecast fitted to 0.4 percent; now half the requests are reads and the client's retries show.

Two settings of the balancer change the answer, and the module compares them. Both backends are in `haproxy.cfg`:

```text
backend seatwave
  balance roundrobin
  option httpchk
  http-check send meth GET uri /ready
  http-check expect status 200
  # Two more attempts, each on another instance (redispatch on every retry).
  retries 2
  option redispatch 1
  # Retried on three events. conn-failure: no connection was made, so SeatWave saw nothing. 503:
  # SeatWave's own "not ready, nothing was done", or the shared database down, where nothing was
  # done either. empty-response: the connection closed before any byte of an answer, after the
  # request was sent, so the instance MAY have done the work. That last case is safe for a hold
  # only because it carries an Idempotency-Key: the retry on another instance finds the hold the
  # first one committed and replays it, and a hold the first one did not commit is made once. A
  # GET is safe to repeat in every case.
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
  server seatwave-2 10.210.10.12:8080
  server seatwave-3 10.210.10.13:8080
```

- The backend `seatwave_active_only` has only the **active check**: every 2 seconds (`inter 2s`) HAProxy asks the server `GET /ready`; three failures in a row (`fall 3`) mark it `DOWN`; two successes (`rise 2`) mark it `UP`. There are no retries (`retries 0`, `retry-on none`).
- The backend `seatwave` (production) adds a **passive check** and **retries**. `observe layer4 error-limit 3 on-error mark-down` means: watch the real traffic; if three connections in a row to the server fail, mark it down at once, without waiting for the active check. `retries 2` with `retry-on conn-failure empty-response 503` means: if a request could not connect, got no answer, or got a 503 from the server, send it again, to another server (`option redispatch 1`). `slowstart 10s` means: when a server comes back, raise its share step by step over 10 seconds.

The comment above `retry-on` names the three events and says which one needs care. A request that never connected, or got SeatWave's own 503, did no work, so it is safe to repeat. A connection that closed after the request was sent may have done the work. That is safe for a hold only because it carries an `Idempotency-Key`: the retry on another instance finds the hold the first one committed and replays it, so no seat is held twice. A `GET` is safe to repeat in every case.

### 5. Detection time

**Detection time** is the time from the moment the server stopped answering to the moment the balancer took it out. The harness measures it from two clocks. The load records when the victim's last answer arrived. The watch records when the balancer's table changed the victim to `DOWN`. The report subtracts. The table is read every 100 ms, and a reading is stamped when the answer arrived, so the removal time can be late by up to one interval plus the time of one reading, never early.

The active-checks arithmetic is in the comment on `timeout check` in `haproxy.cfg`:

```text
  # Above one TCP retransmission (1 s), as Module 9 set its proxy's connect timeout: one lost SYN
  # is not a failure.
  timeout connect 2s
  timeout client 30s
  # The whole request head must arrive within 5 s of the request's start, so a client that sends
  # its headers a byte at a time (a slow-headers attack) holds a connection 5 s at most; without
  # it, every byte would restart the 30 s client timeout. A fan's request head is one packet.
  timeout http-request 5s
  # Above SeatWave's slowest honest answer (a hold, a few milliseconds) with a wide margin.
  timeout server 10s
  timeout http-keep-alive 10s
  timeout queue 2s
  # With "timeout check" set, a check's connect waits min(timeout connect, inter), here 2 s, and
  # "timeout check" is the extra wait for the answer (configuration manual, "timeout check"). So a
  # dead instance, whose address no longer answers, fails each check after the 2 s connect wait,
  # and a hung one, which accepts the connection but never answers, after 1 s more than the
  # connect took. HAProxy schedules the next check 2 s (inter) after one ends: about 4 s from one
  # failed check of a dead instance to the next, so "fall 3" takes about 12 s, which the
  # active-only kill run measures.
```

With `timeout check` set, a check's connection attempt waits `min(timeout connect, inter)`, here 2 seconds. A dead instance's address no longer answers, so each check fails after that 2-second wait, and HAProxy starts the next check 2 seconds (`inter`) after one ends. That is about 4 seconds from one failed check to the next, and `fall 3` takes about 12 seconds after the first check starts. The README adds the evidence from the HAProxy log, `Layer4 timeout, check duration: 2001ms`. The captured status changes fit: `UP -> UP 2/3` at +12.459 s, `UP 2/3 -> UP 1/3` at +16.502 s, `UP 1/3 -> DOWN` at +20.483 s, about 4 seconds apart. Measured from the last answer at +9.611 s, the detection time is 10.87 seconds.

The passive check needs no waiting: three failed connections, which at 3,000 requests per second happen within milliseconds, mark the server down. The production run measured 52.7 ms.

### 6. The pair of balancers, and the failover gap

One balancer is a single point of failure, so the module runs two (`lb1` and `lb2`) and one **floating address**, 10.210.10.100. The clients use only that address. At any moment one balancer owns it. A program called **keepalived** runs beside each balancer and speaks **VRRP**, the Virtual Router Redundancy Protocol: the owner announces "I am alive" once a second (`advert_int 1`). If the backup hears nothing for three announcements plus a small extra (the standard, RFC 5798, gives `(256 - priority) / 256` of a second), it takes the address. In the captured run `lb1` held the address and was killed, so the backup was `lb2`, with priority 100: 3 + 156/256 = 3.609 seconds after the last announcement it heard. The last announcement came up to one second before the kill, so the takeover comes 2.609 to 3.609 seconds after the kill.

The **failover gap** is the stretch in which nobody owns the address and so nobody answers. Packets sent to it are lost; a client's connection attempt gets no reply and waits for its connect timeout. The harness measures it as the longest time between two consecutive answers (`longest gap between two answers`), and counts the requests that failed inside it.

### 7. Cost: processor time per thousand requests

A balancer that is fast but eats a whole processor is expensive. The usual number is **CPU milliseconds per thousand requests**: the processor time a container used during a run, divided by the number of requests, times a thousand. Smaller is cheaper.

`scripts/lab.sh cpu` measures it. It reads two counters at the same two moments, before and after one 20-second round-robin run at 3,000 requests per second:

- The container's CPU time. Linux keeps a **control group** (cgroup) for each container: a group of processes whose resources the kernel counts together. The file `/sys/fs/cgroup/cpu.stat` inside the container has the line `usage_usec`, the processor time all its processes used, in microseconds. The helper `cpu_usec` in `scripts/common.sh` reads it with `docker exec`.
- The requests. For `lb1`, the `stot` cell of the backend's summary row (requests passed); for `seatwave-1`, the `stot` cell of its server row (requests sent to it).

Microseconds divided by requests is microseconds per request, which is the same number as milliseconds per 1,000 requests. The span includes the warm-up, and the README notes what else is in it: `lb1`'s number also holds its own health checks and the harness's `show stat` every 100 ms, and `seatwave-1`'s holds the checks of both balancers.

Each balancer runs one thread (`nbthread 1` in `haproxy.cfg`) on one processor (`cpus: 1` in `compose.yaml`), so the balancer number is per core. Section "Lab 4" below reads the result.

### 8. What can mislead you

- **One run is one sample.** The README shows one run per lab. A second run would differ in the last digits. The relations (production is far better than active-only, a deploy fails nothing) are not in doubt; the third digit is.
- **The Docker machine is shared.** The balancers, the instances, the database and the harness share one virtual machine with 12 processors. The README says other programs were running.
- **A status other than 5xx is an answer.** A 409 means the seat was taken. That is not an error.
- **The mix must be the stated mix.** The settings say 50 percent holds. The harness decides each request's kind from its number, and the report's `mix:` line prints what really ran: `45000 holds (POST), 45000 reads (GET)` in a kill run. `scripts/lab.sh` also gives every held seat back before and after each run, so each run starts with all 20,000 seats free. In version 1.0.0 the seats of earlier runs were still held, every hold got 409, and the kill runs sent only `POST`s.
- **A forecast "without retries" next to a client that retries.** The fans' client retries reads, so a kill can cost fewer failures than the forecast (section 4).
- **Averages hide.** The report prints p50, p99 and the maximum.

## Walkthrough of this repository's use

Each block below is the captured output of one lab of version 1.1.0, as the README prints it, with my reading. I did not run any of them: they need the Docker stack. I checked the arithmetic of each with a short script.

### Lab 1: how the load is shared

```text
share by instance: seatwave-1 33.3% (20000), seatwave-2 33.3% (20000), seatwave-3 33.3% (20000)
share by instance: seatwave-1 33.3% (19958), seatwave-2 33.6% (20134), seatwave-3 33.2% (19908)
share by instance: seatwave-1 50.0% (30000), seatwave-2 25.0% (15000), seatwave-3 25.0% (15000)
share by instance: seatwave-2 100.0% (60000)
```

Four runs of 20 seconds at 3,000 requests per second. The settings files are `share-roundrobin`, `share-leastconn`, `share-weighted` and `share-source`. Round robin gives each instance exactly a third, 20,000 requests each. Least connections gives a third each, within a percent: the instances answer equally fast, so the counts stay level. The weighted run has weights 2, 1 and 1 (see `haproxy.cfg`) and gives 50, 25 and 25 percent. Source hashing sends every request of one client address to one instance, and there is only one client address (the harness), so one instance gets 100 percent. This proves what the algorithm does when there is a single client. It does not mean source hashing is bad.

### Lab 2: kill an instance, active checks only

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
```

The first line is the lab script's: at +10 s on its own clock it runs `docker compose kill -s KILL seatwave-2`. The README explains the clocks: the script sleeps 5 s of warm-up and 10 s after starting the harness, whose own clock starts a moment later, so the report shows the kill as the victim's last answer, a little before +10 s. Then the harness's summary:

- `run kill-active: http://10.210.10.21:8082, ...`. Port 8082 is the active-only door of `lb1`.
- `sent 90000, answered 82347, failed 7653 (8.503%), not sent 0`. 7,653 of 90,000 is 8.503 percent.
- `mix: 45000 holds (POST), 45000 reads (GET), ...`: exactly half and half, with no read of a hold not made yet and no unreadable reply.
- `statuses: 200 42761, 201 12501, 409 27085, 502 2, 503 2882`. 200 is a read answered, 201 a hold created, 409 a seat already taken. The failures are 2,882 answers of 503 and 2 of 502, and
- `failures: ... IOException: HTTP/1.1 header parser received no bytes 4769`: requests whose connection closed without an answer. The README does not say why some failures are 503 and some are closed connections; I can only report both.
- `latency of answered requests: p50 0.7 ms, p99 2000.7 ms, max 2138.3 ms`: the middle request took 0.7 ms, but 1 in 100 answered requests took about 2 seconds. That fits the retries of section 4: such a read waited on the dead instance until its connection closed, then the client sent it again to a live instance, and its latency counts from the first attempt's due time. This is my reading of the numbers; the report does not mark which requests were retried.

```text
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

- `share by instance`: `seatwave-2` answered 11.7 percent (9,611 answers). It carried a third of 3,000 requests per second, about 1,000 a second, until its last answer at +9.611 s, and it answered nothing after that. `share by balancer: lb1 100.0%`: only `lb1` was used.
- `balancer status changes`: `+12.459 s UP -> UP 2/3`, `+16.502 s UP 2/3 -> UP 1/3`, `+20.483 s UP 1/3 -> DOWN`. About four seconds apart, as section 5 explains.
- `109379 requests passed, 0 retries, 0 redispatches`: the balancer retried nothing (`retries 0`), but it passed 4,379 requests more than the harness sent. Those are the client's second attempts.
- `removal of seatwave-2`: last answer at +9.611 s, taken out at +20.483 s, 10,871.8 ms later; 7,653 failed requests sent in between and 0 after. The forecast is `3000 requests/s x 0.3333 share x 10.872 s = 10871`; the client's retries saved 3,218 of them.
- `longest gap between two answers: 107.7 ms`: the service never went silent. Two thirds of the requests still got answers, so the gap between two answers is small, and 101 failed requests were sent inside it.

### Lab 2: the same kill behind production

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

The only change is port 8080, the production backend. `failed 0`, and the mix is again half holds, half reads. The status table: `+9.644 s lb1 seatwave-2: UP -> DOWN`, 52.7 ms after the last answer at +9.591 s. The forecast is `3000 requests/s x 0.3237 share x 0.053 s = 51` requests, and none failed, because the balancer moved them: `13 retries, 18 redispatches` are its own work, requests it sent again to another server. The users saw none of it. The worst latency was 25.8 ms, against 2,138.3 ms in the active-only run.

### Lab 3: a rolling deploy

```text
+8 s: ./gradlew -q deploy --args=config/deploy.properties --run --yes
plan: restart [seatwave-1, seatwave-2, seatwave-3] of project seatwave-balancer one at a time, on balancers [lb1, lb2], backends [seatwave, seatwave_active_only]
  each: drain, wait for 0 sessions, stop (SIGTERM), wait until seen down, start, ready, wait until up, settle 10000 ms
```

The deploy tool prints its plan, then each step with the elapsed time. Read the first instance:

```text
+     35.7 ms  seatwave-1: drain set on 2 balancer(s)
+     48.6 ms  seatwave-1: no session left on any balancer
+    290.4 ms  seatwave-1: stopped (SIGTERM; the instance drained itself)
+  12076.8 ms  seatwave-1: seen down by every balancer's checks
+  12802.0 ms  seatwave-1: started
+  12808.8 ms  seatwave-1: ready set; the checks decide when it gets traffic again
+  17095.0 ms  seatwave-1: up on every balancer; slow start ramps its weight
```

- `+ 35.7 ms drain set on 2 balancer(s)`: the `DRAIN` command was sent to `lb1` and `lb2`.
- `+ 48.6 ms no session left on any balancer`: 12.9 ms later, no request was running on `seatwave-1`.
- `+ 290.4 ms stopped (SIGTERM; the instance drained itself)`: `docker compose stop` took 241.8 ms.
- `+ 12076.8 ms seen down by every balancer's checks`: 11.8 seconds after the stop. This is the active check again: three failed checks, about 4 seconds apart. The instance is drained, so no request goes there while the balancer learns that it is gone.
- `+ 12802.0 ms started`, `+ 12808.8 ms ready set`, `+ 17095.0 ms up on every balancer`: the start took 0.7 s, and the checks needed 4.3 seconds to see two good results and the plain `UP` the deploy waits for.
- The next instance starts at +27101.2 ms: 10 seconds after `up`, which is the settle time (`deploy.settleMillis=10000`) and the length of HAProxy's slow start.

The other two instances follow the same pattern:

```text
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
```

The last step came at 67,910.3 ms, and `deploy finished: 3 instances restarted, all up` is the tool's last line.

```text
run deploy-production: http://10.210.10.21:8080, 3000 requests/s for 100.0 s after a 5.0 s warm-up, 50% holds; client timeouts: connect 1000 ms, request 5000 ms
sent 300000, answered 300000, failed 0 (0.000%), not sent 0
mix: 150000 holds (POST), 150000 reads (GET), of which 0 of a hold not made yet; 0 hold replies the harness could not read
statuses: 200 150000, 201 12850, 409 137150
failures: none
latency of answered requests: p50 0.8 ms, p99 38.9 ms, max 179.7 ms
share by instance: seatwave-1 32.8% (98351), seatwave-2 33.4% (100315), seatwave-3 33.8% (101334)
share by balancer: lb1 100.0% (300000)
```

The harness ran at the same time (100 seconds at 3,000 per second). `sent 300000, answered 300000, failed 0`. The mix is 150,000 holds and 150,000 reads. Statuses 200 (reads), 201 (holds created) and 409 (seat taken). The shares are 32.8, 33.4 and 33.8 percent: every instance served about a third even though each was out of rotation for roughly 20 seconds, because the share is counted over the whole run.

```text
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
```

These are the status changes seen from `lb1` for the three instances. For `seatwave-1`: `UP -> DRAIN` at +8.193 s (the deploy's drain), `DRAIN -> DRAIN 2/3` and `DRAIN 2/3 -> DRAIN 1/3` as the checks failed after the stop, `DRAIN 1/3 -> DOWN` at +19.005 s, then after the start `DOWN -> DOWN 1/2` and `DOWN 1/2 -> UP` at +24.976 s. The same sequence follows for the other two. Note that no line says `UP -> DOWN`: a server was never taken out by failure, it was drained first. That is why nothing failed.

```text
balancer lb1 during the run (backend seatwave): 314278 requests passed, 0 retries, 0 redispatches
longest gap between two answers: 120.0 ms, from +77.525 s to +77.645 s (0 failed requests sent inside it)
```

`0 retries, 0 redispatches`: even the retry mechanism had nothing to do. `lb1` counted 314,278 requests, a little fewer than the 315,000 the harness sent with the warm-up. My reading, not something I observed: each watch's first reading of `show stat` comes a moment after the first requests left. The longest gap between two answers was 120.0 ms, with no failed request inside it.

The claim "a rolling deploy costs zero failed requests" is therefore measured twice: the harness counts `failed 0` from the outside, and `lb1` counts `0 retries` from the inside.

### Lab 3: the same deploy behind active checks only

```text
run deploy-active: http://10.210.10.21:8082, 3000 requests/s for 100.0 s after a 5.0 s warm-up, 50% holds; client timeouts: connect 1000 ms, request 5000 ms
sent 300000, answered 300000, failed 0 (0.000%), not sent 0
mix: 150000 holds (POST), 150000 reads (GET), of which 0 of a hold not made yet; 0 hold replies the harness could not read
statuses: 200 150000, 201 12507, 409 137493
failures: none
latency of answered requests: p50 0.8 ms, p99 6.8 ms, max 158.1 ms
```

The same deploy with the load sent to port 8082, the backend without passive checks and without retries. It also lost 0 of 300,000 requests, and the worst latency was 158.1 ms. This is a change from version 1.0.0, whose run of this lab failed 7 of 299,999 requests and had seconds with a p99 up to 1,468 ms; in this run no second of the timeline has a p99 above 1 second. The run shows that the deploy's sequence alone (drain, wait for zero sessions, stop, wait until seen down) protects the requests; the production backend's retries are a second safety net, not the reason.

### Lab 3: kill the balancer that holds the floating address

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
floating address after lb1 came back: keepalived-2
```

- The script first finds which balancer owns the address (`keepalived-1`, so it kills `lb1`) and at +12 s kills it. After the kill the address is on `keepalived-2`.
- `sent 120000, answered 110785, failed 9215 (7.679%)`.
- `failures: ConnectException 18, HttpConnectTimeoutException: HTTP connect timed out 9174, IOException: HTTP/1.1 header parser received no bytes 23`. Almost all failures are connect timeouts: while nobody owned the address, a connection attempt got no reply and waited for its one-second timeout.
- `latency ... max 72.7 ms`: no answered request waited long. A request that met the gap did not get through at all.
- `share by balancer: lb1 31.4%, lb2 68.6%`: `lb1` answered until it was killed, `lb2` after the takeover.
- `+11.655 s lb1 *: ANSWERING -> UNREACHABLE`: the watch of `lb1` noticed that its runtime API no longer answered. The star stands for "the balancer itself".
- `longest gap ... 3073.9 ms, from +11.594 s to +14.668 s (9214 failed requests sent inside it)`. At 3,000 requests per second, 3.0739 seconds is 9,222 requests, and 9,214 failed. The two numbers agree: during the gap, every request failed. The gap lies between the 2.609 and 3.609 seconds that section 6 computes, plus the time the clients needed to reconnect.

`floating address after lb1 came back: keepalived-2` shows that the address stayed on `lb2` when `lb1` returned. That is the effect of `nopreempt` in the keepalived files: a returning balancer does not take the address back, so the service has one move for one failure, not two.

### Lab 4: what a request costs

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

- The plan line says what the lab does: one 20-second round-robin run, with the CPU of `lb1` and `seatwave-1` read before and after; nothing is disrupted.
- The harness's lines: `sent 60000, answered 60000, failed 0`, and `lb1` passed 75,000 requests (20 seconds plus the 5-second warm-up at 3,000 per second).
- `cpu: lb1 used 5226.5 ms of CPU for 75000 requests passed: 69.7 ms per 1,000 requests`. 5,226.5 ms divided by 75 thousands of requests is 69.7 ms per 1,000.
- `cpu: seatwave-1 used 8818.8 ms of CPU for 25000 requests: 352.8 ms per 1,000 requests`. One instance got a third of the 75,000 requests, and each request costs it about five times what it costs the balancer: the instance reads JSON, talks to the database and writes JSON, while the balancer reads the request's first lines and passes the rest on.

What it means for sizing: at 69.7 ms of one core per 1,000 requests, one core of this balancer carries about 1,000 / 69.7 x 1,000 = 14,347 requests per second. Module 1's peak was 13,333 holds and 20,000 seat maps per second, 33,333 in all, which would need about 2.3 such cores. These numbers come from one run on a shared laptop and include the balancer's own checks; they are a scale, not a promise.

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

The stand-in plays this world: from 3.0 seconds into the measured part, requests that would go to `seatwave-2` get a 503; from 4.5 seconds, the fake balancer reports `seatwave-2` as `DOWN` and requests go only to the other two. Its clock starts at its first request, a little after the harness's time zero, so the times in the report differ from 3.0 and 4.5 by a fraction of a second. The hold ids it returns are random UUIDs, the form the harness accepts.

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
sent 2400, answered 2250, failed 150 (6.250%), not sent 0
mix: 1200 holds (POST), 1200 reads (GET), of which 0 of a hold not made yet; 0 hold replies the harness could not read
statuses: 200 1124, 201 1126, 503 150
failures: HTTP 503 150
latency of answered requests: p50 1.2 ms, p99 3.6 ms, max 9.9 ms
share by instance: seatwave-1 42.8% (963), seatwave-2 14.4% (325), seatwave-3 42.8% (962)
share by balancer: lb1 100.0% (2250)
balancer status changes (backend seatwave):
  +4.757 s lb1 seatwave-2: UP -> DOWN
balancer lb1 during the run (backend seatwave): 2700 requests passed, 0 retries, 0 redispatches
removal of seatwave-2:
  last answer from seatwave-2: +3.241 s
  taken out by lb1 at +4.757 s: 1515.5 ms after its last answer
  failed requests sent in between: 150; after: 0; Little's Law without retries: 300 requests/s x 0.3340 share x 1.515 s = 152
longest gap between two answers: 12.2 ms, from +6.401 s to +6.413 s (0 failed requests sent inside it)
timeline:
   from s    sent answered  failed    p99 ms  seatwave-1  seatwave-2  seatwave-3
      0.0     600      600       0       4.2         200         200         200
      2.0     600      525      75       3.4         200         125         200
      4.0     600      525      75       2.0         263           0         262
      6.0     600      600       0       3.5         300           0         300
```

Read it with the sections above. `failed 150`: the plan is 300 requests per second, a third to the victim, for 1.5 seconds (3.0 to 4.5): 300 x 0.333 x 1.5 = 150. The report's forecast is `300 requests/s x 0.3340 share x 1.515 s = 152`, and 300 x 0.3340 x 1.515 is 151.8, so the printed factors multiply out. `sent 2400` is exactly 8 seconds at 300 per second. The `mix:` line shows 1,200 holds and 1,200 reads. `seatwave-2 14.4%` answered before it died. The status change is the single `UP -> DOWN` of the fake balancer. The removal analysis says how long after its last answer the balancer took it out.

**4. Slow the balancer down and see the failures double.** Make a copy of the stand-in in which the balancer notices the death after 3.0 seconds instead of 1.5, and run the harness again against it, on other ports.

```bash
sed 's/= 3.0, 4.5, 1.0/= 3.0, 6.0, 1.0/' build/try/toy.py > build/try/toy_slow.py
sed -e 's/18080/18082/' -e 's/19999/19997/' build/try/toy.properties > build/try/toy_slow.properties
python3 build/try/toy_slow.py 18082 19997 & PID=$!
sleep 1
./gradlew -q load --args='build/try/toy_slow.properties' 2>&1 | sed -n '1,2p;10,17p'
kill $PID
```

```text
run toy-kill: http://127.0.0.1:18082, 300 requests/s for 8.0 s after a 1.0 s warm-up, 50% holds; client timeouts: connect 1000 ms, request 5000 ms
sent 2400, answered 2100, failed 300 (12.500%), not sent 0
  +6.328 s lb1 seatwave-2: UP -> DOWN
balancer lb1 during the run (backend seatwave): 2700 requests passed, 0 retries, 0 redispatches
removal of seatwave-2:
  last answer from seatwave-2: +3.241 s
  taken out by lb1 at +6.328 s: 3086.7 ms after its last answer
  failed requests sent in between: 300; after: 0; Little's Law without retries: 300 requests/s x 0.3340 share x 3.087 s = 309
longest gap between two answers: 8.3 ms, from +3.577 s to +3.585 s (1 failed requests sent inside it)
timeline:
```

The detection time doubled to about 3 seconds and the failed requests doubled to 300. That is the rule of thumb of section 4 at work: failures grow in step with the time the balancer needs to notice. This is the whole point of the module's comparison between active-only checks (about 11 seconds) and the passive check (about 50 ms). The stand-in's client never meets a closed connection, only 503 answers, so no retry saves anything here and the forecast fits.

**5. Check the README's arithmetic.** These are the checks I made.

```bash
python3 -c "print(7653/90000*100, 3000*0.3333*10.872, 3000*3.0739, 10871-7653, 109379-105000)"
```

```text
8.503333333333334 10870.9128 9221.7 3218 4379
```

The first is the failure percentage of the active-checks kill (8.503). The second is the forecast from the printed factors (10,871 after rounding). The third is how many requests were due in the 3.0739-second failover gap (about 9,222, against 9,214 that failed). The fourth is how many failures the client's retries saved, and the fifth how many more requests `lb1` passed than the harness sent.

## Mistakes people make

- **Testing with a closed loop.** The tester slows down when the server does, so the problem looks smaller than it is. Send at a rate.
- **Counting the warm-up.** The first seconds are slow for reasons that have nothing to do with the thing you want to measure.
- **Counting the answer 409 as an error.** A conflict is the service saying no correctly.
- **Trusting the stated mix.** A run whose holds all fail can turn into something else. Print what really ran, as the `mix:` line does, and start each run from the same state, as `scripts/lab.sh` does by giving the seats back.
- **Forgetting the client's retries.** A forecast "without retries" is an upper bound when the client retries reads. Count the balancer's requests passed against the requests sent to see them.
- **Reading a share as a quality.** Source hashing gave one instance 100 percent in Lab 1 because there was one client address.
- **Measuring detection from the kill command, not from the last answer.** The harness takes the last answer from the victim as the start, because the kill command and the real end of the process can differ.
- **Expecting a deploy to be free by default.** It is free here because of the sequence: drain, wait for zero sessions, stop, wait for "seen down", start, ready, wait for up, settle. Skip a step and a number appears in `failed`.
- **Quoting CPU without saying what is in it.** The `lb1` number includes its own checks and the harness's readings; say so next to the number.
- **Trusting one run.** Repeat before you believe a digit.
- **Forgetting the guard.** Without `maxInFlight`, a dead target would make the load generator pile up requests until it ran out of memory.

## Check yourself

1. Why is the harness open-loop? *Answer: A closed-loop tester slows down when the server stalls, and the stall hides. An open loop keeps the rate, so the stall shows as latency and failures.*
2. A server dies behind a balancer that retries nothing and checks every 2 seconds, three failures to mark it down. Give the rule of thumb for the number of failed requests. *Answer: Request rate times the dead server's share times the time until the balancer takes it out.*
3. Why did the production backend lose 0 requests in the kill and the active-only backend 7,653? *Answer: Production has a passive check that marks the server down after three failed connections, in about 50 ms, and retries that move a failed request to another server. The active-only backend waits for three failed checks, about 11 seconds.*
4. The forecast for the active-only kill was 10,871, but 7,653 failed. Where did the other 3,218 go? *Answer: The fans' client retried reads whose connection closed with no bytes, and round robin sent the second attempt to a live instance. `lb1` passed 4,379 more requests than the harness sent.*
5. Why did the rolling deploy lose 0 requests, even behind the backend without retries? *Answer: The tool drained each instance first, waited for zero sessions, and only then stopped it, so no request was ever sent to an instance on its way out.*

## Further reading

- HAProxy 3.4 Configuration Manual, `option httpchk`, `inter`, `fall`, `rise`, `slowstart`, `observe`, `error-limit`, `on-error`, `retries`, `retry-on`, `option redispatch`, `timeout check`: https://docs.haproxy.org/3.4/configuration.html
- HAProxy 3.4 Management Guide, `show stat` and `set server`: https://docs.haproxy.org/3.4/management.html
- RFC 5798, Virtual Router Redundancy Protocol (VRRP) Version 3: https://www.rfc-editor.org/rfc/rfc5798
- keepalived configuration synopsis, `keepalived.conf(5)`: https://keepalived.readthedocs.io/en/latest/configuration_synopsis.html
- Linux kernel documentation, Control Group v2, `cpu.stat`: https://docs.kernel.org/admin-guide/cgroup-v2.html
- Java SE 25 API, `java.net.http.HttpClient`: https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpClient.html
