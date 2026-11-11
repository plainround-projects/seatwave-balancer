# `haproxy.cfg`, line by line

## What it is

`haproxy/haproxy.cfg` is the one configuration file of both balancers, `lb1` and `lb2`. The program that reads it is HAProxy 3.4.7. This note explains every line of the file, in order. `haproxy.md` explains what HAProxy is and how the configuration language works; read it first, because this note uses its words (frontend, backend, server, check, track, runtime API) without defining them again. `load-balancing-concepts.md` explains why a balancer does these things.

## Why this repository uses it

The module's labs measure what a client sees when an instance dies, stops, is replaced, or when a balancer dies. Each lab needs a setting to be exactly what it says, so the file has five backends that differ in one thing each, and most numbers in the file have a comment that says why. The comments are part of the lesson: they name the reason for a value.

## Where it appears

| Path | What it does |
|---|---|
| `haproxy/haproxy.cfg` | The whole file. It is mounted read-only into `lb1` and `lb2` at `/usr/local/etc/haproxy/haproxy.cfg`. |
| `compose.yaml` | Mounts it, sets `BALANCER_NAME`, publishes the ports of the five doors, the status page and the runtime API. |
| `src/test/java/.../config/InfrastructureRulesTest.java` | Reads the text of each backend and checks the rules below (checks, retries, slow start, tracking). |
| `config/*.properties` | The harness and deploy settings name the doors by container port (8080 to 8084) and the backends by name. |

## How it works underneath

Read the file from the top. HAProxy reads it once when it starts. Its sections are `global`, `defaults`, one `frontend`, five `backend` sections and one `listen`. A request arrives at one of five ports, a rule in the frontend picks a backend by the port, and the backend picks a server. The three instances are the same three servers in every backend, at `10.210.10.11`, `.12` and `.13`, port 8080 for requests and port 8081 for the health check.

The five doors:

| Port in the container | Published on the Mac | Backend | What is different |
|---|---|---|---|
| 8080 | 21000 (lb1), 21010 (lb2) | `seatwave` | PRODUCTION: round robin, active check, passive check, slow start, retries |
| 8081 | 21001, 21011 | `seatwave_leastconn` | chooses the server with the fewest open connections |
| 8082 | 21002, 21012 | `seatwave_active_only` | SIMPLE: the active check only, no retries, no slow start |
| 8083 | 21003, 21013 | `seatwave_source` | the same client always gets the same server |
| 8084 | not published | `seatwave_weighted` | round robin where `seatwave-1` counts double |

The two ports 8404 (status page) and 9999 (runtime API) are published as 21004/21014 and 21005/21015.

## Walkthrough of this repository's use

### The header

`haproxy/haproxy.cfg`, lines 1 to 3:

```text
# SeatWave's load balancer (Module 10): HAProxy 3.4.7, one file for both balancers of the pair.
# Every keyword was checked against the 3.4 configuration manual (doc/configuration.txt of the
# 3.4.7 source) on 11 November 2026. BALANCER_NAME comes from compose.yaml (lb1 or lb2).
```
These comments say that the file is for both balancers of the pair, that every keyword was checked against the 3.4 configuration manual (the file `doc/configuration.txt` of the 3.4.7 source) on 11 November 2026, and that `BALANCER_NAME` comes from `compose.yaml`: the same text runs in `lb1` and `lb2`, and only that one environment variable differs.

### `global`

`haproxy/haproxy.cfg`, lines 5 to 15:

