# HAProxy: the load balancer and its configuration language

## What it is

**HAProxy** is a free, open-source program that sits between clients and servers and spreads the clients' requests over several servers. A program that does this is a **load balancer**. HAProxy is also a **reverse proxy**: the client talks to HAProxy as if it were the server, and HAProxy opens its own connection to a real server, forwards the request, and passes the answer back. (`load-balancing-concepts.md` explains the idea from zero. This note explains the program and its configuration language.)

HAProxy is one program that you configure with one text file. You do not write code for it. The file says which ports to listen on, which servers exist, how to choose between them, how to check that they are alive, what to do when one fails, and what to log.

## Why this repository uses it

Module 10 puts three identical SeatWave instances behind a balancer, then puts a second balancer beside the first so that the balancer is not a single point of failure. The module measures what a client sees when an instance dies, when an instance is stopped politely, when an instance is replaced one at a time, and when a balancer dies. The open-source build of HAProxy has everything those measurements need: active health checks (the balancer asks each instance "are you well?" on a timer), passive health checks (it watches real traffic for failures), retries, slow start, a drain state, and a runtime API to change servers while it runs. The Module 9 proxy, NGINX, has only the passive kind in its open-source build. `ARCHITECTURE.md` records that choice.

## Where it appears

| Path | What it does with HAProxy |
|---|---|
| `haproxy/haproxy.cfg` | The one configuration file, used by both balancers. Every line is explained in `haproxy-config-walkthrough.md`. |
| `compose.yaml` | Runs the official image `haproxy:3.4.7` twice (`lb1`, `lb2`), mounts the file read-only, sets `BALANCER_NAME`, and publishes six ports each. See `docker-compose.md`. |
| `keepalived/lb1.conf`, `keepalived/lb2.conf` | The check script asks the balancer's own `/healthz` page. See `keepalived-vrrp.md`. |
| `src/main/java/.../haproxy/` | A Java client for HAProxy's runtime API (`show stat`, `set server`). See `java-haproxy-client-walkthrough.md`. |
| `src/main/java/.../harness/BalancerWatch.java` | Reads the statistics every 100 ms during a run. See `java-harness-walkthrough-1.md` and `java-harness-walkthrough-2.md`. |
| `src/main/java/.../deploy/` | Drains and re-enables instances through the runtime API. See `java-deploy-walkthrough.md`. |
| `scripts/common.sh` | Reads the statistics page as CSV to count servers that are up. See `shell-scripts-walkthrough.md`. |
| `src/test/java/.../config/InfrastructureRulesTest.java` | Reads `haproxy.cfg` as text and checks the rules the module's numbers depend on. |
| `src/test/java/.../stack/StackTest.java` | Drains an instance through the runtime API and counts who answers. See `tests-stack-walkthrough.md`. |

## How it works underneath

1. **Process.** The official image starts `haproxy -W -db -f /usr/local/etc/haproxy/haproxy.cfg` (I read this from `/proc/1/cmdline` in the running container). `-f` names the configuration file. `-db` means "do not go to the background". `-W` is the **master-worker mode**: process 1 is a small master that reads the file and starts a **worker**, and the worker does the real work. The log line `Initializing new worker (8)` is the master starting it. With `nbthread 1` the worker runs a single thread.
2. **Event loop.** One thread serves thousands of connections by never waiting: it asks the operating system which connections have something to read or write, handles those, and moves on. That is why one CPU is enough for the module's 3,000 requests a second.
3. **Files.** The file is read once, when HAProxy starts. A change to the file takes effect only after a reload or a restart. (The runtime API changes some things without that, in memory only: the state of one server, for example.)
4. **Network.** Each `bind` line makes HAProxy listen on a port. A client connects to a port. HAProxy reads the HTTP request, decides which **backend** (a named group of servers) gets it, picks a server in that group, connects to the server (or reuses a connection it kept), forwards the request, reads the answer, and sends it back to the client.
5. **Checks.** Separately from client traffic, a timer in HAProxy connects to each server and asks it a question, for example "GET /ready". The answer decides whether the server is UP or DOWN.
6. **Memory.** Counters (requests, bytes, errors, retries, current sessions) live in memory. The statistics page and the runtime API read them.
7. **Logs.** Lines go to the standard output of the container here, so `docker compose logs lb1` shows them. With `option dontlog-normal` only the failed requests get a line.

