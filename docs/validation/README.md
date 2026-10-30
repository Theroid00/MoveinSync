# Validation evidence

P12 normal tests and required-class checks: `p12-tests.txt`. Prometheus configuration and firing/idle alert tests: `p12-promtool.txt`. Runtime target, rule, datasource, dashboard and query checks: `p12-runtime.txt`.

`p12-brake.json`, `p12-alerts.json` and `p12-brake.txt` record a real snapshot stopped with zero mutations and its firing Prometheus alert. The run was rejected after inspection.

`dashboard.png` is a rendered capture of all 12 panels during operational checks. Grafana’s official image-rendering API and an ephemeral `grafana/grafana-image-renderer:v5.12.10` service produced it; the temporary renderer was removed after verification. Performance measurements remain in `bench/results/`.