```text
global
  # Level info, which carries the request lines; "option dontlog-normal" below keeps only the
  # failed ones, because a line per request at 3,000 requests per second would cost more than the
  # balancing.
  log stdout format raw local0 info
  # One thread on one CPU (compose.yaml), so every balancer number in the module is per core.
  nbthread 1
  maxconn 20000
  # The runtime API on the lab network. LAB ONLY: level admin lets anyone who reaches this port
  # drain or disable servers. compose.yaml publishes it on 127.0.0.1 only, for the deploy tool.
  stats socket ipv4@0.0.0.0:9999 level admin
```
- `global` opens the section of settings for the whole process.
- The comment above `log` and the `log` line itself: `log stdout format raw local0 info`. `log` sets where logs go. `stdout` is the program's standard output, which Docker collects. `format raw` writes the message as plain text, with no date-and-host prefix (Docker adds its own time). `local0` is a "facility", a label that syslog-style logging needs and that means nothing here. `info` is the most detailed level that is sent. The comment explains the next idea: level `info` is the level that carries the request lines, and `option dontlog-normal` (in `defaults`) keeps only the failed ones, because a line for every request at 3,000 requests a second would cost more than the balancing.
- `nbthread 1` runs HAProxy with one thread. The comment ties it to the one CPU that `compose.yaml` gives the container, so every balancer number in the module is a number per core.
- `maxconn 20000` is the most connections the whole process accepts at the same time. Beyond it new connections wait in the operating system's queue. The load is 3,000 requests a second with a few milliseconds each, so the number of open connections stays far below this; the ceiling is a safety net.
- `stats socket ipv4@0.0.0.0:9999 level admin` opens the runtime API: a socket (a place a program can connect to) on IPv4 address `0.0.0.0` (every address of the container), port 9999, with access level `admin`. The two comment lines above it say it plainly: this is for the lab only, because anyone who can reach the port can drain or disable servers. `compose.yaml` publishes the port on `127.0.0.1` only, for the deploy tool.

### `defaults`

`haproxy/haproxy.cfg`, lines 17 to 44:

```text
defaults
  mode http
  log global
  # One log line per failed request only (timeouts, refused connections, 5xx), with HAProxy's
  # termination state, so a kill or a drain shows how each failure ended.
  option httplog
  option dontlog-normal
  option dontlognull
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
  timeout check 1s
```
- `defaults` opens a section of settings that every frontend, backend and listen section after it inherits.
- `mode http` makes HAProxy read and understand HTTP (layer 7, see `load-balancing-concepts.md`): it can look at the method, the path and the headers, add headers, check status codes and retry a request. The other mode, `tcp`, only shuffles bytes.
- `log global` makes these sections log to the target set in `global`.
- `option httplog` selects the detailed HTTP log format, which includes timers and a "termination state" (two letters that say how the request ended). Here is a real line from this lab, a request that failed because its instance was killed and the backend had no retries:

```
10.210.10.1:57672 [11/Nov/2026:07:27:15.713] public seatwave_active_only/seatwave-3 0/0/-1/-1/0 503 0 - - SC-- 1/1/0/0/0 0/0 "POST /v1/holds HTTP/1.1"
10.210.10.1:57702 [11/Nov/2026:07:27:15.748] public seatwave_active_only/seatwave-3 0/0/-1/-1/2002 503 0 - - sC-- 1/1/0/0/0 0/0 "POST /v1/holds HTTP/1.1"
```

  Read the first line from the left. `10.210.10.1:57672` is the client (the Mac, seen from inside the network, port 57672). The date in brackets is when the request was accepted. `public` is the frontend. `seatwave_active_only/seatwave-3` is the backend and the server it chose. The five numbers `0/0/-1/-1/0` are times in milliseconds: time to receive the request, time waiting in a queue, time to connect to the server (`-1` means it never did), time for the response (`-1`, none), and the total time (0 here, 2002 in the second line). Then the status `503` that HAProxy itself answered, the bytes `0`, two dashes for cookies, and the termination state: `SC--`. The first letter says who ended it (`S`: the server side refused or aborted; a lower-case `s` in the second line: a server-side timeout). The second letter says in which phase (`C`: while connecting). The first line failed at once because the address was refused. The second waited the whole 2 seconds of `timeout connect` (2002 ms). Then `1/1/0/0/0` are the connection counts and the number of retries (0 here), `0/0` are the queue lengths, and the last part is the request line.