### The configuration language in five minutes

The file is plain text, read top to bottom. A line that starts with `#` is a comment. Indentation does not matter to HAProxy (the file here indents each section's lines by two spaces for people).

- A **section** starts with a keyword at the left margin: `global`, `defaults`, `frontend <name>`, `backend <name>`, or `listen <name>`. Every line after it, until the next section keyword, belongs to it.
  - `global`: settings for the whole process (threads, logging, the runtime API).
  - `defaults`: settings that the sections after it inherit, until the next `defaults`.
  - `frontend`: what to listen on and where to send each request.
  - `backend`: a group of servers and how to choose among them.
  - `listen`: a frontend and a backend written together, handy for small things like a status page.
- A **keyword line** is a keyword followed by its arguments: `balance roundrobin`, `retries 2`, `bind :8080`.
- **Times** carry a unit: `us`, `ms`, `s`, `m`, `h`, `d`. A bare number means milliseconds. `2s` is two seconds.
- An **ACL** is a yes-or-no question about a request, such as "is the port the client reached 8081?". It can be named (`acl is_leastconn dst_port 8081`) or written inline in braces (`{ dst_port 8081 }`), as this file does.
- `use_backend <name> if <condition>` sends a request to a backend when the condition is true. `default_backend <name>` is the fallback.
- **Environment variables** can be read inside double quotes as `"${NAME}"`.
- `option <name>` turns a behavior on. `no option <name>` turns it off.
- **Checking a file.** `haproxy -c -f <file>` reads the file, reports problems and exits without serving anything. Exit status 0 means the file is valid. I ran it inside the running `lb1` container: `docker compose exec -T lb1 haproxy -c -f /usr/local/etc/haproxy/haproxy.cfg`. It printed nothing and exited 0; the exit status is what to look at.

### Server states and the statistics columns

A server in a backend with a health check is in one of these states.

| What `show stat` prints in the `status` column | Meaning |
|---|---|
| `UP` | Healthy and receiving traffic. |
| `UP 2/3`, `UP 1/3` | Still up, but the last checks failed. The first number counts how many more failures it may take before it is marked down (with `fall 3`: after one failure `2/3`, after two `1/3`, after three `DOWN`). |
| `DOWN` | Marked down. Gets no traffic. |
| `DOWN 1/2` | Down, but the last check passed. With `rise 2` it needs two good checks in a row; after the first it shows `1/2`, after the second it is `UP`. |
| `DRAIN` | An administrator asked it to take no new sessions. Sessions that already exist finish. |
| `MAINT` | An administrator took it out completely. |

The README's timelines show exactly these strings: `UP -> UP 2/3 -> UP 1/3 -> DOWN` when an instance is killed, and `DOWN -> DOWN 1/2 -> UP` when it comes back.

The statistics are a table: one row for each frontend, each backend and each server, with one column per number. It comes as CSV (comma-separated values) from `http://127.0.0.1:21004/stats;csv` and from the runtime API command `show stat`. The columns this repository uses, with their position in the row, as the header line printed on this machine shows:

```
# pxname svname qcur qmax scur smax slim stot bin bout dreq dresp ereq econ eresp wretr wredis status weight act ...
```

`pxname` (1) is the proxy name (the section: `seatwave`), `svname` (2) is the server name (`seatwave-1`, or `FRONTEND` or `BACKEND` for the whole section), `scur` (5) the sessions now, `stot` (8) the sessions in total, `wretr` (16) the number of retries, `wredis` (17) the number of redispatches (a retry sent to a different server), `status` (18) the state above, `weight` (19) the weight, and `act` (20) the number of active servers (on a BACKEND row).

### The runtime API

`stats socket ipv4@0.0.0.0:9999 level admin` in the file opens a TCP port where a program can type commands. A command is one line; the answer is text. In the way this repository uses it, one connection carries one command and is closed after the answer. Three commands matter here:

- `show stat` prints the table above.
- `show info` prints facts about the process: version, number of threads, limits, uptime. On this machine it printed `Version: 3.4.7-8872a26`, `Nbthread: 1`, `Maxconn: 20000`.
- `set server <backend>/<server> state ready|drain|maint` changes the administrative state of one server without a reload. It prints nothing when it works and `No such server.` when the name is wrong.

The access level matters. `level admin` lets the client change things. A lower level (`operator`, `user`) can only read or do less. Whoever can reach this port can drain or disable servers, which is why the file says "LAB ONLY" and `compose.yaml` publishes the port on `127.0.0.1` only.

## Walkthrough of this repository's use

`haproxy-config-walkthrough.md` quotes and explains all of `haproxy/haproxy.cfg`. This section shows how the pieces fit, by the section headers, and what each tool does with the running balancer.

The file has these sections, in this order. These are the section lines of the file:

```text
global
```
```text
defaults
```
```text
frontend public
```
```text
backend seatwave
```
```text
backend seatwave_active_only
```
```text
backend seatwave_leastconn
```
```text
backend seatwave_source
```
```text
backend seatwave_weighted
```
```text
listen status
```
- `global` and `defaults` set the process and the inherited behavior.
- `frontend public` is the one place clients arrive. It listens on five ports, and each port leads to a different backend. This is the trick of the module: the five backends differ in one thing each, so a lab can change one thing by changing the port.
- `backend seatwave` is **PRODUCTION**: the default backend, with active and passive checks, retries and slow start.
- `backend seatwave_active_only` is the **SIMPLE** version: the same active check, nothing else. The labs compare the two.
- `backend seatwave_leastconn`, `seatwave_source` and `seatwave_weighted` show three other ways to choose a server. Their servers **track** PRODUCTION's: they copy its health and its drain state and run no checks of their own.
- `listen status` is the balancer's own health page (`/healthz`) and statistics page (`/stats`).

### What the tools do with the running balancer

- **The scripts** read `/stats;csv` through the published status port (21004 for `lb1`, 21014 for `lb2`) and count the rows whose `status` starts with `UP`. See `shell-scripts-walkthrough.md`.
- **The harness** opens the runtime API and reads `show stat` every 100 ms during a run, so it can report the moment a server changed state and how many retries and redispatches the balancer made. See `java-harness-walkthrough-1.md` and `java-harness-walkthrough-2.md`.
- **The deploy tool** sends `set server <backend>/<server> state drain` to both balancers, watches `scur` until it is 0, and later sets the state back to `ready`. See `java-deploy-walkthrough.md`.
- **`StackTest`** drains `seatwave-3` in the `seatwave` backend of `lb1`, sends 30 holds, and asserts that only `seatwave-1` and `seatwave-2` answered. See `tests-stack-walkthrough.md`.

## Try it yourself

I ran these on 11 November 2026 against the running stack (Docker Desktop, project name `seatwave-balancer-notes`; the ports are the real ones). Timings and counts differ on every run. Start the stack with `scripts/stack.sh up --run --yes` first.

1. The version, the thread count and the limits, from the runtime API (`nc` is `netcat`, a tool that sends text to a port):

```
echo "show info" | nc 127.0.0.1 21005 | grep -E '^(Name|Version|Nbthread|Maxconn|Hard_maxconn)'
```

Output:

```
Name: HAProxy
Version: 3.4.7-8872a26
Nbthread: 1
Maxconn: 20000
Hard_maxconn: 20000
```

2. The state of the three instances in PRODUCTION's backend, and the weights of the weighted backend (from the status page's CSV):

