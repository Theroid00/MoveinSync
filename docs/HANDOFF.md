# Remaining work and next steps

Updated 2026-10-31. Resume from **P11**, not from the older Claude session summary. Read [PROGRESS.md](../PROGRESS.md) first; [the build spec](spec/AGENT_BUILD_SPEC.md) is the source of truth for acceptance criteria.

## Current state

| Work | Status | Evidence |
|---|---|---|
| P0–P9: services, security, reconciliation, operations hooks and concurrency | Complete | Phase commits and checks in [PROGRESS.md](../PROGRESS.md) |
| P10: process-level fault injection | Complete | [18 passing runs, six scenarios × three](fault-runs/SUMMARY.txt); phase commit `78fa078` |
| Database-outage response fix | Complete | `4f9b105`: preserve 503 `DB_UNAVAILABLE` when a failed rollback hides the original timeout; regression tests in both services |
| P11: benchmark tooling | Implemented; full execution pending | `de134a5`; [runner and instructions](../bench/README.md) |
| P12: Prometheus alerts and Grafana | Not started | `ops/` still needs its configuration; no observability Compose services yet |
| P13: submission docs, Postman and demo | Not started | Final README, design note, decisions, Postman collection and demo scripts remain |
| Release `v1.0` | Not created | Requires all phase gates and final checks |

Last completed normal gate: **313 tests, zero failures/errors/skips; all 42 required test classes present**. Benchmark code compiles. The k6 payload self-check passed **7/7**, the Python report fixture test passed, and generated counts were checked for all six exports. These checks do not establish benchmark performance or full-run success.

## Next action: run P11 on the other computer

**The user requested that heavy benchmarks run on another computer. Do not restart them on this Mac.** No complete local benchmark result has been accepted; [docs/benchmark.md](benchmark.md) intentionally says so.

Use a dedicated checkout with Java 21, Docker Engine + Compose, bash, curl, jq and Python 3. Docker must be running. See [bench/README.md](../bench/README.md) for Java/Colima setup and runner details.

```bash
git clone https://github.com/Theroid00/HR.git roster-sync
cd roster-sync
java -version
docker compose version
./mvnw -q verify && scripts/check-tests.sh
```

Then run the benchmark suite in bash, retaining its console log and exit status:

```bash
set -o pipefail
scripts/bench/run-all.sh 2>&1 | tee benchmark-run.log
```

The runner **deletes and recreates the roster-sync Compose volumes** between test stacks. Keep unrelated data out of that Compose project and ports 8080/8081 free. It runs lightweight self-checks first, then the heavy reconciliation scenarios, API load and contention tests. `benchmark-run.log` is ignored by Git; retain it for troubleshooting.

### Known issue to verify first

The first local attempt failed because PostgreSQL prohibits COPY directly into an RLS table. The benchmark fixture now COPYs into a temporary table, then inserts under the app role's tenant policy. Production role permissions were not broadened.

The second attempt reached a **300-second timeout in the apply UPDATE during an initial-load iteration**. The code now runs `ANALYZE recon_plan` after creating that temporary table, so apply joins can use its action distribution. Normal tests pass, but **the performance effect has not been verified**. The RLS-compatible baseline fixture and complete runner also still need full execution. [Local failure evidence](../bench/local-attempts/query-timeout.txt) and [partial measurements](../bench/local-attempts/recon-initial-partial.json) are diagnostic artifacts, not final results.

If the remote run fails, retain the console log, Failsafe report and partial outputs, identify the failing scenario/iteration, and fix the underlying issue before repeating the gate. Keep the heap cap, mutation assertions and timing bounds intact. Do not replace missing measurements with estimates or copy partial local results into the final report.

### P11 acceptance checklist