- `option dontlog-normal` suppresses the line for a request that went well. Only failures and errors are logged. The comment above says why.
- `option dontlognull` suppresses the line for a connection that sent no data at all (a port scan, or a check).
- `timeout connect 2s` is how long HAProxy waits to establish a connection to a server. The comment says why 2 seconds: it is above one TCP retransmission (1 s). When a connection request (a SYN packet) is lost, the operating system tries again after 1 second, so one lost packet must not count as a failure. This is also the figure that decides how long a **dead** server takes to be detected: see `timeout check` below.
- `timeout client 30s` closes a client connection that has been silent for 30 seconds.
- The three comment lines above `timeout http-request 5s` explain it. The **request head** is the first part of an HTTP request: the request line (`POST /v1/holds HTTP/1.1`) and the headers, up to the empty line that ends them. `timeout http-request 5s` says the whole head must arrive within 5 seconds of the request's start. Without it, only `timeout client` would apply, and that one restarts every time a byte arrives. A client that sends its headers one byte every few seconds (a **slow-headers attack**, a way to tie up a server's connections with very little traffic of one's own) could then hold a connection open for as long as it likes. With the line, such a client holds a connection 5 seconds at most. A real fan's request head is small and arrives in one packet, so the limit never touches honest traffic. The same 5 seconds also bound the wait for the next request on a kept-alive connection, but `timeout http-keep-alive 10s` below is the setting for that case, and HAProxy uses `http-keep-alive` there when both are set.
- `timeout server 10s` gives a server 10 seconds of silence before HAProxy gives up on its answer. The comment says the longest honest SeatWave answer is a few milliseconds, so 10 s is a wide margin.
- `timeout http-keep-alive 10s` is how long HAProxy keeps a connection open, waiting for the client's next request on it. It applies only between requests on a connection that is being reused.
- `timeout queue 2s` is how long a request may wait for a free server slot. No server here has a connection limit, so nothing ever waits in a queue; the line only sets a bound.
- The seven comment lines above `timeout check 1s` and the line itself say exactly how long a health check may take. With `timeout check` set, HAProxy limits the connect part of a check to the smaller of `timeout connect` and `inter` (the check interval, set to 2 s further down), so here min(2 s, 2 s) = 2 s, and `timeout check` (1 s) is the extra wait for the answer once the connection is made. The configuration manual says this under its entry "timeout check". Two cases follow from it:
  - A **dead** instance: its address no longer answers at all, so the connection is never made. Each check fails after the 2 s connect wait. In this lab's log a check on the killed instance said `Layer4 timeout, check duration: 2000ms` (Layer 4 is the connection level).
  - A **hung** instance: it accepts the connection but never answers. Each check fails 1 s after the connection was made.
  - HAProxy schedules the next check 2 s (`inter`) after one ends. So the failed checks of a dead instance come about 4 s apart (2 s of waiting plus 2 s of interval), and `fall 3` (three failures in a row, set below) takes about 12 s. The active-only kill run measures that; the end of the PRODUCTION section below gives the numbers.
  - Version 1.0.0's comment said a check slower than 1 s fails "within the same budget as a dead one". That was true only for the answer part, because the connect part waits 2 s. The new comment states both parts.

### `frontend public`

`haproxy/haproxy.cfg`, lines 46 to 60:

