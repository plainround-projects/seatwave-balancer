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

### `scripts/common.sh`: the project and the two command arrays

`scripts/common.sh`, lines 1 to 8:

```bash
#!/usr/bin/env bash
# Shared by scripts/stack.sh and scripts/lab.sh: the project's fixed name, the Docker check, the
# audit line, and read-only questions to the running stack. Sourced, never run on its own.

readonly PROJECT="seatwave-balancer"
compose=(docker compose -p "$PROJECT")
# "down" and "run" name the tools profile, so the harness's containers are included.
compose_all=(docker compose -p "$PROJECT" --profile tools)
```
- Line 1 is the shebang, and lines 2 and 3 are comments: the file is shared by `stack.sh` and `lab.sh`, and holds the project's fixed name, the Docker check, the audit line and read-only questions to the running stack. "Sourced, never run on its own."
- `readonly PROJECT="seatwave-balancer"` sets a variable and forbids changing it later. Every Docker command in the scripts uses this name, so the scripts can only act on this project.
- `compose=(docker compose -p "$PROJECT")` stores a command in an **array**: the four words `docker`, `compose`, `-p` and `seatwave-balancer`. Later `"${compose[@]}" ps` expands to `docker compose -p seatwave-balancer ps`, with each word kept whole.
- The comment and `compose_all=(docker compose -p "$PROJECT" --profile tools)` make a second array with `--profile tools` added. The comment says why: `down` and `run` name the `tools` profile, so the harness's containers are included. The harness service in `compose.yaml` is hidden from a plain `up` by that profile (see `docker-compose.md`).

### `docker_must_answer`: a bounded check of Docker

`scripts/common.sh`, lines 10 to 28:

```bash
# Check before: Docker must answer, within a bound, or nothing is done.
docker_must_answer() {
  docker info --format '{{.ServerVersion}}' > /dev/null 2>&1 &
  local checker=$!
  local waited
  for (( waited = 0; waited < 10; waited++ )); do
    if ! kill -0 "$checker" 2> /dev/null; then
      if wait "$checker"; then
        return 0
      fi
      break
    fi
    sleep 1
  done
  kill "$checker" 2> /dev/null || true
  echo "Docker is not running (or does not answer within 10 s)." >&2
  echo "Start Docker Desktop and run this again; nothing was started or stopped." >&2
  exit 1
}
```
This function stops the whole script, with a clear message, when Docker does not answer. It must not hang, because `docker info` can wait for a long time when Docker Desktop is half started.

- `docker_must_answer() { ... }` defines a **function**: a named block of commands that runs when its name is written as a command.
- `docker info --format '{{.ServerVersion}}' > /dev/null 2>&1 &` asks the Docker engine for its version. `--format` picks one field. `> /dev/null 2>&1` throws away both output streams, because only the exit code matters. The final `&` starts the command in the **background**: the shell does not wait for it and goes on with the next line.
- `local checker=$!` stores `$!`, the process number of the last background command. `local` makes the variable belong to this function only. `local waited` declares the counter.
- `for (( waited = 0; waited < 10; waited++ )); do ... done` is a counting loop in the C style: start at 0, repeat while less than 10, add 1 each time. It runs at most 10 times.
- `if ! kill -0 "$checker" 2> /dev/null; then` asks whether the background job has ended. `kill -0 <number>` sends no signal; it only tests whether the process exists, and succeeds if it does. The `!` flips the result: the body runs when the process is gone. `2> /dev/null` hides the "no such process" message.
- `if wait "$checker"; then return 0; fi` collects the finished job. `wait <number>` waits for that process and succeeds if its exit code was 0. Then `return 0` leaves the function: Docker answered.
- `break` leaves the loop when the job ended with a failure. Docker answered with an error, so there is no point waiting.
- `sleep 1` waits one second before the next look.
- After the loop (a timeout or a failure): `kill "$checker" 2> /dev/null || true` stops the job if it is still running. `|| true` means "if that fails, carry on": without it `set -e` would stop the script on the failed `kill` of an already finished job.
- The two `echo ... >&2` lines print the message on the error stream: "Docker is not running (or does not answer within 10 s). Start Docker Desktop and run this again; nothing was started or stopped." Then `exit 1` ends the **whole script** (not only the function), with status 1.

### `audit`

`scripts/common.sh`, lines 30 to 33:

```bash
audit() {
  mkdir -p build/stack
  echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $1" >> build/stack/audit.log
}
```
- `mkdir -p build/stack` makes the folder if it is missing; `-p` means no error if it exists.
- `echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $1" >> build/stack/audit.log` appends one line: the time, then the function's first argument `$1`. `date -u` gives UTC; the format makes `2026-11-11T07:24:44Z`. `>>` appends, so earlier lines stay.

