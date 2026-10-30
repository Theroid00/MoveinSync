#!/usr/bin/env bash
# Two-minute tour of the main guarantees against the running stack (`docker compose up -d --build --wait`).
# Does not reset any data and can be re-run. Each step prints PASS/FAIL lines; exits non-zero on a failure.
set -uo pipefail
. "$(dirname "$0")/../lib.sh"

step() { printf '\n== %s\n' "$*"; }
outcome() { echo "${1#* }" | jq -r '.outcome // .errorCode'; }

wait_until 240 "services up and demo data seeded" seeded
HR=$(token hr@acme)
ADMIN=$(token admin@acme)
OPS=$(token ops@acme)
OTHER=$(token admin@globex)
EMP="TOUR-$(date +%s)"

step "1. Out of order: the v7 office transfer arrives before the v6 shift change"
assert_eq "v7 applied" APPLIED "$(outcome "$(post_json "$ROSTER/api/v1/hr/events" "$HR" "$(upsert_json "$EMP-v7" 7 "$EMP" GGN-A NIGHT)")")"
assert_eq "v6 ignored as stale" STALE "$(outcome "$(post_json "$ROSTER/api/v1/hr/events" "$HR" "$(upsert_json "$EMP-v6" 6 "$EMP" BLR-A MORNING)")")"
assert_eq "employee has v7's office and shift" "GGN-A NIGHT" \
  "$(curl -fsS "$ROSTER/api/v1/employees/$EMP" -H "Authorization: Bearer $ADMIN" | jq -r '.officeCode + " " + .shiftCode')"

step "2. Duplicates: the same event delivered again changes nothing"
assert_eq "resent v7 is a duplicate" DUPLICATE "$(outcome "$(post_json "$ROSTER/api/v1/hr/events" "$HR" "$(upsert_json "$EMP-v7" 7 "$EMP" GGN-A NIGHT)")")"
assert_eq "still one change event in the outbox" 1 "$(psql_roster "SELECT count(*) FROM outbox_event WHERE tenant_id='acme' AND employee_id='$EMP'")"

step "3. Field ownership: HR updates never overwrite the transport admin's pickup point"
REV=$(curl -fsS "$ROSTER/api/v1/employees/$EMP" -H "Authorization: Bearer $ADMIN" | jq -r .aggregateRevision)
STATUS=$(curl -sS -o /dev/null -w '%{http_code}' -X PATCH "$ROSTER/api/v1/employees/$EMP/transport" -H "Authorization: Bearer $ADMIN" \
  -H "If-Match: \"$REV\"" -H 'Content-Type: application/json' -d '{"pickupPoint":"Gate 4","specialAssistance":null}')
assert_eq "transport admin sets the pickup point" 200 "$STATUS"
assert_eq "later HR update applied" APPLIED "$(outcome "$(post_json "$ROSTER/api/v1/hr/events" "$HR" "$(upsert_json "$EMP-v8" 8 "$EMP" BLR-B NIGHT)")")"
assert_eq "pickup point survived the HR update" "Gate 4" \
  "$(curl -fsS "$ROSTER/api/v1/employees/$EMP" -H "Authorization: Bearer $ADMIN" | jq -r .pickupPoint)"
SMUGGLED=$(upsert_json "$EMP-v9" 9 "$EMP" BLR-B NIGHT | jq -c '.hr.pickupPoint = "Gate 9"')
assert_eq "HR payload carrying a pickup point is refused" UNKNOWN_FIELD "$(outcome "$(post_json "$ROSTER/api/v1/hr/events" "$HR" "$SMUGGLED")")"

