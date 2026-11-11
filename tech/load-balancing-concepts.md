# Load balancing: the ideas, tied to this repository's files

## What it is

A **load balancer** is a program or machine that receives requests from clients and hands each one to one of several identical servers. It does two jobs. It **spreads the work**, so no single server is overloaded. And it **hides failures**, so when one server dies, clients are sent to the others. A server behind a balancer is also called an **instance**, a **backend server** or an **upstream**.

This note explains the ideas from zero, then shows where each one appears in this repository. `haproxy.md` explains the program, `haproxy-config-walkthrough.md` explains every line of its file, and `keepalived-vrrp.md` explains how the balancer itself is made redundant.

## Why this repository uses it

Module 10 is about spreading the load. SeatWave started as one process. A single process has a limit on how many requests it can serve, and when it dies, the service dies. The module runs three copies behind a balancer, then a second balancer so that the balancer is not a single point of failure. Then it measures what clients see when something fails. The ideas below are the vocabulary of those measurements.

## Where it appears

| Idea | Where in the repository |
|---|---|
| Layer 7 balancing | `mode http` in `haproxy/haproxy.cfg` |
| Round robin, least connections, source hashing, weights | The five backends of `haproxy.cfg` and the share lab (`scripts/lab.sh share`) |
| Active health check | `option httpchk`, `http-check`, `default-server check ... inter 2s fall 3 rise 2` |
| Passive health check | `observe layer4 error-limit 3 on-error mark-down` in `backend seatwave` |
| Retries and redispatch | `retries 2`, `option redispatch 1`, `retry-on ...` |
| Draining | The `DRAIN` state set through the runtime API by `deploy/`, and the instance's own drain in `Lifecycle` |
| Slow start | `slowstart 10s` in `backend seatwave` |
| Stateless instances | The shared PostgreSQL database; the `Idempotency-Key` on `POST /v1/holds` |
| The redundant pair | `lb1` and `lb2` in `compose.yaml`, `keepalived/*.conf` |
| Measuring all of it | `harness/`, `scripts/lab.sh`, `README.md` |

## How it works underneath

A request in this lab goes through these steps:

1. The client opens a TCP connection to the floating address `10.210.10.100` on port 8080 (or, from the Mac, to a published port such as 21000 on `127.0.0.1`). Whichever balancer holds the address receives it.
2. HAProxy reads the HTTP request and applies the rule for the port. Port 8080 leads to `backend seatwave`.
3. The backend picks a server that is `UP` by its algorithm.
4. HAProxy connects to the server (or reuses a connection), forwards the request and waits for the answer.
5. If the connection fails, or the server answers 503 or nothing, and the retry rules allow it, HAProxy picks another server and tries again.
6. The answer goes back to the client with an added header `X-Balancer`.
7. In parallel, timers run: the health checks on each server, and the VRRP messages between the two keepalived programs.

### Layer 4 and layer 7

Network software talks about **layers**. Layer 4 is the transport layer: TCP connections, which are streams of bytes between two addresses and ports. Layer 7 is the application layer: here HTTP, with methods, paths, headers and status codes.

- A **layer 4** balancer (HAProxy `mode tcp`) sees only connections. It picks a server when the connection opens and then copies bytes both ways. It is fast and simple. It cannot look at a path, add a header or retry one request, because it does not know where one request ends.
- A **layer 7** balancer (HAProxy `mode http`) reads each request. It can route by port or path, add headers, retry a request that failed, check that a server answers with the right status, and count requests rather than connections.

This repository uses layer 7 (`mode http` in the `defaults` section) because the module needs request-level behavior: the `X-Balancer` header, the `/ready` check with an expected status, and retries of single requests.

### Algorithms: how the next server is chosen

| Algorithm | Rule | Good for | In this repository |
|---|---|---|---|
| Round robin | Each server in turn | Requests of similar cost | `balance roundrobin` in `seatwave` (PRODUCTION) and `seatwave_active_only` |
| Weighted round robin | Each server in turn, but a server with weight 2 gets two turns for each turn of weight 1 | Servers of different sizes | `seatwave_weighted`: `seatwave-1` has `weight 2` |
| Least connections | The server with the fewest open connections now | Requests of very different durations | `balance leastconn` in `seatwave_leastconn` |
| Source hash | A hash of the client's address picks the server, so one client always meets the same server | Needing "stickiness" without cookies | `balance source` with `hash-type consistent` in `seatwave_source` |