Every action that changes something calls `audit`. After my `up`, the log held `2026-11-11T07:24:44Z up: started seatwave-balancer-notes`.

### `stats_port`

`scripts/common.sh`, lines 35 to 42:

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

### `servers_up`: how many servers a balancer sees up

`scripts/common.sh`, lines 44 to 50:

```bash
# How many servers of a backend a balancer sees UP, read from its statistics page as CSV.
servers_up() {
  local port
  port="$(stats_port "$1")"
  { curl -s --max-time 2 "http://127.0.0.1:${port}/stats;csv" 2> /dev/null || true; } \
    | awk -F, -v backend="$2" '$1 == backend && $2 != "BACKEND" && $18 ~ /^UP/ { up++ } END { print up + 0 }'
}
```
- `local port` declares the variable on its own line, and `port="$(stats_port "$1")"` sets it on the next. The two steps are separate on purpose: `local port="$(...)"` would hide the exit code of `stats_port`, and `set -e` would never see a failure. `$( ... )` runs the command and puts its output in the place of the expression.
- `{ curl -s --max-time 2 "http://127.0.0.1:${port}/stats;csv" 2> /dev/null || true; }` is a group of commands in braces. `curl` fetches a URL; `-s` hides the progress meter, `--max-time 2` gives up after 2 seconds, and the URL is the balancer's statistics page with `;csv` added, which returns the table as comma-separated values. If curl fails, `|| true` keeps the group successful, so the pipe goes on and the function prints `0` instead of stopping the script.
- The `\` at the end of the line continues the command on the next line, and `|` pipes the table into `awk`.
- `awk` is a small language for tables. `-F,` says fields are separated by commas. `-v backend="$2"` passes the second argument of the function into the program as a variable called `backend`. The program in single quotes is `pattern { action }`: for each row whose first field (`$1`, the proxy name) equals `backend`, whose second field (`$2`, the server name) is not `BACKEND` (the summary row), and whose 18th field (`$18`, the `status` column, see `haproxy.md`) starts with `UP` (the regular expression `/^UP/`), do `up++` (add one to `up`). `END { print up + 0 }` runs after the last row and prints the count; adding 0 turns "never set" into `0`.

Because `UP 1/3` also starts with `UP`, a server that is still up but failing checks counts as up. A `DRAIN` or `DOWN` server does not.

### `address_holder`: who holds the floating address

`scripts/common.sh`, lines 52 to 67:

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

`scripts/common.sh`, lines 69 to 94:

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
                '$1 == backend && $2 == server && $18 ~ /^UP/ { found = 1 } END { exit !found }'; then
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
- `if ! curl -s --max-time 2 "..." | awk -F, -v backend="$backend" -v server="$server" '...'; then all="no"; fi` fetches the table and runs a small `awk` program: for a row whose proxy name is `backend`, whose server name is `server`, and whose status starts with `UP`, set `found = 1`. `END { exit !found }` exits with status 0 if `found` is set (`!found` is 0 when found is 1), and with status 1 if not. The `!` in front of the pipe flips it again, so `all` becomes `no` when the server is not up in that place.
- `if [[ "$all" == "yes" ]]; then return 0; fi` ends the function when every place saw the server up. `[[ ... ]]` is Bash's test command; `==` compares two strings.
- `sleep 1` and the loop try again.
- After 60 rounds: a message on the error stream and `return 1`.

### `scripts/stack.sh`: the header and the safety line

`scripts/stack.sh`, lines 1 to 20:

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
# Guardrails: the only target is the project seatwave-balancer; Docker must answer first or nothing
# is done; every change appends an audit line to build/stack/audit.log; "down" checks afterwards
# that no container, network or volume of the project is left; any failure exits non-zero.
set -euo pipefail
```
- Lines 2 to 19 are comments that document the script. They give the usage: one of four actions and the options `--run` and `--yes`. With no `--run` the script is a **dry run**: it prints what it would do and changes nothing. With `--run` it asks first, unless `--yes` skips the question for a reader who has already read the plan.
- The comment lists the four actions: `up` (make the database password, build the jars and the images, start the stack, wait until both balancers see all three instances up and one balancer holds the floating address), `down` (stop and remove every container, network and volume of the project), `test` (up, run the stack tests, then down, even if a test fails) and `status` (list the containers and both balancers' view; read-only).
- The last comment block lists the **guardrails**: the only target is the project `seatwave-balancer`; Docker must answer first or nothing is done; every change appends an audit line; `down` checks afterwards that nothing of the project is left; any failure exits non-zero.
- `set -euo pipefail` is three switches in one:
  - `-e`: stop the script when a command fails (returns a non-zero exit code). There are exceptions: a command used as the condition of an `if`, or on the left of `&&` or `||`, may fail without stopping the script.
  - `-u`: treat reading a variable that was never set as an error, instead of silently using an empty string. A typo in a name then stops the script.
  - `-o pipefail`: a pipe `a | b` normally has the exit code of `b` only. With this switch it fails if any stage failed.

### `scripts/stack.sh`: constants, arguments and the action

`scripts/stack.sh`, lines 22 to 44:

```bash
readonly SECRET_FILE="build/secrets/db.password"
readonly WAIT_SECONDS=90
readonly DOCKER_TIMEOUT_SECONDS=10
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
- Four `readonly` constants: the password file's path, how long `up` waits for the stack (90 seconds), a Docker timeout of 10 seconds, and the floating address, which `up` prints in its last message. The Docker timeout constant is never used: `docker_must_answer` in `common.sh` has its own 10 written in the loop.
- `usage() { echo "usage: $0 <up|down|test|status> [--run] [--yes]" >&2; exit 2; }` prints the usage line on the error stream and exits with status 2, the usual code for "wrong command line". `$0` is the name the script was started with.
- `[[ $# -ge 1 ]] || usage` checks that there is at least one argument. `$#` is the number of arguments and `-ge` means "greater than or equal". `||` runs `usage` when the test fails.
- `action="$1"` stores the first argument, and `shift` drops it so that `"$@"` holds only the options. `mode="dry-run"` and `confirmed="no"` are the defaults.
- `for option in "$@"; do ... done` goes over the remaining arguments. `"$@"` expands to each argument as one word. The `case` sets `mode="run"` for `--run`, `confirmed="yes"` for `--yes`, and calls `usage` for anything else (`*`).
- `case "$action" in up|down|test|status) ;; *) usage ;; esac` accepts only the four actions: the first branch does nothing (`;;` with no command), and anything else is a usage error. I checked: `scripts/stack.sh dance` and `scripts/stack.sh up --bogus` both printed the usage line and exited with status 2.

### `scripts/stack.sh`: where it runs, the Docker check and `status`

`scripts/stack.sh`, lines 46 to 58:

```bash
cd "$(dirname "$0")/.."
source scripts/common.sh

docker_must_answer

if [[ "$action" == "status" ]]; then
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
- `docker_must_answer` runs the Docker check. It comes **before** the dry-run exit below, so even a dry run stops with exit status 1 when Docker is not running. The header says a dry run changes nothing, and it does not change anything; but a reader who only wants to see the plan must start Docker first. `lab.sh` checks in the other order. I confirmed it with a fake `docker` that always fails: `stack.sh up` printed the Docker message and exited with 1, while `lab.sh share` printed its plan and exited 0.
- The `status` branch: `"${compose[@]}" ps --all` lists the project's containers (also stopped ones). The `for` loop prints for each balancer how many servers it sees up in the backend `seatwave`. `$(servers_up "$balancer" seatwave)` runs the function and puts its output in the string. `address_holder` says who has the floating address. `exit 0` ends the script, so `status` never reaches the code below, and it needs no `--run`.

### `scripts/stack.sh`: the plan, the dry run and the question

`scripts/stack.sh`, lines 60 to 73:

```bash
echo "plan: $action the Compose project $PROJECT"
case "$action" in
  up) echo "  password into $SECRET_FILE; ./gradlew -q stackFiles; ${compose[*]} up -d --build; wait until ready" ;;
  down) echo "  ${compose_all[*]} down -v --remove-orphans; check nothing of $PROJECT is left" ;;
  test) echo "  up, then ./gradlew stackTest, then down -v (always)" ;;