```text
frontend public
  # One frontend, five doors: each port reaches a backend that differs in one thing only.
  bind :8080
  bind :8081
  bind :8082
  bind :8083
  bind :8084
  maxconn 20000
  # Which balancer passed the response on: the pair lab counts answers by it.
  http-response set-header X-Balancer "${BALANCER_NAME}"
  use_backend seatwave_leastconn if { dst_port 8081 }
  use_backend seatwave_active_only if { dst_port 8082 }
  use_backend seatwave_source if { dst_port 8083 }
  use_backend seatwave_weighted if { dst_port 8084 }
  default_backend seatwave
```
- `frontend public` opens the frontend called `public`. It is where all clients arrive.
- The first comment says the idea: one frontend, five doors, where each port reaches a backend that differs in one thing only.
- `bind :8080` to `bind :8084` make HAProxy listen on five ports. `:8080` with no address before the colon means every IPv4 address of the container. Compose publishes four of the five (8084 is reachable only inside the lab network).
- `maxconn 20000` limits the connections this frontend accepts.
- The comment above `http-response` and the line itself: `http-response set-header X-Balancer "${BALANCER_NAME}"` adds a header named `X-Balancer` to every response that comes back from a server. Its value is the environment variable `BALANCER_NAME` (`lb1` or `lb2`), read when the file is loaded. A client can then see which balancer passed the answer on (`x-balancer: lb1` in the README's captured output), and the pair lab counts answers by it.
- `use_backend seatwave_leastconn if { dst_port 8081 }` is a routing rule. `{ dst_port 8081 }` is an inline ACL: `dst_port` is the port the client connected to on this side, so the condition is true for connections that arrived at 8081. The rule sends them to `seatwave_leastconn`. The next three lines do the same for 8082, 8083 and 8084. HAProxy tests `use_backend` rules from the top, and the first that matches wins.
- `default_backend seatwave` handles everything else: the connections that arrived at 8080 (PRODUCTION).

### `backend seatwave`: PRODUCTION

`haproxy/haproxy.cfg`, lines 62 to 84:

```text
# PRODUCTION. Round robin; an active check of /ready every 2 s, down after 3 failures, up after
# 2 successes; a passive check that marks an instance down after 3 failed connections in a row;
# slow start over 10 s; and retries that move to another instance when a request was not served.
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
- The three comment lines state the design: round robin; an active check of `/ready` every 2 s, down after 3 failures, up after 2 successes; a passive check that marks an instance down after 3 failed connections in a row; slow start over 10 s; and retries that move to another instance when a request was not served.
- `backend seatwave` opens the backend called `seatwave`.
- `balance roundrobin` picks the servers in turn: first, second, third, first again. If the servers have weights it follows them.
- `option httpchk` makes the health check an HTTP request instead of a plain connection. `http-check send meth GET uri /ready` sets what to send (the method `GET`, the path `/ready`). `http-check expect status 200` sets the answer that counts as healthy: status 200. Any other status, or no answer, is a failure. `/ready` is a different endpoint from `/health` on purpose: a draining instance is alive but not ready, and the balancer must stop sending it work (see `java-seatwave-walkthrough-1.md`, `java-seatwave-walkthrough-2.md` and `java-seatwave-walkthrough-3.md`).
- `retries 2` allows two more tries after the first. A request that cannot be served by the first server is tried again, at most twice more.
- `option redispatch 1` says where the retry goes. The comment above says "each on another instance (redispatch on every retry)". The number 1 means "on every retry"; without redispatch a retry would go to the same server.
- The seven comment lines above `retry-on` explain the rule, one event at a time. `retry-on conn-failure empty-response 503` lists the events that allow a retry:
  - `conn-failure`: the connection to the server could not be made. SeatWave saw nothing, so nothing was done.
  - `503`: the server answered with status 503. A SeatWave instance answers 503 in two cases: its own "not ready, nothing was done" while it starts or drains, and the case where the shared database is down. Nothing was done in either case.
  - `empty-response`: the connection closed before any byte of an answer came back, **after** the request was sent. This is the one case where the instance **may** have done the work: it may have committed a hold and died before it could answer. The comment says why a retry is still safe. A hold carries an `Idempotency-Key` (a unique name the client gives the request, see `load-balancing-concepts.md`). The retry goes to another instance, which looks the key up in the shared database: if the first instance committed the hold, the second finds it and replays the same answer; if the first did not commit it, the hold is made once, now. Either way a seat is never held twice. A `GET` only reads, so repeating it is safe in every case. Other retry events, such as a response timeout, are left out on purpose, and `InfrastructureRulesTest` checks that `response-timeout` and `junk-response` are not in the line.
- `default-server check port 8081 inter 2s fall 3 rise 2 slowstart 10s observe layer4 error-limit 3 on-error mark-down` sets defaults for the `server` lines that follow, one setting at a time:
  - `check` turns on the active health check for each server.
  - `port 8081` makes the check go to port 8081 of the server, not the port 8080 that carries requests. The instance serves `/health`, `/ready` and `/metrics` on its own port, with its own thread, so a flood of requests cannot starve the check (the comment in `config/seatwave-container.properties` says the `/health`, `/ready` and `/metrics` endpoints run on their own thread).
  - `inter 2s` waits 2 seconds between one check and the next.
  - `fall 3` means 3 failed checks in a row mark the server down.
  - `rise 2` means 2 good checks in a row mark it up again.
  - `slowstart 10s` ramps a server's share of the traffic from almost nothing to full over 10 seconds, starting when it comes up. A cold instance (its pool and caches empty) is not hit with a full third of the load at once.
  - `observe layer4` turns on the **passive** check: HAProxy watches real traffic for connection-level errors.
  - `error-limit 3` and `on-error mark-down` say what to do about them: after 3 consecutive errors on real traffic, mark the server down at once, without waiting for the active checks.
- The three `server` lines each give a name, an address and a port: `server seatwave-1 10.210.10.11:8080` is a server called `seatwave-1` at that address. The addresses are the fixed ones in `compose.yaml`. The defaults above apply to all three.

How long does it take to notice a dead instance? The README's active-only run shows what the numbers mean in practice: the status went `UP`, then `UP 2/3`, `UP 1/3`, `DOWN`, at +12.5 s, +16.5 s and +20.5 s of the run, about 4 seconds apart. Each failed check waited 2 s for the connection (the connect wait of `timeout check`, explained above) and the next began 2 s later (`inter`). So `fall 3` takes about 12 s from the first check after the kill, and the re-measured run of version 1.1.0 found 10.87 s from the last answer to the removal. The kill fell between two checks: the instance's last answer was at +9.6 s, the first failed check had started about 0.85 s after that and ended 2 s later at +12.5 s, and two more 4 s steps brought `DOWN` at +20.5 s. The passive check in this backend cut the same case to 52.7 ms in that run, because real requests failed fast and three failures came in quickly.

### `backend seatwave_active_only`: SIMPLE

`haproxy/haproxy.cfg`, lines 86 to 99:

```text
# SIMPLE checks: the same active check, no passive check, no retries, no slow start. This is the
# backend whose losses Little's Law predicts: every request sent to a dead instance before the
# check notices it fails.
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
- The comment says what this backend is for: the same active check, no passive check, no retries, no slow start. It is the backend whose losses Little's Law predicts: every request sent to a dead instance before the check notices fails. (Little's Law says that the average number in a system equals the arrival rate times the average time in the system. Here: 3,000 requests a second, times one third of them going to the dead instance, times 10.872 seconds before it is removed, is 10,871 requests, as the report of the re-measured run prints. The same run counted 7,653 failed requests. The difference, 3,218, is the reads that the harness's own client tried once more on a new connection, as a fan's app would: the JDK client repeats a `GET` whose reused connection closed before any byte of the answer, never a `POST` (`java-harness-walkthrough-1.md` explains the harness).)
- `balance roundrobin`, `option httpchk`, `http-check send ...` and `http-check expect ...` are the same as in PRODUCTION.
- `retries 0` and `retry-on none` switch retries off. A request that cannot reach its server gets HAProxy's own 503 answer.
- `default-server check port 8081 inter 2s fall 3 rise 2` has `check`, the port and the three numbers, but none of `slowstart`, `observe`, `error-limit` or `on-error`. `InfrastructureRulesTest` checks that this block contains none of `redispatch`, `observe` and `slowstart`.
- The three `server` lines are the same three servers.