```
echo "show stat" | nc 127.0.0.1 21005 | awk -F, '$1=="seatwave" {print $1,$2,$18,$19}'
curl -s 'http://127.0.0.1:21004/stats;csv' | awk -F, '$1=="seatwave_weighted"{print $1,$2,$18,$19}'
```

Output:

```
seatwave seatwave-1 UP 1
seatwave seatwave-2 UP 1
seatwave seatwave-3 UP 1
seatwave BACKEND UP 3
seatwave_weighted seatwave-1 UP 2
seatwave_weighted seatwave-2 UP 1
seatwave_weighted seatwave-3 UP 1
seatwave_weighted BACKEND UP 4
```

(The BACKEND row's weight is the sum of its servers' weights: 3, and 2 + 1 + 1 = 4.)

3. Drain one instance by hand, look at every backend, and put it back. Notice that the three tracking backends follow, and that `seatwave_active_only` does not track anything, so it stays `UP` (this is why the deploy tool sets the drain in both `seatwave` and `seatwave_active_only`):

```
r() { printf '%s\n' "$1" | nc 127.0.0.1 21005; }
r "set server seatwave/seatwave-3 state drain"
r "show stat" | awk -F, '$1 ~ /^seatwave/ && $2=="seatwave-3" {print $1,$2,$18,$19}'
r "set server seatwave/seatwave-3 state ready"
r "set server seatwave/nosuch state ready"
```