esac
if [[ "$mode" == "dry-run" ]]; then
  echo "dry run: nothing changed. Add --run to do it."
  exit 0
fi
if [[ "$confirmed" != "yes" ]]; then
  read -r -p "Go ahead with '$action' for $PROJECT? [y/N] " answer < /dev/tty
  [[ "$answer" == "y" ]] || { echo "not confirmed; nothing changed" >&2; exit 1; }
fi
```
- `echo "plan: $action the Compose project $PROJECT"` prints what will happen.
- The `case "$action"` prints a second line for each action. `${compose[*]}` joins the array into one string with spaces, so `docker compose -p seatwave-balancer` appears in the text. The plan for `up` says `${compose[*]} up -d --build; wait until ready`. The real command, further down, is `up -d --build --quiet-pull`: the plan leaves out the last option. It is harmless, but the printed plan is not exactly what runs.
- `if [[ "$mode" == "dry-run" ]]; then ... exit 0; fi` ends the script with success after printing "dry run: nothing changed. Add --run to do it."
- `if [[ "$confirmed" != "yes" ]]; then ... fi` asks, unless `--yes` was given. `read -r -p "..." answer < /dev/tty` reads a line from the **terminal** into `answer`. `-r` keeps backslashes as they are, `-p` shows the prompt. `< /dev/tty` makes it read from the keyboard even if the script's input is a pipe. Without a terminal, the read fails: running `scripts/stack.sh down --run` with no terminal printed `/dev/tty: Device not configured` and exited with 1, having changed nothing. `[[ "$answer" == "y" ]] || { echo "not confirmed; nothing changed" >&2; exit 1; }` ends the script unless the answer is exactly `y`. The braces group two commands after `||`.

### `scripts/stack.sh`: `up`

`scripts/stack.sh`, lines 75 to 99:

```bash
up() {
  mkdir -p build/secrets
  if [[ ! -s "$SECRET_FILE" ]]; then
    # A throwaway lab password, new for every fresh stack, never committed (.gitignore) and never
    # copied into an image (.dockerignore). Readable by the containers' own users.
    # head ends the pipe itself: "tr < /dev/urandom | head" would end with SIGPIPE, and pipefail
    # would stop the script there without a word (the first version of this script did).
    (umask 022; head -c 24 /dev/urandom | base64 | tr -d '/+=\n' > "$SECRET_FILE")
  fi
  ./gradlew -q stackFiles
  "${compose[@]}" up -d --build --quiet-pull
  audit "up: started $PROJECT"
  for (( waited = 0; waited < WAIT_SECONDS; waited++ )); do
    if [[ "$(servers_up lb1 seatwave)" == "3" && "$(servers_up lb2 seatwave)" == "3" \
          && "$(servers_up lb1 seatwave_active_only)" == "3" && "$(servers_up lb2 seatwave_active_only)" == "3" \
          && "$(address_holder)" != "none" ]]; then
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
- The three comment lines explain the password and a bug. The password is a throwaway lab password, new for every fresh stack, never committed (`.gitignore`) and never copied into an image (`.dockerignore`), and "readable by the containers' own users". The last lines say why `head` is used: `tr < /dev/urandom | head` would end with SIGPIPE (the signal a program gets when the reader of its pipe has gone) and `pipefail` would stop the script without a word; "the first version of this script did".
- `(umask 022; head -c 24 /dev/urandom | base64 | tr -d '/+=\n' > "$SECRET_FILE")` runs in a **subshell** (the parentheses), so the `umask` change does not leak out. `umask 022` makes the new file readable by everybody (mode 644), which lets the container users read it. `head -c 24 /dev/urandom` takes 24 random bytes from the system's random source and then ends the pipe itself. `base64` turns them into 32 letters and digits (and `/`, `+`). `tr -d '/+=\n'` deletes the characters `/`, `+`, `=` and the newline, which can trouble a URL or a settings file. The result is written to the file. I checked: the file had 32 characters and mode `-rw-r--r--`. Readable by everybody on the machine also means by other accounts on it. That is acceptable for a throwaway lab password in an ignored folder, and the comment says why.
- `./gradlew -q stackFiles` collects the jars (see `gradle.md`); `-q` means quiet.
- `"${compose[@]}" up -d --build --quiet-pull` builds the images and starts all services except the `tools` profile. `-d` returns once they are started (detached), `--build` builds images first, and `--quiet-pull` hides the progress bars of image downloads.
- `audit "up: started $PROJECT"` writes the audit line.
- The wait loop runs up to `WAIT_SECONDS` (90) times. `$(( ... ))` is arithmetic, here only the loop's counter. Inside, one long test with `[[ ... && ... ]]`: both balancers see 3 servers up in `seatwave`, both see 3 up in `seatwave_active_only`, and `address_holder` is not `none`. The backslashes continue the line. When all hold, it prints for example `stack is up after 5 s: 3 of 3 instances up on lb1 and lb2; 10.210.10.100 on keepalived-2` and `return 0`. Otherwise `sleep 1`.
- After the loop: the message "the stack was not ready within 90 s" on the error stream, the last 20 log lines of all services (`logs --tail 20 >&2`), and `return 1`, which `set -e` turns into the end of the script.

### `scripts/stack.sh`: `down`

`scripts/stack.sh`, lines 101 to 116:

```bash
down() {
  "${compose_all[@]}" down -v --remove-orphans
  audit "down: removed $PROJECT"
  # Check after: nothing of this project may be left.
  local left
  left="$(docker ps -aq --filter "label=com.docker.compose.project=$PROJECT")"
  left+="$(docker network ls -q --filter "label=com.docker.compose.project=$PROJECT")"
  left+="$(docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT")"
  if [[ -n "$left" ]]; then
    echo "something of $PROJECT is still there: $left" >&2
    return 1
  fi
  # The password belongs to the database that was just removed; the next stack makes a new one.
  rm -f "$SECRET_FILE"
  echo "stack is down: no container, network or volume of $PROJECT is left"
}
```
- `"${compose_all[@]}" down -v --remove-orphans` stops and removes the containers, the network and (with `-v`) the volumes, including the harness's container (the `--profile tools`). `--remove-orphans` also removes containers of this project that the file no longer lists.
- `audit` records it.
- The comment says "Check after: nothing of this project may be left." `local left` is a string. `left="$(docker ps -aq --filter "label=com.docker.compose.project=$PROJECT")"` lists the ids of all containers (`-a`: stopped ones too; `-q`: only ids) that carry the project label. The next two lines append (`+=`) the ids of networks and volumes with the same label. The label is set by Compose on everything it creates.
- `if [[ -n "$left" ]]; then ... return 1; fi`: `-n` tests "not empty". If anything is left, it prints "something of seatwave-balancer is still there:" and the ids, on the error stream, and fails. The ids are glued together without a separator (the list of containers has no newline at its end and the next list starts right after), so two ids can print as one run of characters. The script's logic is right; only that error message is hard to read.
- `rm -f "$SECRET_FILE"` deletes the password, because it belongs to the database that was just removed; `-f` means no error if it is already gone. The last line prints "stack is down: no container, network or volume of seatwave-balancer is left".

### `scripts/stack.sh`: the dispatch

`scripts/stack.sh`, lines 118 to 127:

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

`scripts/lab.sh`, lines 1 to 22:

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
#   default a dry run: print the plan, change nothing; --run does it after a question; --yes skips it
#
# Guardrails: the only target is the project seatwave-balancer and, for each lab, one named
# container; Docker must answer and the stack must be ready first; each disruption appends an
# audit line to build/stack/audit.log; the stack is checked ready again afterwards; any failure
# exits non-zero. Every report is written to build/runs/<lab>.txt.
set -euo pipefail
```
- The header lists the eight labs and what each does. In short: `share` runs four load runs with four algorithms and disrupts nothing; `kill-active` and `kill-production` kill `seatwave-2` with SIGKILL (the signal a program cannot catch, like a power cut) 10 seconds into a run, behind the active-only backend and behind PRODUCTION; `stop-active` and `stop-production` send it SIGTERM ("please finish and exit") through `docker compose stop` instead; `deploy-production` and `deploy-active` run the rolling deploy during a run; `pair` kills the balancer that holds the floating address, 12 seconds into a run against that address. The default is a dry run, `--run` does it after a question, `--yes` skips the question.
- The guardrails: the only target is the Compose project, and for each lab one named container; Docker must answer and the stack must be ready first; each disruption appends an audit line; the stack is checked ready again afterwards; any failure exits non-zero. Every report is written to `build/runs/<lab>.txt`.
- `set -euo pipefail` as in `stack.sh`.

### `scripts/lab.sh`: usage and arguments

`scripts/lab.sh`, lines 24 to 40:

```bash
usage() {
  echo "usage: $0 <share|kill-active|kill-production|stop-active|stop-production|deploy-production|deploy-active|pair> [--run] [--yes]" >&2
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
- `usage` prints the list of lab names and exits with status 2.
- `[[ $# -ge 1 ]] || usage` requires a lab name. `lab="$1"` and `shift` take it, and `mode` and `confirmed` start as in `stack.sh`.
- The `for` and `case` read `--run` and `--yes` and reject anything else. I checked: `scripts/lab.sh nothing` and `scripts/lab.sh share --nope` printed the usage line and exited with 2.

### `scripts/lab.sh`: constants and the plan

`scripts/lab.sh`, lines 42 to 55:

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
  *) usage ;;
esac
```
- The comment says the numbers count seconds into the run, after the warm-up starts. `readonly WARMUP_SECONDS=5` is the harness's warm-up, `DISRUPT_AFTER_SECONDS=10` the time of a kill or a stop, `PAIR_DISRUPT_AFTER_SECONDS=12` the time of the balancer kill (longer, so the harness has a full steady period first), and `DEPLOY_AFTER_SECONDS=8` the time the deploy starts.
- The `case "$lab"` sets the text `plan` for each lab. `kill-active|kill-production)` has two names in one branch: `|` means "or". `${DISRUPT_AFTER_SECONDS}` puts the constant into the text. The last branch `*) usage ;;` rejects an unknown lab before anything else happens.

### `scripts/lab.sh`: the checks before the lab

`scripts/lab.sh`, lines 57 to 74:

```bash
cd "$(dirname "$0")/.."
source scripts/common.sh

