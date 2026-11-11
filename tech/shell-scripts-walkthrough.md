# The shell scripts: `common.sh`, `stack.sh` and `lab.sh`

## What it is

A **shell** is a program that reads commands you type and runs them. A **shell script** is a text file of such commands, so you can run them again with one word. These three scripts are written for **Bash**, a widely used shell. The first line of a script that is run on its own, `#!/usr/bin/env bash`, is the **shebang**: it tells the operating system which program should run the file. `env bash` finds Bash on your computer wherever it is installed.

`posix-shell.md` explains shell basics (commands, arguments, variables, quoting, exit codes, pipes) using the Gradle wrapper script. Read it first if those words are new. This note covers what these scripts add: Bash arrays, `[[ ... ]]` tests, functions, `trap`, background jobs, a small `awk` program, and a pattern for scripts that change things: dry run by default, ask first, check before, check after, write an audit line.

## Why this repository uses it

The stack has eight containers and a harness, and the labs need exact, repeatable steps: start the stack, wait until it is ready, send load, kill one thing at a fixed time, put it back, report. Typing every Docker command by hand invites mistakes, and some of the commands kill containers. The scripts wrap each action in the same safe routine: do nothing unless told to (`--run`), ask for confirmation, refuse to touch anything but the project's own containers, write what was done to a log, and check the result.

## Where it appears

| Path | What it does |
|---|---|
| `scripts/common.sh` | Not run on its own. It is loaded (sourced) by the other two. It holds the project name, the Docker check, the audit line and read-only questions to the running stack. |
| `scripts/stack.sh` | Starts, tests, stops and lists the Compose project `seatwave-balancer`. |
| `scripts/lab.sh` | Runs one lab: the harness sends load inside the lab network while the script does one disruptive thing at a fixed time, then puts the stack back. |
| `build/stack/audit.log` | The log the scripts append to (made at run time; not in git). |
| `build/runs/<lab>.txt` | The report of each lab (made at run time). |
| `build/secrets/db.password` | The database password `stack.sh up` makes (not in git). |

## How it works underneath

1. **A command is a process.** The shell starts a program, waits for it and reads its **exit code**: 0 means success, anything else means failure. `$?` holds the last exit code.
2. **Three streams.** Every process has standard input (0), standard output (1) and standard error (2). `> file` sends output to a file, `>> file` appends, `2>&1` sends errors to the same place as output, `>&2` sends output to the error stream (used for messages that are not results), and `/dev/null` is a file that swallows everything.
3. **A pipe** (`a | b`) connects the output of `a` to the input of `b`.
4. **Variables** are set with `name=value` (no spaces) and read with `$name`. Always put the read in double quotes, `"$name"`, so a value with spaces stays one word.
5. **An array** holds a list: `names=(a b c)`. `"${names[@]}"` becomes one word per item. `${#names[@]}` is the count. `"${names[*]}"` joins the items into one word.
6. **Safety line.** `set -euo pipefail` at the top of a script turns Bash from "carry on after errors" into "stop at the first one". Each letter is explained below.
7. **These scripts talk to Docker.** `docker compose -p seatwave-balancer ...` acts on the containers of one named project; `docker compose exec` runs a command inside a running container.
8. **Sourcing.** `source file` runs the commands of `file` in the current shell, as if they were typed here. That is how `stack.sh` and `lab.sh` get the functions and variables of `common.sh`.
9. **The shape of a safe script.** Arguments are checked. A dry run (the default) only prints the plan. A question comes before any change. A check before makes sure Docker answers and the stack is ready. A check after proves the result. An audit line records every change.

## Walkthrough of this repository's use

### `scripts/common.sh`: the project, the Docker time limit and the two command arrays

`scripts/common.sh`, lines 1 to 10:

```bash
#!/usr/bin/env bash
# Shared by scripts/stack.sh and scripts/lab.sh: the project's fixed name, the Docker check, the
# audit line, and read-only questions to the running stack. Sourced, never run on its own.

readonly PROJECT="seatwave-balancer"
# How long "docker info" may take before Docker counts as not running.
readonly DOCKER_TIMEOUT_SECONDS=10
compose=(docker compose -p "$PROJECT")
# "down" and "run" name the tools profile, so the harness's containers are included.
compose_all=(docker compose -p "$PROJECT" --profile tools)
```
- Line 1 is the shebang, and lines 2 and 3 are comments: the file is shared by `stack.sh` and `lab.sh`, and holds the project's fixed name, the Docker check, the audit line and read-only questions to the running stack. "Sourced, never run on its own."
- `readonly PROJECT="seatwave-balancer"` sets a variable and forbids changing it later. Every Docker command in the scripts uses this name, so the scripts can only act on this project.
- The comment and `readonly DOCKER_TIMEOUT_SECONDS=10` set how long `docker info` may take before Docker counts as not running: 10 seconds. `docker_must_answer` below uses it twice, in its loop and in its message. (Version 1.0.0 defined this constant in `stack.sh`, where nothing used it, and wrote the number 10 into the loop by hand.)
- `compose=(docker compose -p "$PROJECT")` stores a command in an **array**: the four words `docker`, `compose`, `-p` and `seatwave-balancer`. Later `"${compose[@]}" ps` expands to `docker compose -p seatwave-balancer ps`, with each word kept whole.
- The comment and `compose_all=(docker compose -p "$PROJECT" --profile tools)` make a second array with `--profile tools` added. The comment says why: `down` and `run` name the `tools` profile, so the harness's containers are included. The harness service in `compose.yaml` is hidden from a plain `up` by that profile (see `docker-compose.md`).

### `docker_must_answer`: a bounded check of Docker

`scripts/common.sh`, lines 12 to 30:

```bash
# Check before: Docker must answer, within a bound, or nothing is done.
docker_must_answer() {
  docker info --format '{{.ServerVersion}}' > /dev/null 2>&1 &
  local checker=$!
  local waited
  for (( waited = 0; waited < DOCKER_TIMEOUT_SECONDS; waited++ )); do
    if ! kill -0 "$checker" 2> /dev/null; then
      if wait "$checker"; then
        return 0
      fi
      break
    fi
    sleep 1
  done
  kill "$checker" 2> /dev/null || true
  echo "Docker is not running (or does not answer within ${DOCKER_TIMEOUT_SECONDS} s)." >&2
  echo "Start Docker Desktop and run this again; nothing was started or stopped." >&2
  exit 1
}
```
This function stops the whole script, with a clear message, when Docker does not answer. It must not hang, because `docker info` can wait for a long time when Docker Desktop is half started.

- `docker_must_answer() { ... }` defines a **function**: a named block of commands that runs when its name is written as a command.
- `docker info --format '{{.ServerVersion}}' > /dev/null 2>&1 &` asks the Docker engine for its version. `--format` picks one field. `> /dev/null 2>&1` throws away both output streams, because only the exit code matters. The final `&` starts the command in the **background**: the shell does not wait for it and goes on with the next line.
- `local checker=$!` stores `$!`, the process number of the last background command. `local` makes the variable belong to this function only. `local waited` declares the counter.
- `for (( waited = 0; waited < DOCKER_TIMEOUT_SECONDS; waited++ )); do ... done` is a counting loop in the C style: start at 0, repeat while less than `DOCKER_TIMEOUT_SECONDS` (10), add 1 each time. It runs at most 10 times. Inside `(( ... ))` a variable is named without `$`.
- `if ! kill -0 "$checker" 2> /dev/null; then` asks whether the background job has ended. `kill -0 <number>` sends no signal; it only tests whether the process exists, and succeeds if it does. The `!` flips the result: the body runs when the process is gone. `2> /dev/null` hides the "no such process" message.
- `if wait "$checker"; then return 0; fi` collects the finished job. `wait <number>` waits for that process and succeeds if its exit code was 0. Then `return 0` leaves the function: Docker answered.
- `break` leaves the loop when the job ended with a failure. Docker answered with an error, so there is no point waiting.
- `sleep 1` waits one second before the next look.
- After the loop (a timeout or a failure): `kill "$checker" 2> /dev/null || true` stops the job if it is still running. `|| true` means "if that fails, carry on": without it `set -e` would stop the script on the failed `kill` of an already finished job.
- The two `echo ... >&2` lines print the message on the error stream. `${DOCKER_TIMEOUT_SECONDS}` puts the constant into the text, so the message always names the real limit: "Docker is not running (or does not answer within 10 s). Start Docker Desktop and run this again; nothing was started or stopped." Then `exit 1` ends the **whole script** (not only the function), with status 1.

### `audit`

`scripts/common.sh`, lines 32 to 35:

```bash
audit() {
  mkdir -p build/stack
  echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $1" >> build/stack/audit.log
}
```
- `mkdir -p build/stack` makes the folder if it is missing; `-p` means no error if it exists.
- `echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $1" >> build/stack/audit.log` appends one line: the time, then the function's first argument `$1`. `date -u` gives UTC; the format makes `2026-11-11T07:24:44Z`. `>>` appends, so earlier lines stay.

