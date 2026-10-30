#!/usr/bin/env bash
# Runs every fault script 3 times on a freshly reset stack and saves each run's output to
# docs/fault-runs/<script>-<n>.txt. Prints a summary table; exits non-zero if any run failed.
set -uo pipefail
. "$(dirname "$0")/../lib.sh"

SCRIPTS="kill-roster-after-commit kill-consumer-after-commit kill-during-reconciliation kill-relay-after-claim consumer-down db-pause"
OUT="$ROOT/docs/fault-runs"
RUNS=${RUNS:-3}
mkdir -p "$OUT"
SUMMARY="$STATE_DIR/fault-summary.txt"
: > "$SUMMARY"
TOTAL_FAIL=0

for script in $SCRIPTS; do
  for n in $(seq 1 "$RUNS"); do
    file="$OUT/$script-$n.txt"
    {
      echo "# $script run $n — $(date -u +%Y-%m-%dT%H:%M:%SZ)"
      echo "# host: $(uname -sm), docker $(docker version --format '{{.Server.Version}}' 2>/dev/null)"
      reset_stack
      started=$(date +%s)
      bash "$ROOT/scripts/faults/$script.sh"
      rc=$?
      echo "# exit=$rc duration=$(( $(date +%s) - started ))s"
      exit $rc
    } 2>&1 | tee "$file"
    rc=${PIPESTATUS[0]}
    result=PASS
    [ "$rc" -ne 0 ] && { result=FAIL; TOTAL_FAIL=$((TOTAL_FAIL + 1)); }
    printf '%-30s run %s  %s\n' "$script" "$n" "$result" >> "$SUMMARY"
  done
done

$DC down -v -t 5 >/dev/null 2>&1
{
  echo
  echo "Fault-injection summary ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
  cat "$SUMMARY"
  echo "failed runs: $TOTAL_FAIL"
} | tee "$OUT/SUMMARY.txt"
[ "$TOTAL_FAIL" -eq 0 ]