Output (the first command printed nothing):

```
seatwave seatwave-3 DRAIN 1
seatwave_active_only seatwave-3 UP 1
seatwave_leastconn seatwave-3 DRAIN 1
seatwave_source seatwave-3 DRAIN 1
seatwave_weighted seatwave-3 DRAIN 1
No such server.
```

4. Check the configuration file inside the container; no output and exit status 0 mean it is valid:

```
docker compose -p seatwave-balancer-notes exec -T lb1 haproxy -c -f /usr/local/etc/haproxy/haproxy.cfg; echo "exit status $?"
```

Output:

```
exit status 0
```

5. See how the process was started (the `\0` bytes separate the arguments):

```
docker compose -p seatwave-balancer-notes exec -T lb1 cat /proc/1/cmdline | tr '\0' ' '
```

Output:

```
haproxy -W -db -f /usr/local/etc/haproxy/haproxy.cfg 
```

## Mistakes people make

- **Assuming the file is read continuously.** It is read at start. Editing `haproxy.cfg` while the container runs changes nothing until the container is restarted.
- **Tracking servers that are down for the wrong reason.** A server that tracks another one follows it exactly: you cannot drain `seatwave_leastconn/seatwave-3` by hand and expect `seatwave` to follow. The repository drains the server that is tracked (`seatwave`) and the one backend that tracks nothing (`seatwave_active_only`).
- **Leaving an admin socket open.** `level admin` on a TCP port is for a closed lab network. Production would use a UNIX socket or a management network. The README says so.
- **Reading `UP 2/3` as "two thirds up".** It means a server that is still up but has failed one check; it is not a share of anything.
- **Measuring one core as if it were many.** The file sets `nbthread 1` and Compose gives one CPU, on purpose, so numbers are per core.
- **Log lines per request at high load.** At 3,000 requests a second a line per request costs more than the balancing. The file logs only failures (`option dontlog-normal`).

## Check yourself

1. What is the difference between a frontend and a backend?
   *A frontend is where clients arrive (ports, and rules that pick a backend). A backend is a group of servers and the way to choose among them.*
2. What does `UP 1/3` mean?
   *The server is still up, but checks have failed twice; one more failure marks it `DOWN`.*
3. What is the runtime API for?
   *Reading statistics and changing a server's state (ready, drain, maintenance) while HAProxy runs, without a reload.*
4. Why do `seatwave_leastconn`, `seatwave_source` and `seatwave_weighted` have no `check` of their own?
   *Their servers `track` the servers of `seatwave`, so they share its health and its drain state and differ only in how they choose.*
5. How do you check a configuration file without starting the balancer?
   *`haproxy -c -f <file>`; exit status 0 means it is valid.*

## Further reading

- HAProxy 3.4 configuration manual: https://docs.haproxy.org/3.4/configuration.html
- HAProxy 3.4 management guide (runtime API, `show stat`, `set server`): https://docs.haproxy.org/3.4/management.html
- HAProxy starter guide (3.4): https://docs.haproxy.org/3.4/intro.html
- The official Docker image of HAProxy: https://hub.docker.com/_/haproxy