echo "plan: lab $lab on the Compose project $PROJECT: $plan"
if [[ "$mode" == "dry-run" ]]; then
  echo "dry run: nothing changed. Add --run to do it."
  exit 0
fi
docker_must_answer
if [[ "$(servers_up lb1 seatwave)" != "3" || "$(servers_up lb2 seatwave)" != "3" ]]; then
  echo "the stack is not ready (scripts/stack.sh up --run first); nothing changed" >&2
  exit 1
fi
if [[ "$confirmed" != "yes" ]]; then
  read -r -p "Go ahead with lab '$lab'? [y/N] " answer < /dev/tty
  [[ "$answer" == "y" ]] || { echo "not confirmed; nothing changed" >&2; exit 1; }
fi
mkdir -p build/runs
```
- `cd "$(dirname "$0")/.."` and `source scripts/common.sh` are the same as in `stack.sh`.
- `echo "plan: lab $lab on the Compose project $PROJECT: $plan"` prints the plan, and the dry run ends here with success. The Docker check is below it, so a dry run of a lab needs no Docker.
- `docker_must_answer` is the first check before a real run.
- `if [[ "$(servers_up lb1 seatwave)" != "3" || "$(servers_up lb2 seatwave)" != "3" ]]; then ... exit 1; fi` is the readiness check: both balancers must see all three instances up in `seatwave`. `||` inside `[[ ]]` means "or". If not, it prints "the stack is not ready (scripts/stack.sh up --run first); nothing changed" on the error stream and exits. It does not look at `seatwave_active_only`, which `stack.sh up` does wait for; an active-only lab started right after an instance restart could begin while that backend still counts the instance as down.
- The confirmation question is the same as in `stack.sh`.
- `mkdir -p build/runs` makes the folder for the reports.

### `scripts/lab.sh`: the harness and the disruption

`scripts/lab.sh`, lines 76 to 93:

```bash
# Runs the harness with one settings file and writes its report.
harness() {
  "${compose_all[@]}" run --rm -T harness "/app/config/$1.properties" 2>&1 \
    | grep -v -E '^ ?(Container|Network|Volume) ' > "build/runs/$2.txt"
}

