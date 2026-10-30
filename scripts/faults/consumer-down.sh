#!/usr/bin/env bash
# booking is stopped for 60 s while 20 deactivations happen. The backlog and its age are visible on
# /actuator/prometheus; after booking starts again the relay drains it and cancellations are exact.
set -uo pipefail
. "$(dirname "$0")/../lib.sh"

HR=$(token hr@acme)
metric() { curl -sS "$ROSTER/actuator/prometheus" | awk -v m="$1" 'index($0, m) == 1 {print $2; exit}'; }

log "stopping booking"
$DC stop booking >/dev/null 2>&1
RUN="down-$(date +%s)"
for i in $(seq 31 50); do
  RESPONSE=$(post_json "$ROSTER/api/v1/hr/events" "$HR" "$(deactivate_json "$RUN-$i" "$(next_seq)" "$(printf 'EMP-%03d' "$i")")")
  [ "${RESPONSE%% *}" = 200 ] || die "deactivation $i not accepted: $RESPONSE"
done
STOPPED_AT=$(date +%s)

retry_backlog() { awk -v v="$(metric 'outbox_queue_depth{state="RETRY"')" 'BEGIN { exit !(v > 0) }'; }
wait_until 30 "outbox_queue_depth{state=\"RETRY\"} > 0" retry_backlog
AGE1=$(metric 'outbox_oldest_pending_age_seconds')
sleep 12
AGE2=$(metric 'outbox_oldest_pending_age_seconds')
log "queue depth RETRY=$(metric 'outbox_queue_depth{state="RETRY"'), oldest pending age ${AGE1}s → ${AGE2}s"
if awk -v a="$AGE1" -v b="$AGE2" 'BEGIN { exit !(b > a) }'; then log "PASS  oldest pending age rising"; else log "FAIL  oldest pending age not rising"; FAILURES=$((FAILURES + 1)); fi

REMAINING=$(( 60 - ($(date +%s) - STOPPED_AT) ))
[ "$REMAINING" -gt 0 ] && { log "booking stays down another ${REMAINING}s"; sleep "$REMAINING"; }
log "starting booking"
$DC start booking >/dev/null 2>&1
wait_healthy booking
wait_until 300 "backlog drained" outbox_drained

assert_eq "cancellations (20 employees × 3 future bookings)" "60" "$(psql_booking "SELECT count(*) FROM booking WHERE tenant_id='acme' AND employee_id BETWEEN 'EMP-031' AND 'EMP-050' AND status='CANCELLED'")"
assert_eq "seats released" "60" "$(psql_booking "SELECT count(*) FROM seat_reservation s JOIN booking b USING (booking_id) WHERE b.tenant_id='acme' AND b.employee_id BETWEEN 'EMP-031' AND 'EMP-050' AND s.state='RELEASED'")"
assert_eq "projections inactive" "20" "$(psql_booking "SELECT count(*) FROM employee_projection WHERE tenant_id='acme' AND employee_id BETWEEN 'EMP-031' AND 'EMP-050' AND NOT active")"
assert_eq "dead letters" "0" "$(psql_roster "SELECT count(*) FROM outbox_event WHERE state='DEAD'")"
finish