### The three algorithm backends

`haproxy/haproxy.cfg`, lines 101 to 131:

```text
# The algorithms of Chapter 10.2. Their servers track PRODUCTION's, so they share its checks and
# its drain state, and differ only in how they choose.
backend seatwave_leastconn
  balance leastconn
  retries 2
  option redispatch 1
  retry-on conn-failure empty-response 503
  server seatwave-1 10.210.10.11:8080 track seatwave/seatwave-1
  server seatwave-2 10.210.10.12:8080 track seatwave/seatwave-2
  server seatwave-3 10.210.10.13:8080 track seatwave/seatwave-3

# Hashing on the client's address: every request of one client goes to one instance.
backend seatwave_source
  balance source
  hash-type consistent
  retries 2
  option redispatch 1
  retry-on conn-failure empty-response 503
  server seatwave-1 10.210.10.11:8080 track seatwave/seatwave-1
  server seatwave-2 10.210.10.12:8080 track seatwave/seatwave-2
  server seatwave-3 10.210.10.13:8080 track seatwave/seatwave-3

# Weighted round robin: seatwave-1 stands for a machine twice the size of the others.
backend seatwave_weighted
  balance roundrobin
  retries 2
  option redispatch 1
  retry-on conn-failure empty-response 503
  server seatwave-1 10.210.10.11:8080 weight 2 track seatwave/seatwave-1
  server seatwave-2 10.210.10.12:8080 weight 1 track seatwave/seatwave-2
  server seatwave-3 10.210.10.13:8080 weight 1 track seatwave/seatwave-3
```
- The comment above `seatwave_leastconn` says why these backends have no `check`: their servers **track** PRODUCTION's. `track seatwave/seatwave-1` at the end of a `server` line makes this server share the health state of the server `seatwave-1` of the backend `seatwave`, and its administrative state too (`DRAIN`, `MAINT`). So there is one set of checks, and a drain set on `seatwave/seatwave-1` takes effect in all four backends at once (I drained one in the running lab and saw `DRAIN` in `seatwave`, `seatwave_leastconn`, `seatwave_source` and `seatwave_weighted`, and `UP` in `seatwave_active_only`, which tracks nothing). `InfrastructureRulesTest` checks that the three backends contain the three `track` words and no ` check`.
- `balance leastconn` sends each request to the server with the fewest open connections. It suits requests of very different lengths. When several servers have the same count, the choice among them is not a strict rotation, so a short run of single requests is not evenly spread (I sent 9 one-at-a-time requests and got 1, 6 and 2).
- `retries 2`, `option redispatch 1` and the `retry-on` line are the same as in PRODUCTION, so a request still survives a dead instance in these backends, but without the passive check and slow start.
- `balance source` hashes the client's address and uses the hash to pick a server, so one client always meets the same server while the set of servers does not change. `hash-type consistent` chooses **consistent hashing**: the hash is placed on a ring of server positions, so when one server leaves, only the clients that were on it move, not most of them. From the Mac, every request arrives from the same address (the gateway `10.210.10.1`), so all of them went to one instance (9 of 9 to `seatwave-3` in my run).
- `balance roundrobin` with `weight 2` on `seatwave-1` and `weight 1` on the others gives `seatwave-1` double the turns: the comment says it stands for a machine twice the size of the others. The README's share lines show 50.0% for `seatwave-1` and 25.0% for each of the others.