Every action that changes something calls `audit`, including `give_seats_back` below. After my `up` in a copy of version 1.0.0 whose project name was `seatwave-balancer-notes` (the old run), the log held `2026-11-11T07:24:44Z up: started seatwave-balancer-notes`.

### `stats_port`

`scripts/common.sh`, lines 37 to 44:

```bash
# The published statistics port of each balancer (compose.yaml).
stats_port() {
  case "$1" in
    lb1) echo 21004 ;;
    lb2) echo 21014 ;;
    *) echo "unknown balancer $1" >&2; return 1 ;;
  esac
}
```
- `case "$1" in ... esac` compares the first argument to patterns, like a list of `if`s. `lb1) echo 21004 ;;` means: if it is `lb1`, print 21004 and end this branch (`;;`). The two numbers are the published status ports of the two balancers (see `docker-compose.md`).
- `*) echo "unknown balancer $1" >&2; return 1 ;;` is the catch-all: a message on the error stream and a failing return code.

### `servers_up`: how many servers a balancer sees fully up

`scripts/common.sh`, lines 46 to 53:

```bash
# How many servers of a backend a balancer sees fully UP, read from its statistics page as CSV.
# Exactly "UP": "UP 1/3" is a server already failing its checks, not one that is ready.
servers_up() {
  local port
  port="$(stats_port "$1")"
  { curl -s --max-time 2 "http://127.0.0.1:${port}/stats;csv" 2> /dev/null || true; } \
    | awk -F, -v backend="$2" '$1 == backend && $2 != "BACKEND" && $18 == "UP" { up++ } END { print up + 0 }'
}
```
- The two comment lines say what the function counts: the servers of one backend that a balancer sees **fully** `UP`, read from its statistics page as CSV. "Exactly "UP": "UP 1/3" is a server already failing its checks, not one that is ready."
- `local port` declares the variable on its own line, and `port="$(stats_port "$1")"` sets it on the next. The two steps are separate on purpose: `local port="$(...)"` would hide the exit code of `stats_port`, and `set -e` would never see a failure. `$( ... )` runs the command and puts its output in the place of the expression.
- `{ curl -s --max-time 2 "http://127.0.0.1:${port}/stats;csv" 2> /dev/null || true; }` is a group of commands in braces. `curl` fetches a URL; `-s` hides the progress meter, `--max-time 2` gives up after 2 seconds, and the URL is the balancer's statistics page with `;csv` added, which returns the table as comma-separated values. If curl fails, `|| true` keeps the group successful, so the pipe goes on and the function prints `0` instead of stopping the script.
- The `\` at the end of the line continues the command on the next line, and `|` pipes the table into `awk`.
- `awk` is a small language for tables. `-F,` says fields are separated by commas. `-v backend="$2"` passes the second argument of the function into the program as a variable called `backend`. The program in single quotes is `pattern { action }`: for each row whose first field (`$1`, the proxy name) equals `backend`, whose second field (`$2`, the server name) is not `BACKEND` (the summary row), and whose 18th field (`$18`, the `status` column, see `haproxy.md`) is exactly the text `UP` (`==` compares two strings), do `up++` (add one to `up`). `END { print up + 0 }` runs after the last row and prints the count; adding 0 turns "never set" into `0`.

HAProxy writes `UP 2/3` or `UP 1/3` for a server that is still in service but has already failed one or two checks in a row: it is on its way down. Such a server does not count, and neither does a `DRAIN` or `DOWN` one. Version 1.0.0 matched every status that starts with `UP` (the pattern `$18 ~ /^UP/`), so a server that was failing its checks still counted as ready.

### `stack_ready`: both balancers see all three instances in both checking backends

`scripts/common.sh`, lines 55 to 63:

```bash
# Says whether both balancers see all three instances fully up in both backends that check them.
stack_ready() {
  local balancer backend
  for balancer in lb1 lb2; do
    for backend in seatwave seatwave_active_only; do
      [[ "$(servers_up "$balancer" "$backend")" == "3" ]] || return 1
    done
  done
}
```
- The comment says what it answers: whether both balancers see all three instances fully up in both backends that check them. `seatwave` and `seatwave_active_only` are the two backends with their own active checks; the other three track `seatwave` (`haproxy-config-walkthrough.md`).
- `local balancer backend` declares the two loop variables.
- Two nested `for` loops go over the two balancers and the two backends: four questions in all.
- `[[ "$(servers_up "$balancer" "$backend")" == "3" ]] || return 1` asks `servers_up` for the count and compares it with `3`. When one answer is not 3, `|| return 1` leaves the function at once with status 1, "not ready".
- When all four answers are 3, the loops end and the function ends. A function's exit status is that of its last command, here the successful `for` loop, so the function returns 0, "ready".

Both scripts use this one function: `stack.sh up` waits for it, and `lab.sh` refuses to start a lab without it. A function that returns 0 or 1 can be used straight in an `if`, as in `if ! stack_ready; then`.

### `stat_cell`: one cell of the statistics table

`scripts/common.sh`, lines 65 to 72:

```bash
# One column of one row of a balancer's statistics CSV (for example 8, stot, the requests passed).
stat_cell() {
  local port
  port="$(stats_port "$1")"
  curl -s --fail --max-time 2 "http://127.0.0.1:${port}/stats;csv" \
    | awk -F, -v backend="$2" -v server="$3" -v column="$4" \
        '$1 == backend && $2 == server { print $column; found = 1 } END { exit !found }'
}
```
- The comment says what it returns: one column of one row of a balancer's statistics CSV, for example column 8, `stot`, the total number of requests (sessions) that row has passed since the balancer started.
- `local port` and `port="$(stats_port "$1")"` find the status port of the balancer named by the first argument, as in `servers_up`.
- `curl -s --fail --max-time 2 "http://127.0.0.1:${port}/stats;csv"` fetches the table. `--fail` makes curl exit non-zero when the server answers with an HTTP error status, instead of printing the error page as if it were the table. There is no `|| true` here: a failed read must fail the function, because a missing number would make the lab's arithmetic wrong.
- `awk -F, -v backend="$2" -v server="$3" -v column="$4"` passes the backend, the row name and the column number into `awk`. The row name is a server's name, or `BACKEND` for the row that sums up the whole backend.
- `'$1 == backend && $2 == server { print $column; found = 1 } END { exit !found }'` prints the asked column of the matching row. `$column` is the field whose number the variable holds, so with `column=8` it is `$8`. `END { exit !found }` exits with 1 when no row matched, as in `wait_until_up` below. With `pipefail`, a failed `curl` or a missing row fails the whole function.

### `cpu_usec`: the CPU time a container has used

`scripts/common.sh`, lines 74 to 77:

```bash
# The CPU time a container has used since it started, in microseconds, from its control group.
cpu_usec() {
  docker exec "$PROJECT-$1-1" cat /sys/fs/cgroup/cpu.stat | awk '$1 == "usage_usec" { print $2 }'
}
```
- The comment says what it returns: the CPU time a container has used since it started, in microseconds (millionths of a second), from its **control group**. A control group (cgroup) is the Linux kernel's way to count and limit what a group of processes uses. Docker puts each container in its own group, and inside the container the file `/sys/fs/cgroup/cpu.stat` holds the group's counters.
- `docker exec "$PROJECT-$1-1" cat /sys/fs/cgroup/cpu.stat` runs `cat` inside the container and prints the file. `"$PROJECT-$1-1"` builds the container's name the way Compose names it: the project, the service and the number 1, for example `seatwave-balancer-lb1-1`. This is the plain `docker exec`, which takes a container name, not `docker compose exec`, which takes a service name; both reach the same container.
- `| awk '$1 == "usage_usec" { print $2 }'` keeps the one line whose first word is `usage_usec` and prints its second word, the number. The file's other lines (`user_usec`, `system_usec` and more) split the same time in other ways.
- Reading the counter twice and subtracting gives the CPU time used in between. The `cpu` lab in `lab.sh` does exactly that.

### `give_seats_back`: free every held seat

`scripts/common.sh`, lines 79 to 86:

```bash
# Gives every held seat back, so the next run finds all 20,000 free and its mix is what its label
# says. The lab's own database only; a throwaway lab, never a real sale.
give_seats_back() {
  "${compose[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -q -U seatwave -d seatwave \
    -c "UPDATE seats SET status = 'AVAILABLE', hold_id = NULL, hold_customer = NULL, hold_expires_at = NULL WHERE status = 'HELD'" \
    > /dev/null
  audit "$1: every held seat given back"
}
```
- The two comment lines give the reason. A hold run takes seats, and a held seat answers the next hold for it with 409 (taken). If the seats of one run stayed held, the next run would get more 409 answers, and its mix of answers would not be what its label says. So before and after every run the lab gives every held seat back, and the next run finds all 20,000 seats free. The comment also draws the line: this is the lab's own database, a throwaway lab, never a real sale.
- `"${compose[@]}" exec -T postgres psql ...` runs `psql`, PostgreSQL's command-line client, inside the `postgres` container. `-v ON_ERROR_STOP=1` sets a `psql` variable that tells it to stop at the first failing statement and exit with a non-zero status, so a failed statement can never pass as a success. `-q` (quiet) leaves out the extra messages. `-U seatwave` is the database account and `-d seatwave` the database.
- `-c "UPDATE seats SET status = 'AVAILABLE', hold_id = NULL, hold_customer = NULL, hold_expires_at = NULL WHERE status = 'HELD'"` is the one SQL statement it runs: every row of the table `seats` whose status is `HELD` gets the status `AVAILABLE`, and its three hold columns are emptied (`NULL` means "no value"). `sql-schema.md` explains the table and its columns. The text inside the double quotes uses single quotes for SQL's own strings, so the shell passes it on as one argument.
- `> /dev/null` throws away what `psql` prints: the word `UPDATE` and the number of rows changed.
- `audit "$1: every held seat given back"` writes an audit line, because this changes data. The caller passes its own name, for example `lab pair`.

### `address_holder`: who holds the floating address

`scripts/common.sh`, lines 88 to 103:

```bash
# Which keepalived holds the floating address: keepalived-1, keepalived-2, both or none.
address_holder() {
  local holders=()
  local node
  for node in 1 2; do
    if "${compose[@]}" exec -T "keepalived-$node" ip -4 -o addr show dev eth0 2> /dev/null \
        | grep -q " 10.210.10.100/"; then
      holders+=("keepalived-$node")
    fi
  done
  case "${#holders[@]}" in
    0) echo "none" ;;
    1) echo "${holders[0]}" ;;
    *) echo "both" ;;
  esac
}
```
- `local holders=()` makes an empty array. `local node` declares the loop variable.
- `for node in 1 2; do ... done` repeats for the values 1 and 2.
- `"${compose[@]}" exec -T "keepalived-$node" ip -4 -o addr show dev eth0 2> /dev/null` runs a command inside the container `keepalived-1` (then `keepalived-2`). `exec` runs a command in a running container, `-T` turns off the terminal that Compose would otherwise try to allocate (there is none in a script). The command is `ip -4 -o addr show dev eth0`: list the IPv4 addresses (`-4`) of the card `eth0`, one per line (`-o`). If the container is not running, the command fails and `2> /dev/null` hides the message.
- `| grep -q " 10.210.10.100/"` searches the list for the floating address. `-q` prints nothing and only sets the exit code. The backslash joins the lines. The `if` runs its body when the address is found.
- `holders+=("keepalived-$node")` appends to the array.
- `case "${#holders[@]}" in` looks at the number of items: `0)` prints `none`, `1)` prints the one name (`${holders[0]}` is the first item), and `*)` (two) prints `both`, which would be a split brain. The function reports; the callers decide.

### `wait_until_up`

`scripts/common.sh`, lines 105 to 130:

```bash
# Wait until both balancers see a server up again in both backends that check it.
wait_until_up() {
  local server="$1"
  local waited
  for (( waited = 0; waited < 60; waited++ )); do
    local all="yes"
    local balancer backend
    for balancer in lb1 lb2; do
      for backend in seatwave seatwave_active_only; do
        local port
        port="$(stats_port "$balancer")"
        if ! curl -s --max-time 2 "http://127.0.0.1:${port}/stats;csv" \
            | awk -F, -v backend="$backend" -v server="$server" \
                '$1 == backend && $2 == server && $18 == "UP" { found = 1 } END { exit !found }'; then
          all="no"
        fi
      done
    done
    if [[ "$all" == "yes" ]]; then
      return 0
    fi
    sleep 1
  done
  echo "$server was not up on both balancers within 60 s" >&2
  return 1
}
```
- The comment says what it does: wait until both balancers see one server up again in both backends that check it (`seatwave` and `seatwave_active_only`; the other three backends track the first).
- `local server="$1"` names the function's argument.
- The outer loop runs at most 60 times. Inside, `local all="yes"` starts each round by assuming success, and `local balancer backend` and `local port` declare the loop variables.
- Two nested `for` loops go over the two balancers and the two backends. For each, `port="$(stats_port "$balancer")"` finds the status port.
- `if ! curl -s --max-time 2 "..." | awk -F, -v backend="$backend" -v server="$server" '...'; then all="no"; fi` fetches the table and runs a small `awk` program: for a row whose proxy name is `backend`, whose server name is `server`, and whose status is exactly `UP` (as in `servers_up`), set `found = 1`. `END { exit !found }` exits with status 0 if `found` is set (`!found` is 0 when found is 1), and with status 1 if not. The `!` in front of the pipe flips it again, so `all` becomes `no` when the server is not up in that place.
- `if [[ "$all" == "yes" ]]; then return 0; fi` ends the function when every place saw the server up. `[[ ... ]]` is Bash's test command; `==` compares two strings.
- `sleep 1` and the loop try again.
- After 60 rounds: a message on the error stream and `return 1`.

### `scripts/stack.sh`: the header and the safety line

`scripts/stack.sh`, lines 1 to 21:

```bash
#!/usr/bin/env bash
# Starts, tests and stops the Module 10 stack: the shared database, three SeatWave instances, two
# HAProxy balancers and their keepalived, under the Compose project name seatwave-balancer, every
# published port on 127.0.0.1 only. Adapted from the Module 9 project's scripts/stack.sh.
#
# Usage: scripts/stack.sh <up|down|test|status> [--run] [--yes]
#   default   a dry run: print what would happen, change nothing
#   --run     do it; asks for confirmation on the terminal first
#   --yes     skip the question (for a reader who has already read the plan)
#
#   up      make the database password, build the jars and the images, start the stack, wait until
#           both balancers see all three instances up and one balancer holds the floating address
#   down    stop and remove every container, network and volume of the project (down -v)
#   test    up, ./gradlew stackTest, then down, on every path (also when a test fails)
#   status  list the project's containers and both balancers' view (read-only, no --run needed)
#
# Guardrails: the only target is the project seatwave-balancer; a dry run needs no Docker and
# changes nothing; with --run, Docker must answer first or nothing is done; every change appends
# an audit line to build/stack/audit.log; "down" checks afterwards that no container, network or
# volume of the project is left; any failure exits non-zero.
set -euo pipefail
```
- Lines 2 to 19 are comments that document the script. They give the usage: one of four actions and the options `--run` and `--yes`. With no `--run` the script is a **dry run**: it prints what it would do and changes nothing. With `--run` it asks first, unless `--yes` skips the question for a reader who has already read the plan.
- The comment lists the four actions: `up` (make the database password, build the jars and the images, start the stack, wait until both balancers see all three instances up and one balancer holds the floating address), `down` (stop and remove every container, network and volume of the project), `test` (up, run the stack tests, then down, even if a test fails) and `status` (list the containers and both balancers' view; read-only).
- The last comment block lists the **guardrails**: the only target is the project `seatwave-balancer`; a dry run needs no Docker and changes nothing; with `--run`, Docker must answer first or nothing is done; every change appends an audit line; `down` checks afterwards that nothing of the project is left; any failure exits non-zero. (Its second line is much longer than the others; the words are right, only the wrapping is uneven.)
- `set -euo pipefail` is three switches in one:
  - `-e`: stop the script when a command fails (returns a non-zero exit code). There are exceptions: a command used as the condition of an `if`, or on the left of `&&` or `||`, may fail without stopping the script.
  - `-u`: treat reading a variable that was never set as an error, instead of silently using an empty string. A typo in a name then stops the script.
  - `-o pipefail`: a pipe `a | b` normally has the exit code of `b` only. With this switch it fails if any stage failed.

### `scripts/stack.sh`: constants, arguments and the action

`scripts/stack.sh`, lines 23 to 44:

```bash
readonly SECRET_FILE="build/secrets/db.password"
readonly WAIT_SECONDS=90
readonly FLOATING_ADDRESS="10.210.10.100"

