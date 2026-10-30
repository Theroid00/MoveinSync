#!/usr/bin/env bash
# 200 events at ~20/s while Postgres is frozen for 10 s. Callers see fast 503 DB_UNAVAILABLE and retry
# the same eventId; afterwards every event exists exactly once and no change lacks its outbox row.
set -uo pipefail
. "$(dirname "$0")/../lib.sh"

HR=$(token hr@acme)
RUN="dbp-$(date +%s)"
STATS="$STATE_DIR/$RUN.statuses"
: > "$STATS"
START_TS=$(psql_roster "SELECT now()")

send_all() {
  local i attempt seq response status
  for i in $(seq 1 200); do
    seq=$(next_seq)
    for attempt in $(seq 1 60); do
      response=$(post_json "$ROSTER/api/v1/hr/events" "$HR" "$(upsert_json "$RUN-$i" "$seq" "$(printf 'EMP-%03d' $(( (i % 100) + 1 )))" BLR-B EVENING)")
      status=${response%% *}
      echo "$status $(echo "$response" | jq -r '.errorCode // .outcome' 2>/dev/null)" >> "$STATS"
      [ "$status" = 200 ] && break
      sleep 0.5
    done
    sleep 0.05
  done
}

send_all &
SENDER=$!
sleep 2
log "pausing postgres for 10 s"
$DC pause postgres >/dev/null 2>&1
sleep 10
$DC unpause postgres >/dev/null 2>&1
log "postgres resumed; waiting for the sender"
wait "$SENDER"

log "status summary: $(sort "$STATS" | uniq -c | tr '\n' ';')"
assert_ge "503 DB_UNAVAILABLE responses observed" 1 "$(grep -c '^503 DB_UNAVAILABLE' "$STATS")"
assert_eq "every eventId has exactly one ingest_event row" "200" "$(psql_roster "SELECT count(DISTINCT event_id) FROM ingest_event WHERE event_id LIKE '$RUN-%'")"
assert_eq "no duplicate ingest_event rows" "200" "$(psql_roster "SELECT count(*) FROM ingest_event WHERE event_id LIKE '$RUN-%'")"
APPLIED=$(psql_roster "SELECT count(*) FROM ingest_event WHERE event_id LIKE '$RUN-%' AND outcome='APPLIED'")
assert_eq "all events applied" "200" "$APPLIED"
assert_eq "outbox rows = applied changes" "$APPLIED" "$(psql_roster "SELECT count(*) FROM outbox_event WHERE tenant_id='acme' AND created_at >= '$START_TS'")"
assert_eq "history rows = applied changes (no orphans)" "$APPLIED" "$(psql_roster "SELECT count(*) FROM employee_history WHERE tenant_id='acme' AND created_at >= '$START_TS'")"
finish