### `listen status`

`haproxy/haproxy.cfg`, lines 133 to 140:

```text
# The balancer's own health and its read-only statistics page. keepalived asks /healthz to decide
# whether this balancer may hold the floating address.
listen status
  bind :8404
  monitor-uri /healthz
  stats enable
  stats uri /stats
  stats refresh 2s
```
- The comment says what the section is for: the balancer's own health, and its read-only statistics page. keepalived asks `/healthz` to decide whether this balancer may hold the floating address (see `keepalived-vrrp.md`).
- `listen status` is a frontend and a backend in one, named `status`. It has no servers: it answers by itself.
- `bind :8404` listens on 8404 (published as 21004 and 21014).
- `monitor-uri /healthz` makes HAProxy answer `200 OK` itself, without asking any server, to a request for `/healthz`. If HAProxy is not running, nothing answers, and that silence is what keepalived reads as "this balancer is not well". The body is `<html><body><h1>200 OK</h1>` then `Service ready.`, which busybox `wget` printed inside the keepalived container.
- `stats enable` turns on the statistics page, `stats uri /stats` puts it at `/stats`, and `stats refresh 2s` asks a browser to reload it every 2 seconds. The page is read-only: there is no `stats admin` line. The same address with `;csv` added (`/stats;csv`) gives the table as CSV, which the scripts read.

