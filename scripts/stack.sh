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