# Starts the harness in the background, waits, runs one command, and waits for the report.
disrupted_run() {
  local settings="$1" seconds="$2"
  shift 2
  harness "$settings" "$lab" &
  local harness_job=$!
  sleep "$(( WARMUP_SECONDS + seconds ))"
  audit "lab $lab: $*"
  echo "+${seconds} s: $*"
  "$@"
  wait "$harness_job"
}
```
- `harness() { ... }` runs the load generator. `"${compose_all[@]}" run --rm -T harness "/app/config/$1.properties"` starts the `harness` service once (`run`), removes its container afterwards (`--rm`), without a terminal (`-T`), and gives it the settings file named by the first argument as its argument. `2>&1` joins the error stream to the output. `| grep -v -E '^ ?(Container|Network|Volume) '` throws away the lines that Compose prints about starting and stopping its objects: `-v` inverts the match, `-E` allows the pattern `^ ?(Container|Network|Volume) ` (a line that starts with an optional space and one of three words). `> "build/runs/$2.txt"` writes what is left to the report file named by the second argument.
- `disrupted_run() { ... }` does one lab with one disruption. `local settings="$1" seconds="$2"` names the first two arguments and `shift 2` drops them, so `"$@"` is now the disruptive command itself.
- `harness "$settings" "$lab" &` starts the harness in the background, and `local harness_job=$!` remembers its process number.
- `sleep "$(( WARMUP_SECONDS + seconds ))"` waits the warm-up (5 s) plus the lab's offset (10 s for a kill), then:
- `audit "lab $lab: $*"` writes the audit line (`$*` is all the remaining arguments as one string), `echo "+${seconds} s: $*"` prints what is about to happen, and `"$@"` **runs the disruptive command**: for example `docker compose -p seatwave-balancer kill -s KILL seatwave-2`.
- `wait "$harness_job"` waits for the harness to finish. If the harness failed, its status stops the script (`set -e`).

The "+10 s" in the printed line is the script's own clock, counted from the moment the harness was started. The harness measures from the end of its warm-up and takes a little time to start up, so in the harness's report the kill appears at about +9.75 s, not exactly +10.

### `scripts/lab.sh`: which lab does what

`scripts/lab.sh`, lines 95 to 122:

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
  kill-active) disrupted_run kill-active-only "$DISRUPT_AFTER_SECONDS" "${compose[@]}" kill -s KILL seatwave-2 ;;
  kill-production) disrupted_run kill-production "$DISRUPT_AFTER_SECONDS" "${compose[@]}" kill -s KILL seatwave-2 ;;
  stop-active) disrupted_run stop-active-only "$DISRUPT_AFTER_SECONDS" "${compose[@]}" stop seatwave-2 ;;
  stop-production) disrupted_run stop-production "$DISRUPT_AFTER_SECONDS" "${compose[@]}" stop seatwave-2 ;;
  deploy-production) disrupted_run deploy-production "$DEPLOY_AFTER_SECONDS" ./gradlew -q deploy --args='config/deploy.properties --run --yes' ;;
  deploy-active) disrupted_run deploy-active-only "$DEPLOY_AFTER_SECONDS" ./gradlew -q deploy --args='config/deploy.properties --run --yes' ;;
  pair)
    holder="$(address_holder)"
    case "$holder" in
      keepalived-1) victim="lb1" ;;
      keepalived-2) victim="lb2" ;;
      *) echo "the floating address is on '$holder', not on exactly one balancer; nothing changed" >&2; exit 1 ;;
    esac
    echo "floating address before: $holder, so the balancer to kill is $victim"
    disrupted_run pair "$PAIR_DISRUPT_AFTER_SECONDS" "${compose[@]}" kill -s KILL "$victim"
    echo "floating address after the kill: $(address_holder)"
    ;;
esac
cat "build/runs/$lab.txt"
```
- `share)` runs a loop over four algorithm names: for each it calls `harness share-<name> share-<name>` with the algorithm name filled in (`roundrobin`, `leastconn`, `weighted`, `source`), which writes `build/runs/share-<name>.txt`, then prints a heading and the report with `cat`. Nothing is disrupted. `exit 0` ends the script, so the code below runs only for the other labs.
- `kill-active)` and `kill-production)` call `disrupted_run` with the harness settings `kill-active-only` and `kill-production` (files in `config/`), the offset, and the command `"${compose[@]}" kill -s KILL seatwave-2`. `kill -s KILL` sends SIGKILL to the container's process, with no warning. The two labs differ only in the settings file, which names the door (port 8082 or 8080).
- `stop-active)` and `stop-production)` do the same with `"${compose[@]}" stop seatwave-2`, which sends SIGTERM and waits for the container to exit (up to the 30 s `stop_grace_period`). SeatWave handles SIGTERM by draining.
- `deploy-production)` and `deploy-active)` use `./gradlew -q deploy --args='config/deploy.properties --run --yes'` as the disruptive command: the Gradle task that runs the deploy tool (`java-deploy-walkthrough.md`). The single quotes keep `--args=...` and what follows as one word for Gradle.
- `pair)` first decides which balancer to kill. `holder="$(address_holder)"` asks who has the floating address. The inner `case` maps `keepalived-1` to `victim="lb1"` and `keepalived-2` to `victim="lb2"`. Anything else (`none` or `both`) is refused: "the floating address is on '...', not on exactly one balancer; nothing changed". Then it prints which balancer it will kill, runs `disrupted_run` with `kill -s KILL "$victim"`, and prints where the address went.
- `esac` ends the `case`; the next line, `cat "build/runs/$lab.txt"`, prints the report of the lab (the harness's output with the Compose progress lines removed).

### `scripts/lab.sh`: putting the stack back

`scripts/lab.sh`, lines 124 to 140:

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
```
- `kill-*|stop-*)` is a pattern: any lab name that starts with `kill-` or `stop-`. `"${compose[@]}" start seatwave-2` starts the stopped instance, `wait_until_up seatwave-2` waits until both balancers see it up in both checking backends (up to 60 seconds), `audit` records it, and the script prints "seatwave-2 is up again on both balancers".
- `pair)` starts the killed balancer and restarts its keepalived. The comment says why: the keepalived lives in its balancer's network namespace, which the restart replaced (see `docker-compose.md`). `${victim#lb}` removes the leading `lb` from `lb2`, leaving `2`, so `"keepalived-${victim#lb}"` is `keepalived-2`. After `sleep 6` it prints `floating address after lb2 came back: keepalived-1`. The README shows exactly that: the address stayed where it had moved (`nopreempt`).
- The `deploy-*` labs and `share` need no cleanup: the deploy ends with every instance up, and `share` disrupts nothing.

## Try it yourself

I ran these on 11 November 2026 in a copy of the repository whose project name is `seatwave-balancer-notes` (the scripts in the repository say `seatwave-balancer`). Run them from the repository folder. Timings differ on every run.

1. See the plans without changing anything (dry runs, with Docker running):

```
scripts/stack.sh up
scripts/stack.sh down
scripts/lab.sh pair
```

Output:

```
plan: up the Compose project seatwave-balancer-notes
  password into build/secrets/db.password; ./gradlew -q stackFiles; docker compose -p seatwave-balancer-notes up -d --build; wait until ready
dry run: nothing changed. Add --run to do it.
plan: down the Compose project seatwave-balancer-notes
  docker compose -p seatwave-balancer-notes --profile tools down -v --remove-orphans; check nothing of seatwave-balancer-notes is left
dry run: nothing changed. Add --run to do it.
plan: lab pair on the Compose project seatwave-balancer-notes: docker kill -s KILL on the balancer holding 10.210.10.100 at +12 s; then start it and restart its keepalived
dry run: nothing changed. Add --run to do it.
```

2. The refusals. A wrong action, a wrong option and a missing argument exit with status 2 and print the usage line:

```
scripts/stack.sh dance; echo "exit=$?"
scripts/lab.sh nothing; echo "exit=$?"
scripts/lab.sh; echo "exit=$?"
```

Output:

```
usage: scripts/stack.sh <up|down|test|status> [--run] [--yes]
exit=2
usage: scripts/lab.sh <share|kill-active|kill-production|stop-active|stop-production|deploy-production|deploy-active|pair> [--run] [--yes]
exit=2
usage: scripts/lab.sh <share|kill-active|kill-production|stop-active|stop-production|deploy-production|deploy-active|pair> [--run] [--yes]
exit=2
```

3. Start the stack and read its last line, then the read-only status:

```
scripts/stack.sh up --run --yes 2>&1 | tail -1
scripts/stack.sh status | tail -3
```

Output (the status lines use the same functions as the wait loop; the holder of the address varies from start to start):

```
stack is up after 5 s: 3 of 3 instances up on lb1 and lb2; 10.210.10.100 on keepalived-1
lb1: 3 of 3 instances up in backend seatwave
lb2: 3 of 3 instances up in backend seatwave
floating address on: keepalived-1
```

4. The Docker check. A fake `docker` that always fails shows what the scripts do when Docker is not running (the fake is a three-line file `fakebin/docker` with `#!/bin/sh` and `exit 1`, put first on the path for one command):

```
PATH="$PWD/fakebin:$PATH" scripts/stack.sh up; echo "exit=$?"
PATH="$PWD/fakebin:$PATH" scripts/lab.sh share; echo "exit=$?"
PATH="$PWD/fakebin:$PATH" scripts/lab.sh share --run --yes; echo "exit=$?"
```

Output:

```
Docker is not running (or does not answer within 10 s).
Start Docker Desktop and run this again; nothing was started or stopped.
exit=1
plan: lab share on the Compose project seatwave-balancer-notes: four 20 s runs at 3,000 requests/s; nothing is disrupted
dry run: nothing changed. Add --run to do it.
exit=0
plan: lab share on the Compose project seatwave-balancer-notes: four 20 s runs at 3,000 requests/s; nothing is disrupted
Docker is not running (or does not answer within 10 s).
Start Docker Desktop and run this again; nothing was started or stopped.
exit=1
```

5. Take the stack down and prove that nothing is left, with the same three listings that `down` runs:

```
scripts/stack.sh down --run --yes 2>&1 | tail -1
docker ps -a --filter label=com.docker.compose.project=seatwave-balancer-notes --format '{{.Names}}' | wc -l | tr -d ' '
docker network ls --filter label=com.docker.compose.project=seatwave-balancer-notes --format '{{.Name}}' | wc -l | tr -d ' '
docker volume ls --filter label=com.docker.compose.project=seatwave-balancer-notes --format '{{.Name}}' | wc -l | tr -d ' '
```

Output:

```
stack is down: no container, network or volume of seatwave-balancer-notes is left
0
0
0
```

## Mistakes people make

- **Forgetting `set -e` does not catch everything.** A failure inside an `if` condition or before `||` is ignored on purpose. The scripts use `|| true` only where a failure is expected.
- **`local x=$(cmd)` hides the exit code of `cmd`.** The scripts declare with `local x` and assign on the next line.
- **A pipe that ends with SIGPIPE under `pipefail`.** The password line uses `head -c` to read a fixed number of bytes, because `tr < /dev/urandom | head` would stop the script without a word (the first version of the script did).
- **Unquoted variables.** `"$SECRET_FILE"` and `"${compose[@]}"` are always quoted, so a path with spaces does not break.
- **Trusting the printed plan blindly.** The plan for `up` leaves out `--quiet-pull`, and a dry run of `stack.sh` still needs Docker. Read the code, not only the plan.
- **Running a lab against a half-started stack.** `lab.sh` checks that both balancers see three instances up before it disrupts anything.
- **A password file readable by everyone.** `umask 022` is chosen so that the containers' users can read it; the file lives in an ignored folder and holds a throwaway password, but on a shared machine other accounts could read it.
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