The share lab measures them with 3,000 requests a second for 20 seconds each. The README's lines show: round robin 33.3% for each instance; least connections 33.5%, 33.3% and 33.2%; weighted 50.0%, 25.0% and 25.0%; source hashing 100% on one instance, because the harness is one client.

Two more things the algorithms do not say. First, **stickiness** (always the same server for one client) is only needed when a server keeps something in memory about the client. SeatWave's instances keep nothing: every hold is in the shared database, so any instance can answer for any hold, and a retried `POST /v1/holds` with the same `Idempotency-Key` returns the same hold from any instance. That is called a **stateless** instance, and it is what makes round robin safe. Second, **consistent hashing** places servers and keys on a ring so that, when one server leaves, only the clients that were on it move.

### Health checks: knowing which servers are alive

A balancer must stop sending requests to a dead server. It learns that in two ways.

- An **active** check is a question the balancer asks on a timer, with no client involved: "GET /ready, is the answer 200?". It finds a dead server even when no traffic flows. It is as slow as its timer. In this repository: `inter 2s fall 3 rise 2`, which means ask every 2 s, three failures in a row mark the server down, two successes in a row mark it up again.
- A **passive** check watches real traffic. When real requests fail to connect, the balancer counts the failures and, after enough of them, marks the server down at once. It needs traffic to work, and it reacts at the speed of the traffic. In this repository: `observe layer4 error-limit 3 on-error mark-down`.

Why both? The active check works without traffic and can bring a server back up. The passive check is fast when traffic is heavy. PRODUCTION has both and SIMPLE has the active check only; the labs compare them.

**Detection time** is the time from the instance's death to the moment the balancer stops sending it requests. It decides how many requests fail. For the active check alone it is roughly `fall × (time one failed check takes + inter)`. In this lab a failed check on a dead address takes 2 s (the connect timeout) and the next waits 2 s (`inter`), so each failure costs about 4 s, and three of them cost about 12 s. The README measured 11.82 s. Compare 13.4 ms with the passive check in PRODUCTION.

**Little's Law** gives the cost in failed requests. The average number of things in a system equals the rate they arrive times the time each stays. Here: requests a second, times the share that went to the dead instance, times the detection time. 3,000 × 0.333 × 11.824 s is 11,824, and the README counted 11,777 failed requests. A shorter detection time is the only way to cut this number, apart from retrying.

### Retries: hiding a failure from the client

A **retry** sends a request again, to another server, when the first attempt failed. It turns a failure into a delay. It is safe only when the first attempt did no work. A request that never connected is safe. An answer of 503 from SeatWave means "not ready, nothing was done" in this code, so it is safe. A connection that dies before any answer byte may have done the work. For such a case the request must be **idempotent**: doing it twice has the same effect as doing it once. In HTTP, `GET` is idempotent and `POST` is not. SeatWave makes its `POST /v1/holds` safe to repeat by asking the client for an `Idempotency-Key`: a second request with the same key returns the first hold. The balancer's `retry-on` line (see `haproxy-config-walkthrough.md`) was written with exactly this argument.

### Draining and graceful shutdown

When you want to take a server out for a deploy, killing it fails the requests it is serving. A **drain** avoids that in two steps. First tell the balancer to send the server no new requests (`DRAIN` state). Then wait until the requests it is already serving finish (`scur`, the current sessions, reaches 0). Then stop it. SeatWave adds a second layer: when it receives SIGTERM it stops admitting new requests (it answers 503 with `Connection: close`) and exits only when the running ones are done or a deadline of 20 s passes. The rolling deploy of this repository uses both, one instance at a time, and the README's deploy run shows 299,999 requests sent and 0 failed.

### Slow start: not hurting a cold server

A server that has just started is "cold": its caches are empty, its connections are not open, and the JIT compiler of the Java runtime has not yet optimized the hot code. Giving it a full share of the traffic at once can make it slow, and slow requests pile up. **Slow start** raises its share from almost nothing to full over a time. In this repository `slowstart 10s` is in PRODUCTION; `seatwave_active_only` has none. The deploy tool also waits a settle time (`deploy.settleMillis=10000` in `config/deploy.properties`) after an instance is up before it moves to the next.

### The balancer as a single point of failure

