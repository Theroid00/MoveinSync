#!/usr/bin/env bash
# The brief's required test: roster halts right after the employee + outbox commit, before anything is
# published. After restart the relay delivers the event; booking cancels once and frees the seats.
set -uo pipefail
. "$(dirname "$0")/../lib.sh"

EMP=EMP-010
wait_until 240 "demo data seeded" seeded
HR=$(token hr@acme)
log "arming AFTER_INGEST_COMMIT (relay off) on roster"
ROSTER_FAULT=AFTER_INGEST_COMMIT ROSTER_RELAY=false $DC up -d --force-recreate --no-deps roster >/dev/null 2>&1
wait_healthy roster

EVENT_ID="kill-roster-$(date +%s)"
log "DEACTIVATE $EMP (eventId $EVENT_ID)"
RESPONSE=$(post_json "$ROSTER/api/v1/hr/events" "$HR" "$(deactivate_json "$EVENT_ID" "$(next_seq)" "$EMP")")
log "response status: ${RESPONSE%% *} (expected: no 200, the process dies after commit)"
[ "${RESPONSE%% *}" = 200 ] && { log "FAIL  got a 200 although the fault should have halted roster"; FAILURES=$((FAILURES + 1)); }
wait_exit roster 137

log "committed but unpublished: employee inactive=$(psql_roster "SELECT NOT active FROM employee WHERE tenant_id='acme' AND employee_id='$EMP'"), outbox=$(psql_roster "SELECT state FROM outbox_event WHERE tenant_id='acme' AND employee_id='$EMP' ORDER BY id DESC LIMIT 1")"
log "restarting roster normally"
restart_normal roster
wait_until 120 "outbox drained" outbox_drained

OUTBOX_EVENT=$(psql_roster "SELECT event_id FROM outbox_event WHERE tenant_id='acme' AND employee_id='$EMP' AND event_type='EmployeeDeactivated'")
assert_eq "employee $EMP inactive" "f" "$(psql_roster "SELECT active FROM employee WHERE tenant_id='acme' AND employee_id='$EMP'")"
assert_eq "outbox row delivered" "DELIVERED" "$(psql_roster "SELECT state FROM outbox_event WHERE event_id='$OUTBOX_EVENT'")"
assert_eq "future bookings cancelled" "3" "$(psql_booking "SELECT count(*) FROM booking WHERE tenant_id='acme' AND employee_id='$EMP' AND status='CANCELLED' AND cancelled_by_event='$OUTBOX_EVENT'")"
assert_eq "past booking untouched" "1" "$(psql_booking "SELECT count(*) FROM booking WHERE tenant_id='acme' AND employee_id='$EMP' AND status='RESERVED' AND scheduled_start <= now()")"
assert_eq "seats released" "3" "$(psql_booking "SELECT count(*) FROM seat_reservation s JOIN booking b USING (booking_id) WHERE b.tenant_id='acme' AND b.employee_id='$EMP' AND s.state='RELEASED'")"
assert_eq "processed_message rows for the event" "1" "$(psql_booking "SELECT count(*) FROM processed_message WHERE event_id='$OUTBOX_EVENT'")"
finish