- [ ] `scripts/bench/run-all.sh` exits zero on the benchmark machine.
- [ ] Each of `initial`, `typical`, `heavy`, `quiet`, `cut`, `invalid` has one warm-up and **three measured iterations**, each starting from the correct restored baseline at `-Xmx256m`.
- [ ] Exact outcomes hold: initial creates 200,000; typical updates 6,000 / creates 2,000 / deactivates 2,000; heavy updates 100,000; quiet updates 100; cut blocks with zero mutations; invalid suppresses inferred deactivations.
- [ ] Typical and heavy each satisfy `digest + assess + apply < 60,000 ms`; quiet touches less than 1% of 65,536 buckets.
- [ ] All four 60-second k6 rates (100/250/500/1000 per second) and the 250/s contention run finish. The report includes latency, achieved rate, errors, drops, the observed apply-window p99, lock timing and delivery lag.
- [ ] Machine/runtime information and raw outputs are present, and `docs/benchmark.md` is generated from those outputs.

Bring back these artifacts:

| Path | Purpose |
|---|---|
| `bench/results/` | Six reconciliation JSON files, four API summaries/logs, contention run/window/request samples, lock observations, Prometheus scrapes and machine information |
| `docs/benchmark.md` | Generated results report |
| `roster-service/target/failsafe-reports/*ReconciliationBenchmarkIT*` | Test outcome and per-iteration diagnostic output; copy separately because `target/` is ignored |
| `benchmark-run.log` | Overall execution log and failure context |
| `bench/tmp/contention.jsonl` | Keep until reporting succeeds; then the compact samples in `bench/results/` suffice to rebuild the report |

Review the results, run the normal gate again, commit the accepted raw results and report with **`perf: benchmarks with raw results`**, then mark P11 DONE in `PROGRESS.md` with the actual commit hash. Partial execution does not close P11.

## After P11 passes: P12 observability

Implement the `observability` Compose profile using the image versions already pinned in [known-issues.md](known-issues.md). Add:

- `ops/prometheus.yml`: scrape both applications and load the alert rules.
- `ops/alerts.yml`: safety brake, outbox lag, dead letters, reconciliation failure, validation rejection spike and consumer duplicate spike alerts, using the implemented metric names.
- Grafana datasource/dashboard provisioning and `ops/grafana/roster.json`, showing ingest latency/outcomes, reconciliation phases and lock duration, outbox backlog/lag/retries/dead letters, and consumer cancellations/seat releases.

Run the normal gate and `docker compose --profile observability up -d`. Verify **both Prometheus targets UP, alert rules loaded, and the Grafana dashboard rendering**. Commit with **`feat(ops): prometheus alerts and grafana dashboard`**, then update P12 in `PROGRESS.md`.

## After P12 passes: P13 submission package

Follow [spec §20](spec/AGENT_BUILD_SPEC.md) for exact content and ordering:

- Root `README.md`: summary/video placeholder, measured results, judgment calls, quick start and five curls, coverage links, architecture, trade-offs/scaling limits, test/fault/benchmark commands and language choice.
- `docs/design-note.md`: at most two pages, including the consistency model, architecture, reconciliation → relay → consumer-crash sequence and failure modes.
- `docs/decisions.md`: DEC-01 through DEC-23, reflecting the implementation. Reconciliation currently computes detailed `changedFields`; do not claim it uses the optional `["hr"]` fallback.
- `postman/collection.json` and `environment.json`: all endpoints and token acquisition per role.
- `scripts/demo/run-all.sh`: the complete demo flow with assertions, usable as a smoke test.
- Reconcile `docs/known-issues.md` with actual results; remove obsolete placeholders. The only final TODO should be `VIDEO_LINK_TODO`.

Validate from a fresh checkout: `./mvnw -q verify && scripts/check-tests.sh`, `docker compose up -d --build`, and `scripts/demo/run-all.sh`. Confirm both services are healthy and seeded. Commit with **`docs: README, design note, decisions, postman`**, then update P13.

## Final release and human actions

Check every item in [spec §21](spec/AGENT_BUILD_SPEC.md): tests/classes and named cases, no disabled tests, PII checks, complete real benchmark results, 18 passing fault runs, observability, fresh-checkout startup/demo, documentation and phase history. **Create and push `v1.0` only after all gates pass.**

The human still needs to record the demo video, replace `VIDEO_LINK_TODO`, confirm the GitHub repository is publicly visible when logged out, review the version gate/reconciliation logic, and submit the repository/video links through the assessment channel. A current code push is not a finished release or an assessment submission.
