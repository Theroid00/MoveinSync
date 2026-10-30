#!/usr/bin/env bash
# roster halts inside the reconciliation apply transaction (after the deactivation statement). Nothing
# may be partially applied; after restart, recovery resumes the staged run and completes it exactly once.
set -uo pipefail
. "$(dirname "$0")/../lib.sh"

OPS=$(token ops@acme)
WORK="$STATE_DIR/recon"
mkdir -p "$WORK"

# snapshot <file> <skip-first-n-generated> : EMP-001..100 (seq 1000+i) + G-00001..G-19900 (seq 2000+i)
snapshot() {
  awk -v skip="$2" 'BEGIN {
    print "employee_id,name,email,phone,manager_id,office_code,shift_code,last_change_seq";
    split("BLR-A BLR-B GGN-A", o, " "); split("MORNING EVENING NIGHT", s, " ");
    for (i = 1; i <= 100; i++) printf "EMP-%03d,Employee %03d,emp%03d@acme.example,+9190000%05d,,%s,%s,%d\n", i, i, i, i, o[i%3+1], s[i%3+1], 1000+i;
    for (i = skip + 1; i <= 19900; i++) printf "G-%05d,Generated %05d,g%05d@acme.example,+9180000%05d,,%s,%s,%d\n", i, i, i, i, o[i%3+1], s[i%3+1], 2000+i;
  }' > "$1"
}

# upload <file> <cutoff> → runId
upload() {
  curl -sS -X POST "$ROSTER/api/v1/reconciliations" -H "Authorization: Bearer $OPS" \
    -F "file=@$1;type=text/csv" \
    -F "manifest={\"sourceId\":\"hr-main\",\"cutoffSeq\":$2,\"exportedAt\":\"$(now_iso)\"};type=application/json" | jq -r .runId
}
run_status() { curl -sS "$ROSTER/api/v1/reconciliations/$1" -H "Authorization: Bearer $OPS" | jq -r .status; }

snapshot "$WORK/initial.csv" 0
snapshot "$WORK/night.csv" 10                 # G-00001..G-00010 absent: 10 of 20,000 (0.05%)
log "initial load: $(($(wc -l < "$WORK/initial.csv") - 1)) rows"
INITIAL=$(upload "$WORK/initial.csv" 30000)
completed() { [ "$(run_status "$INITIAL")" = COMPLETED ]; }
wait_until 300 "initial 20k run completed" completed
wait_until 600 "initial run's events delivered downstream" outbox_drained

log "arming DURING_RECON_APPLY on roster"
ROSTER_FAULT=DURING_RECON_APPLY $DC up -d --force-recreate --no-deps roster >/dev/null 2>&1
wait_healthy roster
OPS=$(token ops@acme)
INACTIVE_BEFORE=$(psql_roster "SELECT count(*) FROM employee WHERE tenant_id='acme' AND NOT active")
OUTBOX_BEFORE=$(psql_roster "SELECT count(*) FROM outbox_event WHERE tenant_id='acme'")
NIGHT=$(upload "$WORK/night.csv" 40000)
log "night run $NIGHT uploaded"
wait_exit roster 137 300

assert_eq "inactive count unchanged after the crash (no partial apply)" "$INACTIVE_BEFORE" \
  "$(psql_roster "SELECT count(*) FROM employee WHERE tenant_id='acme' AND NOT active")"
assert_eq "no outbox rows from the crashed apply" "$OUTBOX_BEFORE" "$(psql_roster "SELECT count(*) FROM outbox_event WHERE tenant_id='acme'")"
assert_eq "run left STAGED (APPLYING rolled back)" "STAGED" "$(psql_roster "SELECT status FROM reconciliation_run WHERE run_id='$NIGHT'")"

log "restarting roster normally (startup recovery resumes the staged run)"
restart_normal roster
OPS=$(token ops@acme)
night_done() { [ "$(run_status "$NIGHT")" = COMPLETED ]; }
wait_until 300 "recovered run completed" night_done

assert_eq "exactly 10 deactivated" "$((INACTIVE_BEFORE + 10))" "$(psql_roster "SELECT count(*) FROM employee WHERE tenant_id='acme' AND NOT active")"
assert_eq "run counts.deactivated" "10" "$(psql_roster "SELECT counts->>'deactivated' FROM reconciliation_run WHERE run_id='$NIGHT'")"
assert_eq "one deactivation event per absent employee" "10" "$(psql_roster "SELECT count(*) FROM outbox_event WHERE tenant_id='acme' AND event_type='EmployeeDeactivated' AND employee_id BETWEEN 'G-00001' AND 'G-00010'")"
assert_eq "no duplicate outbox rows (event ids unique per employee revision)" "0" "$(psql_roster "SELECT count(*) FROM (SELECT employee_id, aggregate_revision FROM outbox_event WHERE tenant_id='acme' GROUP BY 1, 2 HAVING count(*) > 1) d")"
finish