If everything goes through one balancer, its death is the service's death. A **redundant pair** has two balancers and one address that moves between them (see `keepalived-vrrp.md`). Only one holds the address at a time (**active-passive**). When the holder dies, the other takes the address after about three missed advertisements: in the README, 2.78 s of failed connections. The failure shows up as `HttpConnectTimeoutException` in the harness, because while no one holds the address, nobody answers the connection.

A pair does not remove every single point of failure. The two balancers here share one Docker host, one network and one database. The README names these limits under "What is simplified".

## Walkthrough of this repository's use

The lines that carry each idea, in `haproxy/haproxy.cfg`. The balance lines:

`haproxy/haproxy.cfg`, line 57:

```text
  balance roundrobin
```
`haproxy/haproxy.cfg`, lines 90 to 91:

```text
backend seatwave_leastconn
  balance leastconn
```
`haproxy/haproxy.cfg`, lines 100 to 102:

```text
backend seatwave_source
  balance source
  hash-type consistent
```
`haproxy/haproxy.cfg`, lines 111 to 112:

```text
backend seatwave_weighted
  balance roundrobin
```
`haproxy/haproxy.cfg`, line 116:

```text
  server seatwave-1 10.210.10.11:8080 weight 2 track seatwave/seatwave-1
```
Round robin in PRODUCTION; least connections; source hashing with consistent hashing; round robin with a weight of 2 on `seatwave-1`.

The active check, the passive check and slow start, in one line of defaults, and the retries:

`haproxy/haproxy.cfg`, lines 62 to 63:

```text
  retries 2
  option redispatch 1
```
`haproxy/haproxy.cfg`, lines 67 to 68:

```text
  retry-on conn-failure empty-response 503
  default-server check port 8081 inter 2s fall 3 rise 2 slowstart 10s observe layer4 error-limit 3 on-error mark-down
```
Every item in the second line, in order, is explained in `haproxy-config-walkthrough.md`. The first block gives the retries (`retries 2`) and the redispatch (`option redispatch 1`).

The SIMPLE backend turns the safety nets off, to make the comparison visible:

`haproxy/haproxy.cfg`, lines 81 to 83:

```text
  retries 0
  retry-on none
  default-server check port 8081 inter 2s fall 3 rise 2
```
The shared state that makes any instance able to serve any request is `db/schema.sql` (see `sql-schema.md` and `postgresql-and-h2.md`), and the `Idempotency-Key` header handling in the SeatWave instance (see `java-seatwave-walkthrough-1.md`, `java-seatwave-walkthrough-2.md` and `java-seatwave-walkthrough-3.md`).

### The numbers of the README, in this note's words

| Experiment | What the README measured | What it shows |
|---|---|---|
| Kill an instance behind the active check only | 11,777 of 89,999 requests failed; the instance was removed 11.82 s after its last answer | Detection time times rate times share is the loss |
| The same kill behind PRODUCTION | 0 failed; removed 13.4 ms after its last answer (6 retries, 9 redispatches) | Passive check and retries hide the kill |
| Rolling deploy of all three instances behind PRODUCTION | 0 failed of 299,999 | Drain first, stop, wait until seen down, start, slow start |
| Kill the balancer that holds the floating address | 8,315 of 119,999 failed; longest gap between two answers 2,777.1 ms | VRRP takes about three missed advertisements plus a skew |

I did not rerun these loads: they need the harness at 3,000 requests a second inside the lab network, and this note's author ran only small sequential tests (below). The numbers are the README's.

## Try it yourself

I ran these on 11 November 2026 against the running stack (see `docker.md` for the setup); the counts differ on every run.

1. The plan of each lab, without running anything (the scripts are dry runs by default):

```
scripts/lab.sh kill-production
scripts/lab.sh deploy-production
scripts/lab.sh pair
```

Output (in my copy the Compose project is named `seatwave-balancer-notes`; in the repository the lines say `seatwave-balancer`):

```
plan: lab kill-production on the Compose project seatwave-balancer-notes: docker kill -s KILL seatwave-2 at +10 s; then start it again
dry run: nothing changed. Add --run to do it.
plan: lab deploy-production on the Compose project seatwave-balancer-notes: ./gradlew -q deploy (all three instances, one at a time) at +8 s
dry run: nothing changed. Add --run to do it.
plan: lab pair on the Compose project seatwave-balancer-notes: docker kill -s KILL on the balancer holding 10.210.10.100 at +12 s; then start it and restart its keepalived
dry run: nothing changed. Add --run to do it.
```

