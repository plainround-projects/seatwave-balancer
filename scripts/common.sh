#!/usr/bin/env bash
# Shared by scripts/stack.sh and scripts/lab.sh: the project's fixed name, the Docker check, the
# audit line, and read-only questions to the running stack. Sourced, never run on its own.

readonly PROJECT="seatwave-balancer"
compose=(docker compose -p "$PROJECT")
# "down" and "run" name the tools profile, so the harness's containers are included.
compose_all=(docker compose -p "$PROJECT" --profile tools)

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

audit() {
  mkdir -p build/stack
  echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $1" >> build/stack/audit.log
}

# The published statistics port of each balancer (compose.yaml).
stats_port() {
  case "$1" in
    lb1) echo 21004 ;;
    lb2) echo 21014 ;;
    *) echo "unknown balancer $1" >&2; return 1 ;;
  esac
}

# How many servers of a backend a balancer sees UP, read from its statistics page as CSV.
servers_up() {
  local port
  port="$(stats_port "$1")"
  { curl -s --max-time 2 "http://127.0.0.1:${port}/stats;csv" 2> /dev/null || true; } \
    | awk -F, -v backend="$2" '$1 == backend && $2 != "BACKEND" && $18 ~ /^UP/ { up++ } END { print up + 0 }'
}

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
