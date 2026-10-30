#!/usr/bin/env bash
# booking halts after committing the cancellation, before answering the relay. The relay retries the
# same event; processed_message absorbs it: one cancellation per booking, seats released once.
set -uo pipefail
. "$(dirname "$0")/../lib.sh"

EMP=EMP-011
HR=$(token hr@acme)
log "arming AFTER_CONSUMER_COMMIT on booking"
BOOKING_FAULT=AFTER_CONSUMER_COMMIT $DC up -d --force-recreate --no-deps booking >/dev/null 2>&1
wait_healthy booking

EVENT_ID="kill-consumer-$(date +%s)"
log "DEACTIVATE $EMP (eventId $EVENT_ID)"
RESPONSE=$(post_json "$ROSTER/api/v1/hr/events" "$HR" "$(deactivate_json "$EVENT_ID" "$(next_seq)" "$EMP")")
assert_eq "roster accepted the event" "200" "${RESPONSE%% *}"
wait_exit booking 137

OUTBOX_EVENT=$(psql_roster "SELECT event_id FROM outbox_event WHERE tenant_id='acme' AND employee_id='$EMP' AND event_type='EmployeeDeactivated'")
retrying() { [ "$(psql_roster "SELECT state FROM outbox_event WHERE event_id='$OUTBOX_EVENT'")" = RETRY ]; }
wait_until 60 "outbox row in RETRY after the lost acknowledgement" retrying
assert_ge "attempts before restart" 1 "$(psql_roster "SELECT attempts FROM outbox_event WHERE event_id='$OUTBOX_EVENT'")"
log "booking had committed: cancelled=$(psql_booking "SELECT count(*) FROM booking WHERE cancelled_by_event='$OUTBOX_EVENT'")"

log "restarting booking normally"
restart_normal booking
delivered() { [ "$(psql_roster "SELECT state FROM outbox_event WHERE event_id='$OUTBOX_EVENT'")" = DELIVERED ]; }
wait_until 240 "event redelivered and acknowledged" delivered

assert_eq "cancellations (3, not 6)" "3" "$(psql_booking "SELECT count(*) FROM booking WHERE tenant_id='acme' AND employee_id='$EMP' AND status='CANCELLED'")"
assert_eq "seats released" "3" "$(psql_booking "SELECT count(*) FROM seat_reservation s JOIN booking b USING (booking_id) WHERE b.tenant_id='acme' AND b.employee_id='$EMP' AND s.state='RELEASED'")"
assert_eq "processed_message rows for the event" "1" "$(psql_booking "SELECT count(*) FROM processed_message WHERE event_id='$OUTBOX_EVENT'")"
assert_eq "outbox state" "DELIVERED" "$(psql_roster "SELECT state FROM outbox_event WHERE event_id='$OUTBOX_EVENT'")"
assert_ge "delivery attempts" 2 "$(psql_roster "SELECT attempts FROM outbox_event WHERE event_id='$OUTBOX_EVENT'")"
finish