## Try it yourself

I ran these on 11 November 2026 against the running stack of version 1.0.0 (the old run; project name `seatwave-balancer-notes`). Version 1.1.0 changed this file's comments and added `timeout http-request 5s`, which no request of these steps comes near, so the steps behave the same; the numbers quoted from the re-measured run of version 1.1.0 say so. Counts and times differ on every run. Start the stack with `scripts/stack.sh up --run --yes`.

1. Send nine holds to each of three doors and count who answered. Port 21000 is PRODUCTION (round robin), 21001 least connections, 21003 source hashing:

```
for p in 21000 21001 21003; do echo "port $p"; for i in 1 2 3 4 5 6 7 8 9; do curl -s -D - -o /dev/null -H 'Content-Type: application/json' -H "Idempotency-Key: \"n-$p-$i\"" --data "{\"eventId\":1,\"seat\":\"T-$p$i\",\"customer\":\"n\"}" http://127.0.0.1:$p/v1/holds | grep -i -E '^(x-served-by|x-balancer)' | tr -d '\r' | tr '\n' ' '; echo; done | sort | uniq -c; done
```

Output:

```
port 21000
   3 x-served-by: seatwave-1 x-balancer: lb1 
   3 x-served-by: seatwave-2 x-balancer: lb1 
   3 x-served-by: seatwave-3 x-balancer: lb1 
port 21001
   1 x-served-by: seatwave-1 x-balancer: lb1 
   6 x-served-by: seatwave-2 x-balancer: lb1 
   2 x-served-by: seatwave-3 x-balancer: lb1 
port 21003
   9 x-served-by: seatwave-3 x-balancer: lb1 
```

  Round robin gives exactly three each. Least connections is uneven for one-at-a-time requests. Source hashing sends all nine to one instance. If you run the loop twice, the second run's holds say `409` (the seats are taken) but the headers are the same.

2. The balancer's own health page (what keepalived asks), and the first lines of the statistics page:

```
curl -s -D - -o /dev/null http://127.0.0.1:21004/healthz | head -3
curl -s -D - -o /dev/null http://127.0.0.1:21004/stats | head -5
```

Output:

```
HTTP/1.1 200 OK
content-length: 58
cache-control: no-cache
HTTP/1.1 200 OK
cache-control: no-cache
content-type: text/html
refresh: 2
transfer-encoding: chunked
```

3. Watch a dead instance being found, with the active check alone. In one terminal send one request after another to the active-only door (21002) and count the statuses:

```
for i in $(seq 1 600); do curl -s -o /dev/null -w '%{http_code}
' --max-time 3 -H 'Content-Type: application/json' -H "Idempotency-Key: "k-$i-$RANDOM"" --data "{"eventId":1,"seat":"K-$((RANDOM%1000+1))","customer":"k"}" http://127.0.0.1:21002/v1/holds; done | sort | uniq -c
```

  About 3 seconds after it starts, in a second terminal, kill an instance and print the status of `seatwave-3` in that backend once a second. Then start it again:

```
docker compose -p seatwave-balancer-notes kill -s KILL seatwave-3
for t in $(seq 1 16); do date +%S; curl -s 'http://127.0.0.1:21004/stats;csv' | awk -F, '$1=="seatwave_active_only" && $2=="seatwave-3" {print $18}'; sleep 1; done
docker compose -p seatwave-balancer-notes start seatwave-3
```

  My run printed, one status per second: `UP` until 3 seconds after the kill, `UP 2/3` for the next 5 seconds, `UP 1/3` for the next 4, and `DOWN` from 13 seconds after the kill. (The kill was at +3 s on my clock, and `DOWN` first showed at +16 s.) The traffic loop, which I cut after 14 seconds, had counted 437 requests: 341 with status 201, 89 with 409 and 7 with 503. The 503s are HAProxy's own answers to requests sent to the dead instance before it was removed. After `start`, the status goes `DOWN 1/2` and then `UP`; in my runs it was `UP` again when I looked 25 seconds later.

4. The same kill against PRODUCTION: run the loop above with port 21000 and the backend name `seatwave` in the `awk`. My run printed `UP` until 2 seconds after the kill, `UP 2/3` once, and `DOWN` from 4 seconds after the kill. All 600 requests got 201 (318) or 409 (282): no failure. The passive check and the retries absorbed the kill. (The re-measured run of version 1.1.0 at 3,000 requests a second shows 52.7 ms instead of 4 s, because there the failures arrive fast enough to reach the error limit at once.) Start the instance again afterwards.

5. Look at the bytes of a failed request in the log, for the active-only door, right after step 3 (the log line is the one explained under `defaults`):

```
docker compose -p seatwave-balancer-notes logs --no-log-prefix lb1 | grep 'POST /v1/holds' | head -2
```

## Mistakes people make

- **Putting `timeout check` where `timeout connect` decides.** Reading `timeout check 1s` as "a dead server is found in 1 s". The connect part of a check follows `timeout connect` (2 s here), so one failed check on a vanished server takes 2 s.
- **Retrying what may have been done.** A retry of a request that changed something is dangerous. The file retries only on `conn-failure`, `empty-response` and `503`, and relies on the `Idempotency-Key` for the middle case. Adding `response-timeout` would retry requests that were slow but done; `InfrastructureRulesTest` forbids it.
- **A health check on `/health`.** `/health` says "alive"; `/ready` says "ready for work". A draining instance is alive and not ready. The balancer must read `/ready`.
- **Checking the traffic port.** The check goes to port 8081, away from request traffic, so load cannot starve it.
- **Forgetting that tracking servers have no checks.** Drain `seatwave_leastconn/seatwave-1` by hand and nothing changes in `seatwave`. Drain the tracked one.
- **Slow start forgotten.** Without `slowstart` a restarted instance would get its full third of traffic the moment it is up. `seatwave_active_only` has none on purpose; the labs show the difference.

## Check yourself

1. Which backend answers a request that arrives at port 8082?
   *`seatwave_active_only`, by the rule `use_backend seatwave_active_only if { dst_port 8082 }`.*
2. What does `retry-on conn-failure empty-response 503` allow, and why is it safe for a hold?
   *A retry when the connection failed, when the server closed without answering, or when it answered 503. SeatWave did no work in the first and third cases, and the `Idempotency-Key` makes a repeat harmless in the second.*
3. Why is the first failure of an active check on a killed instance about 2 s, not 1 s?
   *The connect part of the check is limited by `timeout connect 2s`; `timeout check 1s` only limits the answer after the connection is made.*
4. What does `track seatwave/seatwave-1` do?
   *The server shares the health and the drain state of `seatwave-1` in the backend `seatwave`, so it needs no check of its own.*
5. Why does `listen status` have no `server` line?
   *It answers by itself: `/healthz` through `monitor-uri`, and `/stats` through `stats enable`.*

## Further reading

- HAProxy 3.4 configuration manual: https://docs.haproxy.org/3.4/configuration.html (sections: `global`, `defaults`, `frontend`, `backend`, `listen`; keywords `balance`, `option httpchk`, `http-check`, `retry-on`, `option redispatch`, `server` and its `track`, `slowstart`, `observe`, `error-limit` and `on-error`; `monitor-uri`; `stats`; the HTTP log format)
- HAProxy 3.4 management guide: https://docs.haproxy.org/3.4/management.html