usage() {
  echo "usage: $0 <up|down|test|status> [--run] [--yes]" >&2
  exit 2
}

[[ $# -ge 1 ]] || usage
action="$1"
shift
mode="dry-run"
confirmed="no"
for option in "$@"; do
  case "$option" in
    --run) mode="run" ;;
    --yes) confirmed="yes" ;;
    *) usage ;;
  esac
done
case "$action" in up|down|test|status) ;; *) usage ;; esac
```
- Three `readonly` constants: the password file's path, how long `up` waits for the stack (90 seconds), and the floating address, which `up` prints in its last message. The Docker time limit lives in `common.sh` (`DOCKER_TIMEOUT_SECONDS`), next to the function that uses it.
- `usage() { echo "usage: $0 <up|down|test|status> [--run] [--yes]" >&2; exit 2; }` prints the usage line on the error stream and exits with status 2, the usual code for "wrong command line". `$0` is the name the script was started with.
- `[[ $# -ge 1 ]] || usage` checks that there is at least one argument. `$#` is the number of arguments and `-ge` means "greater than or equal". `||` runs `usage` when the test fails.
- `action="$1"` stores the first argument, and `shift` drops it so that `"$@"` holds only the options. `mode="dry-run"` and `confirmed="no"` are the defaults.
- `for option in "$@"; do ... done` goes over the remaining arguments. `"$@"` expands to each argument as one word. The `case` sets `mode="run"` for `--run`, `confirmed="yes"` for `--yes`, and calls `usage` for anything else (`*`).
- `case "$action" in up|down|test|status) ;; *) usage ;; esac` accepts only the four actions: the first branch does nothing (`;;` with no command), and anything else is a usage error. I checked: `scripts/stack.sh dance` and `scripts/stack.sh up --bogus` both printed the usage line and exited with status 2.

### `scripts/stack.sh`: where it runs and `status`

`scripts/stack.sh`, lines 46 to 57:

```bash
cd "$(dirname "$0")/.."
source scripts/common.sh

if [[ "$action" == "status" ]]; then
  docker_must_answer
  "${compose[@]}" ps --all
  for balancer in lb1 lb2; do
    echo "$balancer: $(servers_up "$balancer" seatwave) of 3 instances up in backend seatwave"
  done
  echo "floating address on: $(address_holder)"
  exit 0
fi
```
- `cd "$(dirname "$0")/.."` changes into the repository folder: `dirname "$0"` is the folder of the script (`scripts`), and `/..` is its parent. So the script works from any current folder, and the paths in it (`build/secrets`, `scripts/common.sh`) are relative to the repository.
- `source scripts/common.sh` loads the shared definitions.
- The `status` branch asks the running stack, so it needs Docker: `docker_must_answer` is its first line. The other actions check Docker later, after the plan (next section), so their dry runs need no Docker. (Version 1.0.0 called `docker_must_answer` here for every action, so even a dry run stopped when Docker was not running.)
- `"${compose[@]}" ps --all` lists the project's containers (also stopped ones). The `for` loop prints for each balancer how many servers it sees up in the backend `seatwave`. `$(servers_up "$balancer" seatwave)` runs the function and puts its output in the string. `address_holder` says who has the floating address. `exit 0` ends the script, so `status` never reaches the code below, and it needs no `--run`.

### `scripts/stack.sh`: the plan, the dry run and the question

`scripts/stack.sh`, lines 59 to 74:

```bash
echo "plan: $action the Compose project $PROJECT"
case "$action" in
  up) echo "  password into $SECRET_FILE; ./gradlew -q stackFiles; ${compose[*]} up -d --build --quiet-pull; wait until ready" ;;
  down) echo "  ${compose_all[*]} down -v --remove-orphans; check nothing of $PROJECT is left" ;;
  test) echo "  up, then ./gradlew stackTest, then down -v (always)" ;;
esac
if [[ "$mode" == "dry-run" ]]; then
  # Checked after the plan, so a dry run changes nothing and needs no Docker.
  echo "dry run: nothing changed. Add --run to do it."
  exit 0
fi
docker_must_answer
if [[ "$confirmed" != "yes" ]]; then
  read -r -p "Go ahead with '$action' for $PROJECT? [y/N] " answer < /dev/tty
  [[ "$answer" == "y" ]] || { echo "not confirmed; nothing changed" >&2; exit 1; }
fi
```
- `echo "plan: $action the Compose project $PROJECT"` prints what will happen.
- The `case "$action"` prints a second line for each action. `${compose[*]}` joins the array into one string with spaces, so `docker compose -p seatwave-balancer` appears in the text. The plan for `up` says `${compose[*]} up -d --build --quiet-pull; wait until ready`, the same options the real command uses further down. (Version 1.0.0's plan left out `--quiet-pull`.)
- `if [[ "$mode" == "dry-run" ]]; then ... exit 0; fi` ends the script with success after printing "dry run: nothing changed. Add --run to do it." The comment inside says why the check sits here: it comes after the plan, so a dry run changes nothing and needs no Docker. I confirmed it with a fake `docker` that always fails (step 4 of "Try it yourself"): the dry run of `stack.sh up` printed its plan and exited 0.
- `docker_must_answer` runs the Docker check for a real run, before the question below, so a reader is not asked to confirm something that cannot work.
- `if [[ "$confirmed" != "yes" ]]; then ... fi` asks, unless `--yes` was given. `read -r -p "..." answer < /dev/tty` reads a line from the **terminal** into `answer`. `-r` keeps backslashes as they are, `-p` shows the prompt. `< /dev/tty` makes it read from the keyboard even if the script's input is a pipe. Without a terminal, the read fails: in my run of version 1.0.0, `scripts/stack.sh down --run` with no terminal printed `/dev/tty: Device not configured` and exited with 1, having changed nothing. `[[ "$answer" == "y" ]] || { echo "not confirmed; nothing changed" >&2; exit 1; }` ends the script unless the answer is exactly `y`. The braces group two commands after `||`.

### `scripts/stack.sh`: `up`

`scripts/stack.sh`, lines 76 to 102:

```bash
up() {
  mkdir -p build/secrets
  if [[ ! -s "$SECRET_FILE" ]]; then
    # A throwaway lab password, new for every fresh stack, never committed (.gitignore) and never
    # copied into an image (.dockerignore). Mode rw-r--r--: on a Linux host the secret is a bind
    # mount that keeps this file's owner and mode, and it is read by other users (PostgreSQL's
    # entrypoint as root and again as postgres, SeatWave as uid 10001), so owner-only would fail
    # there. Docker Desktop on a Mac shows a shared file as owned by its reader. So anyone with an
    # account on this machine can read this lab's password, as the README says.
    # head ends the pipe itself: "tr < /dev/urandom | head" would end with SIGPIPE, and pipefail
    # would stop the script there without a word (the first version of this script did).
    (umask 022; head -c 24 /dev/urandom | base64 | tr -d '/+=\n' > "$SECRET_FILE")
  fi
  ./gradlew -q stackFiles
  "${compose[@]}" up -d --build --quiet-pull
  audit "up: started $PROJECT"
  for (( waited = 0; waited < WAIT_SECONDS; waited++ )); do
    if stack_ready && [[ "$(address_holder)" != "none" ]]; then
      echo "stack is up after ${waited} s: 3 of 3 instances up on lb1 and lb2; $FLOATING_ADDRESS on $(address_holder)"
      return 0
    fi
    sleep 1
  done
  echo "the stack was not ready within ${WAIT_SECONDS} s" >&2
  "${compose[@]}" logs --tail 20 >&2
  return 1
}
```
- `up() { ... }` defines the function that does the work. It is defined here and called at the end.
- `mkdir -p build/secrets` makes the folder for the password.
- `if [[ ! -s "$SECRET_FILE" ]]; then ... fi` runs when the password file does not exist or is empty (`-s` tests "exists and is not empty"; `!` flips it). An existing password is kept, so a second `up` against a surviving database volume still matches.
- The eight comment lines explain the password, its file mode and a bug. The password is a throwaway lab password, new for every fresh stack, never committed (`.gitignore`) and never copied into an image (`.dockerignore`). The middle lines explain the **mode** `rw-r--r--`: the owner may read and write the file, and everybody else may read it. Compose hands the file to the containers as a **secret**. On a Linux host that secret is a **bind mount**: the container sees the host's own file, with its owner and mode. And the file is read by users other than its owner: PostgreSQL's start script reads it as root and again as the user `postgres`, and SeatWave reads it as user 10001. A file only its owner may read (mode `rw-------`) would fail there. Docker Desktop on a Mac shows a shared file as owned by whoever reads it, so the problem does not show up on a Mac. The comment states the price plainly: anyone with an account on this machine can read this lab's password, as the README says. The last two lines say why `head` is used: `tr < /dev/urandom | head` would end with SIGPIPE (the signal a program gets when the reader of its pipe has gone) and `pipefail` would stop the script without a word; "the first version of this script did".
- `(umask 022; head -c 24 /dev/urandom | base64 | tr -d '/+=\n' > "$SECRET_FILE")` runs in a **subshell** (the parentheses), so the `umask` change does not leak out. `umask 022` makes the new file readable by everybody (mode 644, the `rw-r--r--` of the comment), which lets the container users read it. `head -c 24 /dev/urandom` takes 24 random bytes from the system's random source and then ends the pipe itself. `base64` turns them into 32 letters and digits (and `/`, `+`). `tr -d '/+=\n'` deletes the characters `/`, `+`, `=` and the newline, which can trouble a URL or a settings file. The result is written to the file. I checked: the file had 32 characters and mode `-rw-r--r--`. Readable by everybody on the machine also means by other accounts on it. That is acceptable for a throwaway lab password in an ignored folder that `down` deletes, and the comment says why.
- `./gradlew -q stackFiles` collects the jars (see `gradle.md`); `-q` means quiet.
- `"${compose[@]}" up -d --build --quiet-pull` builds the images and starts all services except the `tools` profile. `-d` returns once they are started (detached), `--build` builds images first, and `--quiet-pull` hides the progress bars of image downloads.
- `audit "up: started $PROJECT"` writes the audit line.
- The wait loop runs up to `WAIT_SECONDS` (90) times. `$(( ... ))` is arithmetic, here only the loop's counter. Inside, `if stack_ready && [[ "$(address_holder)" != "none" ]]; then` asks two things: `stack_ready` (both balancers see all three instances fully up in both checking backends, see `common.sh` above), and then, only if that succeeded (`&&`), whether some keepalived holds the floating address. When both hold, it prints for example `stack is up after 4 s: 3 of 3 instances up on lb1 and lb2; 10.210.10.100 on keepalived-1` (the re-measured run of version 1.1.0) and `return 0`. Otherwise `sleep 1`.
- After the loop: the message "the stack was not ready within 90 s" on the error stream, the last 20 log lines of all services (`logs --tail 20 >&2`), and `return 1`, which `set -e` turns into the end of the script.

### `scripts/stack.sh`: `down`

`scripts/stack.sh`, lines 104 to 121:

```bash
down() {
  "${compose_all[@]}" down -v --remove-orphans
  audit "down: removed $PROJECT"
  # Check after: nothing of this project may be left.
  local left
  # One id per line, from all three lists, so the message shows each one apart.
  left="$(docker ps -aq --filter "label=com.docker.compose.project=$PROJECT";
          docker network ls -q --filter "label=com.docker.compose.project=$PROJECT";
          docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT")"
  if [[ -n "$left" ]]; then
    echo "something of $PROJECT is still there:" >&2
    echo "$left" >&2
    return 1
  fi
  # The password belongs to the database that was just removed; the next stack makes a new one.
  rm -f "$SECRET_FILE"
  echo "stack is down: no container, network or volume of $PROJECT is left"
}
```
- `"${compose_all[@]}" down -v --remove-orphans` stops and removes the containers, the network and (with `-v`) the volumes, including the harness's container (the `--profile tools`). `--remove-orphans` also removes containers of this project that the file no longer lists.
- `audit` records it.
- The comment says "Check after: nothing of this project may be left." `local left` is a string.
- The next comment and the three lines after it fill `left` with "one id per line, from all three lists". One `$( ... )` holds three commands, separated by `;` and line breaks, and collects everything they print. `docker ps -aq --filter "label=com.docker.compose.project=$PROJECT"` lists the ids of all containers (`-a`: stopped ones too; `-q`: only ids) that carry the project label. `docker network ls -q` and `docker volume ls -q` with the same filter list the networks and volumes. The label is set by Compose on everything it creates. Each command ends its output with a line break, so the ids stay one per line; `$( ... )` drops only the line breaks at the very end.
- `if [[ -n "$left" ]]; then ... return 1; fi`: `-n` tests "not empty". If anything is left, it prints "something of seatwave-balancer is still there:" on one line and then the ids, one per line, on the error stream, and fails. (Version 1.0.0 built the string from three separate `$( ... )` pieces joined with `+=`, and each piece had lost its last line break, so two ids could print as one run of characters.)
- `rm -f "$SECRET_FILE"` deletes the password, because it belongs to the database that was just removed; `-f` means no error if it is already gone. The last line prints "stack is down: no container, network or volume of seatwave-balancer is left".

### `scripts/stack.sh`: the dispatch

`scripts/stack.sh`, lines 123 to 132:

```bash
case "$action" in
  up) up ;;
  down) down ;;
  test)
    # down runs on every path out of this block, including a failed test.
    trap 'down' EXIT
    up
    ./gradlew stackTest
    ;;
esac
```
- `up) up ;;` and `down) down ;;` call the functions of the same names.
- `test)` calls both. `trap 'down' EXIT` registers a command to run when the script ends, **for any reason**: a normal end, a failed command under `set -e`, or `up` returning 1. A **trap** is a handler for an event; `EXIT` is the event "the shell is leaving". The comment says that `down` runs on every path out of the block, including a failed test. Then `up` and `./gradlew stackTest` run. If the tests fail, `set -e` ends the script, the trap runs `down`, and the script's exit status is still the failure. I checked the other direction with a small script: when the body succeeds and the trap's function returns 1, the whole script exits with 1, so a leftover is reported.

### `scripts/lab.sh`: the header

`scripts/lab.sh`, lines 1 to 30:

```bash
#!/usr/bin/env bash
# The Module 10 labs against the running stack (scripts/stack.sh up): the harness runs load inside
# the lab network and reports, while this script does the one disruptive thing the lab is about,
# at a fixed time into the run, then puts the stack back the way it was.
#
# Usage: scripts/lab.sh <lab> [--run] [--yes]
#   share             Lab 1: four runs, round robin, least connections, weighted, source hashing
#   kill-active       Lab 2: SIGKILL seatwave-2 10 s into the run, behind the active-only backend
#   kill-production   Lab 2: the same kill behind PRODUCTION's backend
#   stop-active       Section 9: SIGTERM seatwave-2 with no drain at the balancer, active-only
#   stop-production   Section 9: the same stop behind PRODUCTION's backend
#   deploy-production Lab 3: the rolling deploy of all three instances, load through PRODUCTION
#   deploy-active     Lab 3: the same deploy, load through the active-only backend
#   pair              Lab 3: SIGKILL the balancer that holds the floating address, 12 s into a run
#                     against that address
#   cpu               Section 8: the CPU lb1 and seatwave-1 use per 1,000 requests, read from their
#                     control groups around one round-robin run; nothing is disrupted
#   default a dry run: print the plan, change nothing; --run does it after a question; --yes skips it
#
# Guardrails: the only target is the project seatwave-balancer and, for each lab, one named
# container; Docker must answer and the stack must be ready first; every held seat is given back
# before and after each run, so each run's mix is the one its label says; each disruption appends
# an audit line to build/stack/audit.log; the stack is put back and checked ready afterwards, also
# when the disruption itself failed; any failure exits non-zero. Every report is written to
# build/runs/<lab>.txt.
#
# The "+10 s" before a disruption is this script's clock: it sleeps 5 s of warm-up plus 10 s after
# starting the harness, whose own clock starts a moment later, so the harness's report shows the
# disruption a little before +10 s (its "last answer from" line).
set -euo pipefail
```
- The header lists the nine labs and what each does. In short: `share` runs four load runs with four algorithms and disrupts nothing; `kill-active` and `kill-production` kill `seatwave-2` with SIGKILL (the signal a program cannot catch, like a power cut) 10 seconds into a run, behind the active-only backend and behind PRODUCTION; `stop-active` and `stop-production` send it SIGTERM ("please finish and exit") through `docker compose stop` instead; `deploy-production` and `deploy-active` run the rolling deploy during a run; `pair` kills the balancer that holds the floating address, 12 seconds into a run against that address; `cpu` measures the CPU time that `lb1` and `seatwave-1` use per 1,000 requests, read from their control groups around one round-robin run, and disrupts nothing (Section 8 of the module). The default is a dry run, `--run` does it after a question, `--yes` skips the question.
- The guardrails: the only target is the Compose project, and for each lab one named container; Docker must answer and the stack must be ready first; every held seat is given back before and after each run, so each run's mix is the one its label says; each disruption appends an audit line; the stack is put back and checked ready afterwards, also when the disruption itself failed; any failure exits non-zero. Every report is written to `build/runs/<lab>.txt`.
- The last comment block explains the "+10 s" that the script prints before a disruption. It is this script's own clock: it sleeps 5 s of warm-up plus 10 s after it starts the harness. The harness's own clock starts a moment later (the container must start first), so the harness's report shows the disruption a little before +10 s, in its "last answer from" line. In the re-measured kill run of version 1.1.0 that line said `+9.611 s`.
- `set -euo pipefail` as in `stack.sh`.

### `scripts/lab.sh`: usage and arguments

`scripts/lab.sh`, lines 32 to 48:

```bash
usage() {
  echo "usage: $0 <share|kill-active|kill-production|stop-active|stop-production|deploy-production|deploy-active|pair|cpu> [--run] [--yes]" >&2
  exit 2
}

[[ $# -ge 1 ]] || usage
lab="$1"
shift
mode="dry-run"
confirmed="no"
for option in "$@"; do
  case "$option" in
    --run) mode="run" ;;
    --yes) confirmed="yes" ;;
    *) usage ;;
  esac
done
```
- `usage` prints the list of the nine lab names and exits with status 2.
- `[[ $# -ge 1 ]] || usage` requires a lab name. `lab="$1"` and `shift` take it, and `mode` and `confirmed` start as in `stack.sh`.
- The `for` and `case` read `--run` and `--yes` and reject anything else. I checked: `scripts/lab.sh nothing` and `scripts/lab.sh share --nope` printed the usage line and exited with 2.

### `scripts/lab.sh`: constants and the plan

`scripts/lab.sh`, lines 50 to 64:

```bash
# Seconds into the run (after the 5 s warm-up starts) when the disruption happens.
readonly WARMUP_SECONDS=5
readonly DISRUPT_AFTER_SECONDS=10
readonly PAIR_DISRUPT_AFTER_SECONDS=12
readonly DEPLOY_AFTER_SECONDS=8

case "$lab" in
  share) plan="four 20 s runs at 3,000 requests/s; nothing is disrupted" ;;
  kill-active|kill-production) plan="docker kill -s KILL seatwave-2 at +${DISRUPT_AFTER_SECONDS} s; then start it again" ;;
  stop-active|stop-production) plan="docker compose stop seatwave-2 (SIGTERM) at +${DISRUPT_AFTER_SECONDS} s; then start it again" ;;
  deploy-production|deploy-active) plan="./gradlew -q deploy (all three instances, one at a time) at +${DEPLOY_AFTER_SECONDS} s" ;;
  pair) plan="docker kill -s KILL on the balancer holding 10.210.10.100 at +${PAIR_DISRUPT_AFTER_SECONDS} s; then start it and restart its keepalived" ;;
  cpu) plan="one 20 s round-robin run at 3,000 requests/s, with lb1's and seatwave-1's CPU read before and after; nothing is disrupted" ;;
  *) usage ;;
esac
```
- The comment says the numbers count seconds into the run, after the warm-up starts. `readonly WARMUP_SECONDS=5` is the harness's warm-up, `DISRUPT_AFTER_SECONDS=10` the time of a kill or a stop, `PAIR_DISRUPT_AFTER_SECONDS=12` the time of the balancer kill (longer, so the harness has a full steady period first), and `DEPLOY_AFTER_SECONDS=8` the time the deploy starts. The `cpu` lab disrupts nothing, so it needs no offset.
- The `case "$lab"` sets the text `plan` for each lab. `kill-active|kill-production)` has two names in one branch: `|` means "or". `${DISRUPT_AFTER_SECONDS}` puts the constant into the text. The `cpu)` branch describes its one run: 20 s of round robin at 3,000 requests a second, with the CPU of `lb1` and `seatwave-1` read before and after. The last branch `*) usage ;;` rejects an unknown lab before anything else happens.

### `scripts/lab.sh`: the checks before the lab

`scripts/lab.sh`, lines 66 to 85:

```bash
cd "$(dirname "$0")/.."
source scripts/common.sh

echo "plan: lab $lab on the Compose project $PROJECT: $plan"
if [[ "$mode" == "dry-run" ]]; then
  echo "dry run: nothing changed. Add --run to do it."
  exit 0
fi
docker_must_answer
if ! stack_ready; then
  echo "the stack is not ready (scripts/stack.sh up --run first); nothing changed" >&2
  exit 1
fi
if [[ "$confirmed" != "yes" ]]; then
  read -r -p "Go ahead with lab '$lab'? [y/N] " answer < /dev/tty
  [[ "$answer" == "y" ]] || { echo "not confirmed; nothing changed" >&2; exit 1; }
fi
mkdir -p build/runs
# Every seat is given back on every way out, also when a step below fails.
trap 'give_seats_back "lab $lab"' EXIT
```
- `cd "$(dirname "$0")/.."` and `source scripts/common.sh` are the same as in `stack.sh`.
- `echo "plan: lab $lab on the Compose project $PROJECT: $plan"` prints the plan, and the dry run ends here with success. The Docker check is below it, so a dry run of a lab needs no Docker.
- `docker_must_answer` is the first check before a real run.
- `if ! stack_ready; then ... exit 1; fi` is the readiness check, the same function `stack.sh up` waits for: both balancers must see all three instances fully up in both checking backends, `seatwave` and `seatwave_active_only`. `!` flips the function's result. If the stack is not ready, it prints "the stack is not ready (scripts/stack.sh up --run first); nothing changed" on the error stream and exits. (Version 1.0.0 checked only the backend `seatwave`, so an active-only lab started right after an instance restart could begin while that backend still counted the instance as down.)
- The confirmation question is the same as in `stack.sh`.
- `mkdir -p build/runs` makes the folder for the reports.
- `trap 'give_seats_back "lab $lab"' EXIT` makes Bash run `give_seats_back` whenever the script ends from here on: after the last line, at an `exit`, or when `set -e` stops it after a failed command. So the seats the run held are given back on every way out, also when a step fails halfway. The comment above it says so.

### `scripts/lab.sh`: the harness and the disruption

`scripts/lab.sh`, lines 87 to 116:

```bash
# Runs the harness with one settings file and writes its report, on a database whose seats are
# all free.
harness() {
  # "|| return 1": inside disrupted_run, called left of "||", Bash turns set -e off, so a failed
  # reset must stop the run by hand rather than measure on held seats.
  give_seats_back "lab $lab" || return 1
  "${compose_all[@]}" run --rm -T harness "/app/config/$1.properties" 2>&1 \
    | grep -v -E '^ ?(Container|Network|Volume) ' > "build/runs/$2.txt"
}

# Starts the harness in the background, waits, runs one command, and waits for the report. If the
# command fails, the run still ends and the stack is still put back; the lab then exits non-zero.
disrupted_run() {
  local settings="$1" seconds="$2"
  shift 2
  harness "$settings" "$lab" &
  local harness_job=$!
  sleep "$(( WARMUP_SECONDS + seconds ))"
  audit "lab $lab: $*"
  echo "+${seconds} s: $*"
  local status=0
  "$@" || status=$?
  if (( status != 0 )); then
    echo "the disruption failed with exit status $status; the run goes on, then the stack is put back" >&2
  fi
  wait "$harness_job" || status=$?
  return "$status"
}

failed=0
```
- The comment above `harness()` says it runs the harness with one settings file and writes its report, "on a database whose seats are all free".
- `harness() { ... }` runs the load generator. Its first line, `give_seats_back "lab $lab" || return 1`, frees every held seat (`common.sh` above), so each run starts from the same 20,000 free seats. The comment explains the `|| return 1`: `disrupted_run` is called on the left of `||` (below), and inside a function called that way Bash switches `set -e` off, so a failed reset would not stop anything by itself; `return 1` ends `harness` with a failure, the run is not made on held seats, and `wait` in `disrupted_run` reports the failure. Then `"${compose_all[@]}" run --rm -T harness "/app/config/$1.properties"` starts the `harness` service once (`run`), removes its container afterwards (`--rm`), without a terminal (`-T`), and gives it the settings file named by the first argument as its argument. `2>&1` joins the error stream to the output. `| grep -v -E '^ ?(Container|Network|Volume) '` throws away the lines that Compose prints about starting and stopping its objects: `-v` inverts the match, `-E` allows the pattern `^ ?(Container|Network|Volume) ` (a line that starts with an optional space and one of three words). `> "build/runs/$2.txt"` writes what is left to the report file named by the second argument.
- The two comment lines above `disrupted_run()` give its promise: it starts the harness in the background, waits, runs one command and waits for the report, and if the command fails, the run still ends and the stack is still put back; the lab then exits non-zero.
- `disrupted_run() { ... }` does one lab with one disruption. `local settings="$1" seconds="$2"` names the first two arguments and `shift 2` drops them, so `"$@"` is now the disruptive command itself.
- `harness "$settings" "$lab" &` starts the harness in the background, and `local harness_job=$!` remembers its process number.
- `sleep "$(( WARMUP_SECONDS + seconds ))"` waits the warm-up (5 s) plus the lab's offset (10 s for a kill), then:
- `audit "lab $lab: $*"` writes the audit line (`$*` is all the remaining arguments as one string), `echo "+${seconds} s: $*"` prints what is about to happen, and `"$@"` **runs the disruptive command**: for example `docker compose -p seatwave-balancer kill -s KILL seatwave-2`.
  - `local status=0` starts a variable for the outcome.
  - `"$@" || status=$?` runs the command and, if it fails, stores its exit code. A command on the left of `||` does not stop the script under `set -e`, so a failed disruption cannot cut the lab short while the harness is still running in the background.
  - `if (( status != 0 )); then ... fi` prints, on the error stream, "the disruption failed with exit status N; the run goes on, then the stack is put back". `(( ... ))` is an arithmetic test.
- `wait "$harness_job" || status=$?` waits for the harness to finish and, if the harness failed, stores the harness's exit code instead.
- `return "$status"` hands the outcome to the caller: 0 when both the disruption and the run succeeded.
- `failed=0`, after the function, is the variable that holds the lab's final exit status. Each disruptive lab below calls `disrupted_run ... || failed=$?`: the `||` again keeps `set -e` from stopping the script, and the code that puts the stack back still runs. The script's very last line, `exit "$failed"`, then exits non-zero. (Version 1.0.0 called `disrupted_run` plainly, so a failed `docker kill` stopped the script at once and skipped the clean-up.)

The "+10 s" in the printed line is the script's own clock, counted from the moment the harness was started, as the header comment says. The harness measures from the end of its warm-up and takes a little time to start up, so in the harness's report the kill appears a little before +10 s: at +9.611 s in the re-measured kill run of version 1.1.0.

### `scripts/lab.sh`: which lab does what

`scripts/lab.sh`, lines 118 to 160:

```bash
case "$lab" in
  share)
    for algorithm in roundrobin leastconn weighted source; do
      harness "share-$algorithm" "share-$algorithm"
      echo "== share-$algorithm"
      cat "build/runs/share-$algorithm.txt"
    done
    exit 0
    ;;
  cpu)
    # CPU and requests are read at the same two moments, so the rate divides one set by itself.
    # The span includes the warm-up, the harness's start and lb1's own work (checks, show stat).
    lb_before="$(cpu_usec lb1)"; sw_before="$(cpu_usec seatwave-1)"
    passed_before="$(stat_cell lb1 seatwave BACKEND 8)"; served_before="$(stat_cell lb1 seatwave seatwave-1 8)"
    harness share-roundrobin cpu
    lb_after="$(cpu_usec lb1)"; sw_after="$(cpu_usec seatwave-1)"
    passed_after="$(stat_cell lb1 seatwave BACKEND 8)"; served_after="$(stat_cell lb1 seatwave seatwave-1 8)"
    grep -E '^(run|sent|mix|latency|balancer lb1)' build/runs/cpu.txt
    awk -v used="$(( lb_after - lb_before ))" -v count="$(( passed_after - passed_before ))" \
      'BEGIN { printf "cpu: lb1 used %.1f ms of CPU for %d requests passed: %.1f ms per 1,000 requests\n", used / 1000, count, used / count }'
    awk -v used="$(( sw_after - sw_before ))" -v count="$(( served_after - served_before ))" \
      'BEGIN { printf "cpu: seatwave-1 used %.1f ms of CPU for %d requests: %.1f ms per 1,000 requests\n", used / 1000, count, used / count }'
    exit 0
    ;;
  kill-active) disrupted_run kill-active-only "$DISRUPT_AFTER_SECONDS" "${compose[@]}" kill -s KILL seatwave-2 || failed=$? ;;
  kill-production) disrupted_run kill-production "$DISRUPT_AFTER_SECONDS" "${compose[@]}" kill -s KILL seatwave-2 || failed=$? ;;
  stop-active) disrupted_run stop-active-only "$DISRUPT_AFTER_SECONDS" "${compose[@]}" stop seatwave-2 || failed=$? ;;
  stop-production) disrupted_run stop-production "$DISRUPT_AFTER_SECONDS" "${compose[@]}" stop seatwave-2 || failed=$? ;;
  deploy-production) disrupted_run deploy-production "$DEPLOY_AFTER_SECONDS" ./gradlew -q deploy --args='config/deploy.properties --run --yes' || failed=$? ;;
  deploy-active) disrupted_run deploy-active-only "$DEPLOY_AFTER_SECONDS" ./gradlew -q deploy --args='config/deploy.properties --run --yes' || failed=$? ;;
  pair)
    holder="$(address_holder)"
    case "$holder" in
      keepalived-1) victim="lb1" ;;
      keepalived-2) victim="lb2" ;;
      *) echo "the floating address is on '$holder', not on exactly one balancer; nothing changed" >&2; exit 1 ;;
    esac
    echo "floating address before: $holder, so the balancer to kill is $victim"
    disrupted_run pair "$PAIR_DISRUPT_AFTER_SECONDS" "${compose[@]}" kill -s KILL "$victim" || failed=$?
    echo "floating address after the kill: $(address_holder)"
    ;;
esac
cat "build/runs/$lab.txt"
```
- `share)` runs a loop over four algorithm names: for each it calls `harness share-<name> share-<name>` with the algorithm name filled in (`roundrobin`, `leastconn`, `weighted`, `source`), which frees the seats and writes `build/runs/share-<name>.txt`, then prints a heading and the report with `cat`. Nothing is disrupted. `exit 0` ends the script, so the code below runs only for the other labs; the `EXIT` trap then frees the seats the last run held, so the lab leaves the database as it found it.
- `cpu)` measures how much CPU time the balancer and one instance spend per request. The two comment lines explain the method: the CPU counters and the request counters are read at the same two moments, so the rate divides one set of differences by the other; and the span between the readings includes the warm-up, the harness's start and `lb1`'s own work (its health checks and the harness's `show stat` questions), so the number is a little higher than the cost of the requests alone.
  - `lb_before="$(cpu_usec lb1)"; sw_before="$(cpu_usec seatwave-1)"` reads the CPU time of the two containers. `;` separates two commands on one line.
  - `passed_before="$(stat_cell lb1 seatwave BACKEND 8)"` reads column 8 (`stot`) of the `BACKEND` row of `lb1`'s backend `seatwave`: every request that backend has passed on. `served_before="$(stat_cell lb1 seatwave seatwave-1 8)"` reads the same column of the row of `seatwave-1`: the requests `lb1` sent to that instance.
  - `harness share-roundrobin cpu` runs the round-robin settings file (20 s at 3,000 requests a second, through `lb1`) and writes `build/runs/cpu.txt`.
  - The two lines after it read the same four numbers again. (The seats are freed by the `EXIT` trap when the lab ends.)
  - `grep -E '^(run|sent|mix|latency|balancer lb1)' build/runs/cpu.txt` prints only the report lines that start with one of those five words: the run's settings, the counts, the mix, the latency and `lb1`'s own counts.
  - `awk -v used="$(( lb_after - lb_before ))" -v count="$(( passed_after - passed_before ))" 'BEGIN { ... }'` does the division. `$(( ... ))` is the shell's whole-number arithmetic, here two subtractions, passed into `awk` as `used` (microseconds of CPU) and `count` (requests). The shell cannot do decimal fractions, so `awk` does the rest: a program with only a `BEGIN` block runs once and reads no input. `printf` prints with a format: `%.1f` is a number with one decimal, `%d` a whole number, `\n` a line break. `used / 1000` turns microseconds into milliseconds. `used / count` is microseconds per request, which is the same number as milliseconds per 1,000 requests.
  - The second `awk` line does the same for `seatwave-1`, with the requests `lb1` sent to it.
  - In the re-measured run of version 1.1.0 the two lines said `cpu: lb1 used 5226.5 ms of CPU for 75000 requests passed: 69.7 ms per 1,000 requests` and `cpu: seatwave-1 used 8818.8 ms of CPU for 25000 requests: 352.8 ms per 1,000 requests`. The 75,000 requests are more than the 60,000 the harness counted, because the counters also ran during the 5 s warm-up (15,000 requests). `exit 0` ends the lab; nothing needs to be put back.
- `kill-active)` and `kill-production)` call `disrupted_run ... || failed=$?` (explained above) with the harness settings `kill-active-only` and `kill-production` (files in `config/`), the offset, and the command `"${compose[@]}" kill -s KILL seatwave-2`. `kill -s KILL` sends SIGKILL to the container's process, with no warning. The two labs differ only in the settings file, which names the door (port 8082 or 8080).
- `stop-active)` and `stop-production)` do the same with `"${compose[@]}" stop seatwave-2`, which sends SIGTERM and waits for the container to exit (up to the 30 s `stop_grace_period`). SeatWave handles SIGTERM by draining.
- `deploy-production)` and `deploy-active)` use `./gradlew -q deploy --args='config/deploy.properties --run --yes'` as the disruptive command: the Gradle task that runs the deploy tool (`java-deploy-walkthrough.md`). The single quotes keep `--args=...` and what follows as one word for Gradle.
- `pair)` first decides which balancer to kill. `holder="$(address_holder)"` asks who has the floating address. The inner `case` maps `keepalived-1` to `victim="lb1"` and `keepalived-2` to `victim="lb2"`. Anything else (`none` or `both`) is refused: "the floating address is on '...', not on exactly one balancer; nothing changed". Then it prints which balancer it will kill, runs `disrupted_run` with `kill -s KILL "$victim"`, and prints where the address went.
- `esac` ends the `case`; the next line, `cat "build/runs/$lab.txt"`, prints the report of the lab (the harness's output with the Compose progress lines removed). The seats the run held are freed by the `EXIT` trap at the end.

### `scripts/lab.sh`: putting the stack back

`scripts/lab.sh`, lines 162 to 180:

```bash
# Put the stack back, and check it.
case "$lab" in
  kill-*|stop-*)
    "${compose[@]}" start seatwave-2
    wait_until_up seatwave-2
    audit "lab $lab: seatwave-2 started again"
    echo "seatwave-2 is up again on both balancers"
    ;;
  pair)
    "${compose[@]}" start "$victim"
    # The keepalived lives in its balancer's network namespace, which the restart replaced.
    "${compose[@]}" restart "keepalived-${victim#lb}"
    audit "lab $lab: $victim and keepalived-${victim#lb} started again"
    sleep 6
    echo "floating address after $victim came back: $(address_holder)"
    ;;
esac
exit "$failed"

```
- `kill-*|stop-*)` is a pattern: any lab name that starts with `kill-` or `stop-`. `"${compose[@]}" start seatwave-2` starts the stopped instance, `wait_until_up seatwave-2` waits until both balancers see it up in both checking backends (up to 60 seconds), `audit` records it, and the script prints "seatwave-2 is up again on both balancers".
- `pair)` starts the killed balancer and restarts its keepalived. The comment says why: the keepalived lives in its balancer's network namespace, which the restart replaced (see `docker-compose.md`). `${victim#lb}` removes the leading `lb` from `lb1`, leaving `1`, so `"keepalived-${victim#lb}"` is `keepalived-1`. After `sleep 6` it prints where the address is. In the re-measured run of version 1.1.0 the lab had killed `lb1` and printed `floating address after lb1 came back: keepalived-2`: the address stayed where it had moved (`nopreempt`).
- The `deploy-*` labs need no clean-up: the deploy ends with every instance up. (`share` and `cpu` already ended with `exit 0`.)
- `exit "$failed"` ends the script with the lab's status: 0, or the exit code of the failed disruption or run. Because it is the last line, the stack has been put back first. If putting it back fails (for example `wait_until_up` gives up after 60 s), `set -e` ends the script there with that failure instead.

## Try it yourself

I ran steps 1, 2 and 4 on 11 November 2026 in a scratch copy of the repository at version 1.1.0, so the project name is the real one, `seatwave-balancer`. They are dry runs and refusals: they change nothing and need no Docker. Steps 3 and 5 need the running stack; their outputs come from the re-measured run of version 1.1.0. Run everything from the repository folder. Timings differ on every run.

1. See the plans without changing anything (dry runs; Docker need not be running):

```
scripts/stack.sh up
scripts/stack.sh down
scripts/lab.sh pair
scripts/lab.sh cpu
```

Output:

```
plan: up the Compose project seatwave-balancer
  password into build/secrets/db.password; ./gradlew -q stackFiles; docker compose -p seatwave-balancer up -d --build --quiet-pull; wait until ready
dry run: nothing changed. Add --run to do it.
plan: down the Compose project seatwave-balancer
  docker compose -p seatwave-balancer --profile tools down -v --remove-orphans; check nothing of seatwave-balancer is left
dry run: nothing changed. Add --run to do it.
plan: lab pair on the Compose project seatwave-balancer: docker kill -s KILL on the balancer holding 10.210.10.100 at +12 s; then start it and restart its keepalived
dry run: nothing changed. Add --run to do it.
plan: lab cpu on the Compose project seatwave-balancer: one 20 s round-robin run at 3,000 requests/s, with lb1's and seatwave-1's CPU read before and after; nothing is disrupted
dry run: nothing changed. Add --run to do it.
```

2. The refusals. A wrong action, a wrong lab and a missing argument exit with status 2 and print the usage line:

```
scripts/stack.sh dance; echo "exit=$?"
scripts/lab.sh nothing; echo "exit=$?"
scripts/lab.sh; echo "exit=$?"
```

Output:

```
usage: scripts/stack.sh <up|down|test|status> [--run] [--yes]
exit=2
usage: scripts/lab.sh <share|kill-active|kill-production|stop-active|stop-production|deploy-production|deploy-active|pair|cpu> [--run] [--yes]
exit=2
usage: scripts/lab.sh <share|kill-active|kill-production|stop-active|stop-production|deploy-production|deploy-active|pair|cpu> [--run] [--yes]
exit=2
```

3. Start the stack and read its last line, then the read-only status (this needs Docker; the output is the re-measured run's):

```
scripts/stack.sh up --run --yes 2>&1 | tail -1
scripts/stack.sh status | tail -3
```

Output (the status lines use the same functions as the wait loop; the holder of the address varies from start to start):

```
stack is up after 4 s: 3 of 3 instances up on lb1 and lb2; 10.210.10.100 on keepalived-1
lb1: 3 of 3 instances up in backend seatwave
lb2: 3 of 3 instances up in backend seatwave
floating address on: keepalived-1
```

4. The Docker check. A fake `docker` that always fails shows what the scripts do when Docker is not running. The fake is a two-line file `fakebin/docker` with `#!/bin/sh` and `exit 1`, made executable with `chmod +x` and put first on the path for one command. The commands with `--run --yes` stop at the Docker check, before anything is done:

```
PATH="$PWD/fakebin:$PATH" scripts/stack.sh up; echo "exit=$?"
PATH="$PWD/fakebin:$PATH" scripts/stack.sh up --run --yes; echo "exit=$?"
PATH="$PWD/fakebin:$PATH" scripts/stack.sh status; echo "exit=$?"
PATH="$PWD/fakebin:$PATH" scripts/lab.sh share --run --yes; echo "exit=$?"
```

Output:

```
plan: up the Compose project seatwave-balancer
  password into build/secrets/db.password; ./gradlew -q stackFiles; docker compose -p seatwave-balancer up -d --build --quiet-pull; wait until ready
dry run: nothing changed. Add --run to do it.
exit=0
plan: up the Compose project seatwave-balancer
  password into build/secrets/db.password; ./gradlew -q stackFiles; docker compose -p seatwave-balancer up -d --build --quiet-pull; wait until ready
Docker is not running (or does not answer within 10 s).
Start Docker Desktop and run this again; nothing was started or stopped.
exit=1
Docker is not running (or does not answer within 10 s).
Start Docker Desktop and run this again; nothing was started or stopped.
exit=1
plan: lab share on the Compose project seatwave-balancer: four 20 s runs at 3,000 requests/s; nothing is disrupted
Docker is not running (or does not answer within 10 s).
Start Docker Desktop and run this again; nothing was started or stopped.
exit=1
```

The dry run of `stack.sh up` now works without Docker (version 1.0.0 stopped it with the Docker message), and `status`, which must ask the stack, stops at once. The folder `build/stack` did not exist afterwards: no audit line was written, because nothing was done.

5. Take the stack down and prove that nothing is left, with the same three listings that `down` runs (this needs Docker; the output is the re-measured run's):

```
scripts/stack.sh down --run --yes 2>&1 | tail -1
docker ps -a --filter label=com.docker.compose.project=seatwave-balancer --format '{{.Names}}' | wc -l | tr -d ' '
docker network ls --filter label=com.docker.compose.project=seatwave-balancer --format '{{.Name}}' | wc -l | tr -d ' '
docker volume ls --filter label=com.docker.compose.project=seatwave-balancer --format '{{.Name}}' | wc -l | tr -d ' '
```

Output:

```
stack is down: no container, network or volume of seatwave-balancer is left
0
0
0
```

## Mistakes people make

- **Forgetting `set -e` does not catch everything.** A failure inside an `if` condition or before `||` is ignored on purpose. The scripts use `|| true` only where a failure is expected.
- **`local x=$(cmd)` hides the exit code of `cmd`.** The scripts declare with `local x` and assign on the next line.
- **A pipe that ends with SIGPIPE under `pipefail`.** The password line uses `head -c` to read a fixed number of bytes, because `tr < /dev/urandom | head` would stop the script without a word (the first version of the script did).
- **Unquoted variables.** `"$SECRET_FILE"` and `"${compose[@]}"` are always quoted, so a path with spaces does not break.
- **Trusting the printed plan blindly.** A plan is only text, and it can drift from the code: version 1.0.0's plan for `up` left out `--quiet-pull`, and its dry run still needed Docker. Version 1.1.0 fixed both. Read the code, not only the plan.
- **Running a lab against a half-started stack.** `lab.sh` calls `stack_ready`: both balancers must see three instances fully `UP` in both checking backends before it disrupts anything. Counting `UP 1/3` as up would let a lab start against an instance that is failing its checks.
- **A failed step that skips the clean-up.** Under `set -e` a failing command ends the script at once, also in the middle of a lab. `disrupted_run` catches the disruption's failure with `||`, lets the run and the clean-up finish, and the lab exits non-zero at the end.
- **Runs that depend on the one before.** A run that leaves seats held changes the next run's answers. `give_seats_back` runs before and after every run.
- **A password file readable by everyone.** `umask 022` is chosen so that the containers' users can read it through a Linux bind mount; the file lives in an ignored folder, holds a throwaway password and is deleted by `down`, but on a shared machine other accounts could read it, and the comment in `stack.sh` says so.
- **Forgetting that `exit` inside a function ends the script.** `docker_must_answer` relies on that.

## Check yourself

1. What does `set -euo pipefail` do?
   *`-e` stops at the first failing command, `-u` makes an unset variable an error, and `-o pipefail` makes a pipe fail if any stage fails.*
2. Why does `down` check, after `docker compose down -v`, that nothing is left?
   *To prove the clean-up with three listings filtered by the project label: containers (including stopped), networks and volumes. Anything found fails the script.*
3. Why is `test` written with `trap 'down' EXIT`?
   *So `down` runs on every way out: success, a failing test, or a failing `up`.*
4. How does `pair` choose the balancer to kill?
   *It asks `address_holder`; `keepalived-1` means kill `lb1`, `keepalived-2` means kill `lb2`, and any other answer stops the lab before anything changes.*
5. Why does `lab.sh` restart a keepalived container after it starts a killed balancer?
   *The keepalived container is attached to the balancer's old network namespace, which the restart replaced; restarting keepalived attaches it to the new one.*

## Further reading

- Bash reference manual (arrays, `[[ ]]`, `trap`, `wait`, `case`, shell parameter expansion `${var#pattern}`): https://www.gnu.org/software/bash/manual/bash.html
- POSIX shell command language: https://pubs.opengroup.org/onlinepubs/9799919799/utilities/V3_chap02.html
- `docker compose up`, `down`, `exec`, `run`, `kill`, `stop`, `start`, `restart`, `ps`, `logs`: https://docs.docker.com/reference/cli/docker/compose/
- `docker ps`, `docker network ls`, `docker volume ls` and their `--filter` flag: https://docs.docker.com/reference/cli/docker/container/ls/
- The AWK language, POSIX: https://pubs.opengroup.org/onlinepubs/9799919799/utilities/awk.html
- `curl` options: https://curl.se/docs/manpage.html