step "4. Bad data: a malformed phone is rejected with a per-record code, and the value is never echoed"
BAD=$(upsert_json "$EMP-bad" 10 "$EMP" BLR-B NIGHT | jq -c '.hr.phone = "98450-12345"')
RESPONSE=$(post_json "$ROSTER/api/v1/hr/events" "$HR" "$BAD")
assert_eq "rejected with PHONE_E164" PHONE_E164 "$(echo "${RESPONSE#* }" | jq -r '.errors[0].code')"
if echo "$RESPONSE" | grep -q '98450'; then log "FAIL  the rejected value was echoed"; FAILURES=$((FAILURES + 1)); else log "PASS  rejected value not echoed"; fi

step "5. Tenant isolation: another tenant's admin cannot see this employee"
assert_eq "globex admin reading an acme employee" 404 \
  "$(curl -sS -o /dev/null -w '%{http_code}' "$ROSTER/api/v1/employees/$EMP" -H "Authorization: Bearer $OTHER")"

step "6. Downstream: deactivating an employee cancels their future bookings and frees the seats"
LEAVER=EMP-045
assert_eq "deactivation accepted" 200 "$(post_json "$ROSTER/api/v1/hr/events" "$HR" "$(deactivate_json "$EMP-leaver" "$(next_seq)" "$LEAVER")" | cut -d' ' -f1)"
wait_until 60 "outbox delivered to booking-service" outbox_drained
BOOKINGS=$(curl -fsS "$BOOKING/api/v1/employees/$LEAVER/bookings" -H "Authorization: Bearer $ADMIN")
assert_eq "future bookings now cancelled" "3" "$(echo "$BOOKINGS" | jq '[.[] | select(.status == "CANCELLED")] | length')"
assert_eq "bookings still reserved" "0" "$(echo "$BOOKINGS" | jq '[.[] | select(.status == "RESERVED")] | length')"
assert_eq "seats still held" "0" "$(echo "$BOOKINGS" | jq '[.[] | select(.seatState == "ACTIVE")] | length')"

step "7. Safety brake: a nightly export missing ~half the employees stops; nobody is deactivated"
CSV="$STATE_DIR/tour-cut.csv"
awk 'BEGIN { print "employee_id,name,email,phone,manager_id,office_code,shift_code,last_change_seq";
  split("BLR-A BLR-B GGN-A", o, " "); split("MORNING EVENING NIGHT", s, " ");
  for (i = 1; i <= 50; i++) printf "EMP-%03d,Employee %03d,emp%03d@acme.example,+9190000%05d,%s,%s,%s,%d\n",
    i, i, i, i, (i == 1 ? "" : "EMP-001"), o[i % 3 + 1], s[i % 3 + 1], i }' > "$CSV"
ACTIVE_BEFORE=$(psql_roster "SELECT count(*) FROM employee WHERE tenant_id='acme' AND active")
RUN=$(curl -fsS -X POST "$ROSTER/api/v1/reconciliations" -H "Authorization: Bearer $OPS" -F "file=@$CSV;type=text/csv" \
  -F "manifest={\"sourceId\":\"hr-main\",\"cutoffSeq\":1000,\"exportedAt\":\"$(now_iso)\"};type=application/json" | jq -r .runId)
awaiting() { [ "$(curl -fsS "$ROSTER/api/v1/reconciliations/$RUN" -H "Authorization: Bearer $OPS" | jq -r .status)" = AWAITING_APPROVAL ]; }
wait_until 60 "run stopped by the safety brake" awaiting
curl -fsS "$ROSTER/api/v1/reconciliations/$RUN" -H "Authorization: Bearer $OPS" |
  jq -r '"   operator sees: \(.missing) of \(.baseline) active employees missing (\(.ratioBp / 100)%), flags \(.flags | join(","))"'
assert_eq "nobody deactivated" "$ACTIVE_BEFORE" "$(psql_roster "SELECT count(*) FROM employee WHERE tenant_id='acme' AND active")"
assert_eq "operator rejects the broken export" REJECTED \
  "$(curl -fsS -X POST "$ROSTER/api/v1/reconciliations/$RUN/reject" -H "Authorization: Bearer $OPS" | jq -r .status)"

step "8. Monitoring: the same events are visible to an on-call engineer"
curl -fsS "$ROSTER/actuator/prometheus" | grep -E '^(roster_safety_brake_trips_total|outbox_queue_depth\{state="(PENDING|DEAD)"|roster_events_total\{outcome="(APPLIED|STALE|DUPLICATE|REJECTED)")' | sed 's/^/   /'

finish
