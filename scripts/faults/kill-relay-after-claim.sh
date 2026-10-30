#!/usr/bin/env bash
# roster halts right after the relay claimed a batch (leases held, nothing sent). After restart the
# leases expire (30 s), the rows are reclaimed and every event is delivered: none lost.
set -uo pipefail
. "$(dirname "$0")/../lib.sh"

HR=$(token hr@acme)
log "pausing the relay so 50 events accumulate"
ROSTER_RELAY=false $DC up -d --force-recreate --no-deps roster >/dev/null 2>&1
wait_healthy roster
HR=$(token hr@acme)
RUN="relay-$(date +%s)"
for i in $(seq 21 70); do
  RESPONSE=$(post_json "$ROSTER/api/v1/hr/events" "$HR" "$(upsert_json "$RUN-$i" "$(next_seq)" "$(printf 'EMP-%03d' "$i")" GGN-A NIGHT)")
  [ "${RESPONSE%% *}" = 200 ] || die "event $i not accepted: $RESPONSE"
done
assert_eq "pending events" "50" "$(undelivered)"

log "arming RELAY_AFTER_CLAIM with the relay enabled"
ROSTER_FAULT=RELAY_AFTER_CLAIM ROSTER_RELAY=true $DC up -d --force-recreate --no-deps roster >/dev/null 2>&1
wait_exit roster 137 120
assert_eq "rows claimed under a lease when the relay died" "50" "$(psql_roster "SELECT count(*) FROM outbox_event WHERE state='CLAIMED'")"

log "restarting roster normally; leases expire after 30 s"
restart_normal roster
wait_until 180 "all events delivered" outbox_drained

assert_eq "delivered events from this run" "50" "$(psql_roster "SELECT count(*) FROM outbox_event o JOIN employee_history h ON h.tenant_id = o.tenant_id AND h.employee_id = o.employee_id AND h.aggregate_revision = o.aggregate_revision WHERE o.state='DELIVERED' AND h.origin='API' AND o.employee_id BETWEEN 'EMP-021' AND 'EMP-070' AND o.event_type='EmployeeUpdated'")"
assert_ge "attempts include the lost claim" 2 "$(psql_roster "SELECT min(attempts) FROM outbox_event WHERE employee_id BETWEEN 'EMP-021' AND 'EMP-070' AND event_type='EmployeeUpdated'")"
ROSTER_IDS=$(psql_roster "SELECT string_agg(event_id::text, ',' ORDER BY event_id) FROM outbox_event WHERE employee_id BETWEEN 'EMP-021' AND 'EMP-070' AND event_type='EmployeeUpdated'")
assert_eq "booking processed every event exactly once" "50" "$(psql_booking "SELECT count(*) FROM processed_message WHERE event_id::text = ANY(string_to_array('$ROSTER_IDS', ','))")"
finish
