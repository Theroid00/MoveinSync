#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/lib.sh"

targets_up() {
  curl -fsS http://localhost:9090/api/v1/targets | jq -e '
    [.data.activeTargets[] | select(.labels.job == "roster" or .labels.job == "booking")]
    | length == 2 and all(.[]; .health == "up")' >/dev/null
}
rules_loaded() {
  curl -fsS http://localhost:9090/api/v1/rules | jq -e '
    [.data.groups[] | select(.name == "roster-sync") | .rules[]]
    | length == 6 and all(.[]; .health == "ok")' >/dev/null
}
dashboard_loaded() {
  curl -fsS -u admin:demo123 http://localhost:3000/api/dashboards/uid/roster-sync |
    jq -e '.dashboard.uid == "roster-sync" and (.dashboard.panels | length) == 12' >/dev/null
}

wait_until 120 "both Prometheus targets UP" targets_up
wait_until 120 "six alert rules healthy" rules_loaded
wait_until 120 "Grafana dashboard provisioned" dashboard_loaded
curl -fsS -u admin:demo123 http://localhost:3000/api/datasources/uid/prometheus/health |
  jq -e '.status == "OK"' >/dev/null
while IFS= read -r expr; do
  curl -fsS --get --data-urlencode "query=$expr" http://localhost:9090/api/v1/query |
    jq -e '.status == "success"' >/dev/null
done < <(jq -r '.panels[].targets[].expr' "$ROOT/ops/grafana/roster.json")
log "PASS: both targets, six alert rules, Grafana datasource/dashboard, all dashboard queries"
