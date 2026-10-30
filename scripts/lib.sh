#!/usr/bin/env bash
# Shared helpers for demo, fault and benchmark scripts. Portable: bash 3.2+ (macOS) and Linux; needs
# docker compose v2, curl and jq. No GNU-only flags.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DC="docker compose -f $ROOT/docker-compose.yml -f $ROOT/docker-compose.faults.yml"
ROSTER="${ROSTER_URL:-http://localhost:8080}"
BOOKING="${BOOKING_URL:-http://localhost:8081}"
STATE_DIR="${STATE_DIR:-${TMPDIR:-/tmp}/roster-sync-scripts}"
mkdir -p "$STATE_DIR"
FAILURES=0

log() { printf '%s  %s\n' "$(date -u +%H:%M:%S)" "$*"; }

die() { log "FATAL: $*"; exit 2; }

# token <username> → access token for a demo user (password demo123)
token() {
  curl -sS -X POST "$ROSTER/api/v1/auth/token" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"demo123\"}" | jq -r .accessToken
}

psql_roster() { $DC exec -T postgres psql -U postgres -d roster -tAq -c "$1"; }
psql_booking() { $DC exec -T postgres psql -U postgres -d booking -tAq -c "$1"; }

# post_json <url> <token> <json> → prints "<http status> <body>" (status 000 when no response)
post_json() {
  local body status
  body="$STATE_DIR/body.$$"
  status=$(curl -sS -m 15 -o "$body" -w '%{http_code}' -X POST "$1" -H "Authorization: Bearer $2" \
    -H 'Content-Type: application/json' -d "$3" 2>/dev/null) || true
  [ -z "$status" ] && status=000
  printf '%s %s\n' "$status" "$(cat "$body" 2>/dev/null)"
  rm -f "$body"
}

# next_seq → a sourceSeq above every seeded one and monotonic across runs and subshells
next_seq() {
  local counter_file="$STATE_DIR/seq_counter" counter
  counter=$(( $(cat "$counter_file" 2>/dev/null || echo 0) + 1 ))
  [ "$counter" -ge 999999 ] && counter=1
  echo "$counter" > "$counter_file"
  echo $(( $(date +%s) * 1000000 + counter ))
}

now_iso() { date -u +%Y-%m-%dT%H:%M:%SZ; }

container() { $DC ps -a -q "$1" | head -1; }

# wait_healthy <service> [timeout s]
wait_healthy() {
  local svc=$1 timeout=${2:-180} start status
  start=$(date +%s)
  while :; do
    status=$(docker inspect -f '{{.State.Health.Status}}' "$(container "$svc")" 2>/dev/null)
    [ "$status" = healthy ] && { log "$svc healthy"; return 0; }
    [ $(( $(date +%s) - start )) -ge "$timeout" ] && die "$svc not healthy after ${timeout}s (status=$status)"
    sleep 2
  done
}

# wait_exit <service> <expected code> [timeout s]
wait_exit() {
  local svc=$1 expected=$2 timeout=${3:-60} start state
  start=$(date +%s)
  while :; do
    state=$(docker inspect -f '{{.State.Status}} {{.State.ExitCode}}' "$(container "$svc")" 2>/dev/null)
    case "$state" in
      "exited $expected") log "$svc exited with code $expected"; return 0 ;;
      exited*) die "$svc exited unexpectedly: $state" ;;
    esac
    [ $(( $(date +%s) - start )) -ge "$timeout" ] && die "$svc did not exit within ${timeout}s (state=$state)"
    sleep 1
  done
}

# wait_until <timeout s> <description> <command...> : polls every 2 s until the command succeeds
wait_until() {
  local timeout=$1 desc=$2 start
  shift 2
  start=$(date +%s)
  until "$@" >/dev/null 2>&1; do
    [ $(( $(date +%s) - start )) -ge "$timeout" ] && die "timed out after ${timeout}s waiting for: $desc"
    sleep 2
  done
  log "ok: $desc ($(( $(date +%s) - start ))s)"
}

assert_eq() {
  if [ "$2" = "$3" ]; then
    log "PASS  $1 (= $3)"
  else
    log "FAIL  $1: expected '$2', got '$3'"
    FAILURES=$((FAILURES + 1))
  fi
}

assert_ge() {
  if [ "$3" -ge "$2" ] 2>/dev/null; then
    log "PASS  $1 (= $3, >= $2)"
  else
    log "FAIL  $1: expected >= $2, got '$3'"
    FAILURES=$((FAILURES + 1))
  fi
}

finish() {
  if [ "$FAILURES" -eq 0 ]; then
    log "RESULT: PASS"
    exit 0
  fi
  log "RESULT: FAIL ($FAILURES assertion(s))"
  exit 1
}

# Normal (no fault) roster/booking containers, recreated.
restart_normal() {
  ROSTER_FAULT= ROSTER_RELAY=true BOOKING_FAULT= $DC up -d --force-recreate --no-deps "$@" >/dev/null 2>&1
  for svc in "$@"; do wait_healthy "$svc"; done
}

undelivered() { psql_roster "SELECT count(*) FROM outbox_event WHERE state <> 'DELIVERED'"; }
outbox_drained() { [ "$(undelivered)" = 0 ]; }
seeded() {
  [ "$(psql_booking "SELECT count(*) FROM booking WHERE scheduled_start > now()" 2>/dev/null)" = 300 ] && outbox_drained
}

# Fresh stack: down -v, build, up, wait until both services are healthy and the demo data is seeded.
reset_stack() {
  log "resetting stack (down -v, up --build)"
  $DC down -v -t 5 >/dev/null 2>&1
  ROSTER_FAULT= ROSTER_RELAY=true BOOKING_FAULT= $DC up -d --build >/dev/null 2>&1 || die "compose up failed"
  wait_healthy roster
  wait_healthy booking
  wait_until 240 "demo data seeded (300 future bookings, outbox drained)" seeded
}

# deactivate_json <eventId> <seq> <employeeId>
deactivate_json() {
  printf '{"eventId":"%s","sourceSeq":%s,"eventType":"DEACTIVATE","employeeId":"%s","effectiveAt":"%s"}' \
    "$1" "$2" "$3" "$(now_iso)"
}

# upsert_json <eventId> <seq> <employeeId> <office> <shift>
upsert_json() {
  printf '{"eventId":"%s","sourceSeq":%s,"eventType":"UPSERT","employeeId":"%s","effectiveAt":"%s","hr":{"name":"Employee %s","email":"%s@acme.example","phone":"+919000000999","managerId":null,"officeCode":"%s","shiftCode":"%s"}}' \
    "$1" "$2" "$3" "$(now_iso)" "$3" "$(echo "$3" | tr 'A-Z' 'a-z')" "$4" "$5"
}
