#!/usr/bin/env bash
# Disposable demo stack: resets this project's volumes and restores seeded data on success.
set -euo pipefail
. "$(dirname "$0")/../lib.sh"
cd "$ROOT"
reset_stack
HR=$(token hr@acme)
ADMIN=$(token admin@acme)
OPS=$(token ops@acme)
OTHER=$(token admin@globex)
EMPLOYEE=$(token emp001@acme)

log "brief example: v7 office transfer precedes v6 shift"
r=$(post_json "$ROSTER/api/v1/hr/events" "$HR" "$(upsert_json demo-v7 7 DEMO-ORDER GGN-A NIGHT)")
assert_eq "v7 applied" APPLIED "$(echo "${r#* }" | jq -r .outcome)"
r=$(post_json "$ROSTER/api/v1/hr/events" "$HR" "$(upsert_json demo-v6 6 DEMO-ORDER BLR-A MORNING)")
assert_eq "v6 ignored" STALE "$(echo "${r#* }" | jq -r .outcome)"
curl -fsS "$ROSTER/api/v1/employees/DEMO-ORDER" -H "Authorization: Bearer $ADMIN" |
  jq -e '.officeCode == "GGN-A" and .shiftCode == "NIGHT"' >/dev/null
curl -fsS "$ROSTER/api/v1/employees/DEMO-ORDER/history" -H "Authorization: Bearer $ADMIN" |
  jq -e 'any(.events[]; .outcome == "STALE")' >/dev/null

log "replay a feed without changing business state"
batch="$STATE_DIR/demo-feed.ndjson"
for i in 1 2 3; do upsert_json "demo-batch-$i" "$(next_seq)" "DEMO-BATCH-$i" BLR-A MORNING; echo; done > "$batch"
first=$(curl -fsS "$ROSTER/api/v1/hr/events/batch" -H "Authorization: Bearer $HR" -H 'Content-Type: application/x-ndjson' --data-binary "@$batch")
assert_eq "batch applied" 3 "$(echo "$first" | jq -r .summary.APPLIED)"
business_state() {
  psql_roster "SELECT md5(string_agg(e::text, '' ORDER BY employee_id)) FROM employee e WHERE tenant_id='acme'"
  psql_roster "SELECT (SELECT count(*) FROM employee_history)::text || ':' || count(*) FROM outbox_event"
}
before=$(business_state)
replay=$(curl -fsS "$ROSTER/api/v1/hr/events/batch" -H "Authorization: Bearer $HR" -H 'Content-Type: application/x-ndjson' --data-binary "@$batch")
assert_eq "replay duplicates" 3 "$(echo "$replay" | jq -r .summary.DUPLICATE)"
assert_eq "business state unchanged" "$before" "$(business_state)"

log "transport ownership, validation and tenant isolation"
revision=$(curl -fsS "$ROSTER/api/v1/employees/DEMO-ORDER" -H "Authorization: Bearer $ADMIN" | jq -r .aggregateRevision)
curl -fsS -X PATCH "$ROSTER/api/v1/employees/DEMO-ORDER/transport" -H "Authorization: Bearer $ADMIN" \
  -H "If-Match: $revision" -H 'Content-Type: application/json' -d '{"pickupPoint":"Demo stop","specialAssistance":"Demo assistance"}' >/dev/null
r=$(post_json "$ROSTER/api/v1/hr/events" "$HR" "$(upsert_json demo-v8 8 DEMO-ORDER BLR-B EVENING)")
assert_eq "newer HR event applied" APPLIED "$(echo "${r#* }" | jq -r .outcome)"
curl -fsS "$ROSTER/api/v1/employees/DEMO-ORDER" -H "Authorization: Bearer $ADMIN" |
  jq -e '.pickupPoint == "Demo stop" and .specialAssistance == "Demo assistance" and .officeCode == "BLR-B"' >/dev/null
bad=$(upsert_json demo-owned-field 9 DEMO-ORDER BLR-A MORNING | jq '.hr.pickupPoint="forbidden"')
r=$(post_json "$ROSTER/api/v1/hr/events" "$HR" "$bad")
assert_eq "HR cannot write pickup" 422 "${r%% *}"
assert_eq "ownership error code" UNKNOWN_FIELD "$(echo "${r#* }" | jq -r .errorCode)"
code=$(curl -sS -o "$STATE_DIR/demo-cross-tenant.json" -w '%{http_code}' "$ROSTER/api/v1/employees/DEMO-ORDER" -H "Authorization: Bearer $OTHER")
assert_eq "another tenant sees 404" 404 "$code"

log "booking API and derived seats"
starts=$(python3 -c 'from datetime import datetime,timedelta,timezone; print((datetime.now(timezone.utc)+timedelta(days=30)).isoformat())')
trip=$(curl -fsS -X POST "$BOOKING/api/v1/trips" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d "{\"capacity\":1,\"startsAt\":\"$starts\"}" | jq -er .tripId)
booking=$(curl -fsS -X POST "$BOOKING/api/v1/bookings" -H "Authorization: Bearer $EMPLOYEE" -H 'Content-Type: application/json' -d "{\"employeeId\":\"EMP-001\",\"tripId\":\"$trip\"}" | jq -er .bookingId)
curl -fsS "$BOOKING/api/v1/bookings/$booking" -H "Authorization: Bearer $EMPLOYEE" | jq -e '.status == "RESERVED"' >/dev/null
curl -fsS "$BOOKING/api/v1/trips/$trip/seats" -H "Authorization: Bearer $EMPLOYEE" | jq -e '.capacity == 1 and .reserved == 1 and .available == 0' >/dev/null
wait_until 120 "outbox drained before fault demonstrations" outbox_drained
scripts/faults/kill-roster-after-commit.sh
scripts/faults/kill-consumer-after-commit.sh

log "unit, randomized and concurrency tests"
./mvnw -q install
scripts/check-tests.sh

log "brief example: 120k rows out of a 200k baseline"
./mvnw -q -pl roster-service test-compile dependency:build-classpath -Dmdep.outputFile=target/bench-classpath.txt
java -cp "roster-service/target/test-classes:roster-service/target/classes:$(cat roster-service/target/bench-classpath.txt)" com.moveinsync.rostersync.roster.bench.ExportGenerator
psql_roster "TRUNCATE employee, employee_history, outbox_event, staging_employee, record_error, reconciliation_run, employee_tombstone, ingest_event"
$DC exec -T postgres psql -U postgres -d roster -v ON_ERROR_STOP=1 \
  -c 'COPY employee (tenant_id,source_id,employee_id,name,email,phone,manager_id,office_code,shift_code,active,source_seq,hr_hash,bucket,aggregate_revision) FROM STDIN (FORMAT csv)' \
  < roster-service/target/bench/baseline.csv >/dev/null
accepted=$(curl -fsS -X POST "$ROSTER/api/v1/reconciliations" -H "Authorization: Bearer $OPS" \
  -F 'file=@roster-service/target/bench/cut.csv;type=text/csv' -F 'manifest=@roster-service/target/bench/cut.manifest.json;type=application/json')
run_id=$(echo "$accepted" | jq -er .runId)
braked() {
  curl -fsS "$ROSTER/api/v1/reconciliations/$run_id" -H "Authorization: Bearer $OPS" > "$STATE_DIR/demo-brake.json"
  jq -e '.status == "AWAITING_APPROVAL"' "$STATE_DIR/demo-brake.json" >/dev/null
}
wait_until 120 "snapshot waiting for approval" braked
jq -e '.baseline == 200000 and .missing == 80000 and .ratioBp == 4000' "$STATE_DIR/demo-brake.json" >/dev/null
assert_eq "zero deactivations before approval" 0 "$(psql_roster "SELECT count(*) FROM employee WHERE NOT active")"
assert_eq "zero outbox mutations at the brake" 0 "$(psql_roster "SELECT count(*) FROM outbox_event")"
curl -fsS "$ROSTER/api/v1/reconciliations/pending-approval" -H "Authorization: Bearer $OPS" | jq -e --arg id "$run_id" 'any(.[]; .runId == $id)' >/dev/null
curl -fsS -X POST "$ROSTER/api/v1/reconciliations/$run_id/reject" -H "Authorization: Bearer $OPS" -H 'Content-Type: application/json' -d '{"reason":"Demo inspection complete"}' >/dev/null
log "PASS: 80,000 missing, 40 percent, no live mutations; rejected safely"

reset_stack
docker compose --profile observability up -d >/dev/null 2>&1
scripts/check-observability.sh
finish
