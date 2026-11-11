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
