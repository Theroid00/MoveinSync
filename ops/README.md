# Observability

Start with `docker compose --profile observability up -d`.
Prometheus is at <http://localhost:9090>; Grafana is at <http://localhost:3000/d/roster-sync/roster-sync>, login `admin` / `demo123`.
The dashboard and datasource are provisioned from this directory. Both UIs bind to loopback.

The six rules cover safety brakes, sustained outbox lag, current dead letters, reconciliation failures, rejected-event ratio and consumer duplicate ratio. They appear in Prometheus's Alerts view; notification delivery requires a separately configured Alertmanager. Pending reconciliations also remain available through the operator API.

Queue gauges refresh every five seconds. Delivered-event latency describes acknowledged events; inspect pending depth and oldest age alongside it. At 1,000/s the measured relay falls behind HTTP ingest. See [the benchmark report](../docs/benchmark.md).

Validate the running stack with `scripts/check-observability.sh`. Validate configuration and firing/idle rule cases with:

```bash
docker run --rm --entrypoint /bin/promtool -v "$PWD/ops:/etc/prometheus:ro" prom/prometheus:v3.15.1 check config /etc/prometheus/prometheus.yml
docker run --rm --entrypoint /bin/promtool -v "$PWD/ops:/etc/prometheus:ro" prom/prometheus:v3.15.1 test rules /etc/prometheus/alerts-test.yml
```

Configuration follows the [Prometheus reference](https://prometheus.io/docs/prometheus/latest/configuration/configuration/) and [Grafana provisioning reference](https://grafana.com/docs/grafana/latest/administration/provisioning/).

[Dashboard capture](../docs/validation/dashboard.png) and [validation logs](../docs/validation/) record the P12 check. An ephemeral official Grafana renderer v5.12.10 produced the capture because no interactive browser was connected; the renderer is not a runtime dependency.
