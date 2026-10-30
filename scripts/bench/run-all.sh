#!/usr/bin/env bash
# All measured results are retained; fixture restoration and API seeding are outside the timing.
set -euo pipefail
. "$(dirname "$0")/../lib.sh"
cd "$ROOT"
mkdir -p bench/results bench/tmp
python3 scripts/bench/test_report.py
docker compose --profile bench run --no-deps --rm k6 run /scripts/ingest-test.js

log "running capped-heap reconciliation benchmarks"
./mvnw -q -pl roster-service -am verify -Pbenchmark
# Reuse Maven's test runtime classpath, without adding a build dependency.
./mvnw -q -pl roster-service -am test-compile dependency:build-classpath -Dmdep.outputFile=target/bench-classpath.txt
java -cp "roster-service/target/test-classes:roster-service/target/classes:$(cat roster-service/target/bench-classpath.txt)" \
  com.moveinsync.rostersync.roster.bench.ExportGenerator

reset_stack
{
  date -u +%Y-%m-%dT%H:%M:%SZ
  uname -a
  if command -v nproc >/dev/null; then nproc; free -h; else sysctl hw.ncpu hw.memsize; fi
  java -version 2>&1
  docker info --format 'Docker CPUs={{.NCPU}} memory_bytes={{.MemTotal}}'
  psql_roster "SELECT version()"
  psql_roster "SELECT name || '=' || setting || coalesce(unit, '') FROM pg_settings WHERE name IN ('shared_buffers','work_mem','max_connections')"
} > bench/results/machine.txt

for rate in 100 250 500 1000; do
  log "k6 rate=$rate/s, 60 seconds"
  docker compose --profile bench run --rm k6 run --summary-export="/results/k6-$rate.json" \
    -e RATE="$rate" -e RUN_ID="rate-$rate-$(date +%s)" /scripts/ingest.js > "bench/results/k6-$rate.txt" 2>&1
  curl -fsS "$ROSTER/actuator/prometheus" > "bench/results/prometheus-$rate.txt"
  reset_stack
done

log "restoring 200k baseline for contention"
psql_roster "TRUNCATE employee, employee_history, outbox_event, staging_employee, record_error, reconciliation_run, employee_tombstone, ingest_event"
# Fixture-only superuser COPY; the Java benchmark fixture retains the runtime role and RLS.
docker compose exec -T postgres psql -U postgres -d roster -v ON_ERROR_STOP=1 \
  -c 'COPY employee (tenant_id,source_id,employee_id,name,email,phone,manager_id,office_code,shift_code,active,source_seq,hr_hash,bucket,aggregate_revision) FROM STDIN (FORMAT csv)' \
  < roster-service/target/bench/baseline.csv > /dev/null
psql_roster "ANALYZE employee"
curl -fsS "$ROSTER/actuator/prometheus" > bench/tmp/contention-before.prom

docker compose --profile bench run --rm k6 run --out json=/raw/contention.jsonl \
  --summary-export=/results/k6-contention.json -e RATE=250 -e RUN_ID="contention-$(date +%s)" \
  /scripts/ingest.js > bench/results/k6-contention.txt 2>&1 &
LOAD_PID=$!
trap 'kill "$LOAD_PID" 2>/dev/null || true' EXIT
# k6's setup seeds exactly 10,000 employees; wait until measured iterations have started.
load_started() { [ "$(psql_roster "SELECT count(*) FROM ingest_event WHERE event_id LIKE 'contention-%' AND event_id NOT LIKE 'seed-%'")" -gt 250 ]; }
wait_until 180 "contention load started" load_started

OPS=$(token ops@acme)
lock_key=$(psql_roster "SELECT lock_key FROM tenant_source WHERE tenant_id='acme' AND source_id='hr-main'")
observe_lock() {
  while kill -0 "$LOAD_PID" 2>/dev/null; do
    psql_roster "SELECT extract(epoch FROM clock_timestamp()) || ',' || EXISTS (SELECT 1 FROM pg_locks WHERE locktype='advisory' AND mode='ExclusiveLock' AND granted AND classid::bigint=($lock_key::bigint >> 32) AND objid::bigint=($lock_key::bigint & 4294967295))"
    sleep 0.2
  done
}
observe_lock > bench/results/contention-lock.csv &
OBSERVER_PID=$!
accepted=$(curl -fsS -X POST "$ROSTER/api/v1/reconciliations" -H "Authorization: Bearer $OPS" \
  -F 'file=@roster-service/target/bench/typical.csv;type=text/csv' \
  -F 'manifest=@roster-service/target/bench/typical.manifest.json;type=application/json')
run_id=$(echo "$accepted" | jq -er .runId)
wait "$LOAD_PID"
wait "$OBSERVER_PID"
trap - EXIT
curl -fsS "$ROSTER/api/v1/reconciliations/$run_id" -H "Authorization: Bearer $OPS" > bench/results/contention-run.json
jq -e '.status == "COMPLETED" and .counts.updated == 6000 and .counts.created == 2000 and .counts.deactivated == 2000' \
  bench/results/contention-run.json > /dev/null
curl -fsS "$ROSTER/actuator/prometheus" > bench/results/prometheus-contention.txt

python3 scripts/bench/report.py
log "benchmarks complete: bench/results/ and docs/benchmark.md"