2. Count the answers of three doors with nine holds each, to see round robin, least connections and source hashing (the loop is in `haproxy-config-walkthrough.md`, "Try it yourself", step 1). My output was: round robin 3, 3 and 3; least connections 1, 6 and 2; source hashing 9 on one instance.

3. Drain one instance in the balancer's state, send holds, and see who answers. This is what `StackTest.sendsNothingNewToADrainedInstance` does:

```
printf '%s\n' "set server seatwave/seatwave-3 state drain" | nc 127.0.0.1 21005
for i in $(seq 1 12); do curl -s -D - -o /dev/null -H 'Content-Type: application/json' -H "Idempotency-Key: \"d-$i-$RANDOM\"" --data "{\"eventId\":1,\"seat\":\"S-$((RANDOM%1000+1))\",\"customer\":\"d\"}" http://127.0.0.1:21000/v1/holds | grep -i '^x-served-by' | tr -d '\r'; done | sort | uniq -c
printf '%s\n' "set server seatwave/seatwave-3 state ready" | nc 127.0.0.1 21005
```

Output of the second command (the first and third print nothing when they work):

```
   6 x-served-by: seatwave-1
   6 x-served-by: seatwave-2
```

Never `seatwave-3`. After `ready`, `show stat` showed all three instances `UP` again.

4. Kill one instance and watch the three states of a check (`UP`, `UP 2/3`, `UP 1/3`, `DOWN`) in the active-only backend: the commands and my output are in `haproxy-config-walkthrough.md`, "Try it yourself", step 3. They show a detection time of 13 seconds with the active check alone and 4 seconds in PRODUCTION at one request after another.

## Mistakes people make

- **Reading round robin as "even load".** It is an even count of requests, not of work. Requests of different cost call for least connections.
- **Retrying anything.** A retried `POST` that changed something doubles the change. Here the `Idempotency-Key` makes it safe.
- **A health check that is too shallow.** A check that only opens the port says "the process listens". `/ready` says "I can serve", which is false while draining.
- **Killing a server before draining it.** Every request in flight on it fails. The deploy drains first.
- **Assuming a pair means no single point of failure.** Shared host, network or database are still single points.
- **Using stickiness to avoid fixing state.** `balance source` hides the problem of state kept in memory; the lab shows it sends one client everywhere or nowhere, and the instances keep nothing, so it is not needed.
- **Ignoring detection time.** Failed requests equal rate times share times detection time. A longer `inter` or a higher `fall` lengthens it.

## Check yourself

1. What is the difference between a layer 4 and a layer 7 balancer?
   *Layer 4 sees only TCP connections and copies bytes. Layer 7 reads each HTTP request, so it can route by port or path, add headers, check status codes and retry one request.*
2. Why does PRODUCTION have both an active and a passive check?
   *The active check finds failures without traffic and brings servers back up; the passive check reacts at the speed of real traffic, which cut the removal time from 11.82 s to 13.4 ms in the README.*
3. How many requests fail if a server dies, with no retries, at 3,000 requests a second, three servers, and an 11.8 s detection time?
   *About 3,000 × (1/3) × 11.8, which is about 11,800.*
4. Why is it safe to retry `POST /v1/holds`?
   *SeatWave answers 503 only when it did no work, and every hold carries an `Idempotency-Key`, so a repeat returns the first hold instead of making a second.*
5. What are the two steps of a safe removal of an instance, and who does each?
   *Drain at the balancer (the deploy tool sets `DRAIN` and waits for `scur` 0), then stop with SIGTERM (the instance's own drain admits nothing new and waits for the running requests).*

## Further reading

- HAProxy 3.4 configuration manual, section "Load balancing algorithms" (`balance`): https://docs.haproxy.org/3.4/configuration.html
- HAProxy 3.4 starter guide, "Load balancing": https://docs.haproxy.org/3.4/intro.html
- HTTP semantics, RFC 9110, section on idempotent methods: https://www.rfc-editor.org/rfc/rfc9110
- VRRP, RFC 5798: https://www.rfc-editor.org/rfc/rfc5798
- The `Idempotency-Key` HTTP header field (IETF draft): https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/
