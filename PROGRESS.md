# PROGRESS

Tracks execution of `docs/spec/AGENT_BUILD_SPEC.md` §16 phases. Read this file first in every session.

Gate command (default): `./mvnw -q verify && scripts/check-tests.sh`

| Phase | Deliverable | Status | Commit | Notes |
|---|---|---|---|---|
| P0 | Parent + 3 modules, wrapper, Surefire/Failsafe config, `scripts/check-tests.sh`, pinned images, docker/ init SQL, Dockerfiles, compose, empty apps with health | TODO | | |
| P1 | Flyway V1/V2 both DBs, roles, RLS, `TenantTx`, `RlsIT` | TODO | | |
| P2 | `core` (§7) + unit tests + `CoreIsPureTest` + `OrderIndependenceTest` + `ReplayIdempotenceTest` | TODO | | |
| P3 | Ingest (§8): single, batch, transport PATCH, admin DELETE, history, outbox, error contract | TODO | | |
| P4 | booking-service consumer + booking API (§11) | TODO | | |
| P5 | Relay (§10) + `StubBookingServer` ITs | TODO | | |
| P6 | Security (§12): issuer, JWKS, resource servers, authz matrix, tenant 404s | TODO | | |
| P7 | Reconciliation (§9) incl. approval, startup recovery | TODO | | |
| P8 | Caching, metrics, structured logs, PII guard, faults bean, dev seeders | TODO | | |
| P9 | Concurrency ITs (§17.3), run 3x consecutively | TODO | | |
| P10 | `docker-compose.faults.yml` + fault scripts (§18) | TODO | | |
| P11 | Benchmarks (§19): generator, `ReconciliationBenchmarkIT`, k6, contention run | TODO | | |
| P12 | Observability profile: prometheus.yml, alerts.yml, grafana | TODO | | |
| P13 | Docs: README, design-note, decisions, benchmark.md, Postman, demo scripts | TODO | | |
| — | Tag `v1.0` | TODO | | |

## Open issues

(see `docs/known-issues.md`)

## Pre-flight notes (step 2, before any code)

- `docs/spec/case-study-5.pdf` is absent from the repo; proceeded on `AGENT_BUILD_SPEC.md` (wins on conflict) + `case5_hr_roster_sync_plan.md` only. Logged in `docs/known-issues.md`.
- Resolved spec/plan contradictions in favor of the spec: bucket digest includes `int8send(seq)`; "missing" query is bucket-scoped not full-table; reconciliation status machine uses spec's exact states (incl. `STAGING`); `HrEventRequest`/`HrEventKind` unified record (not plan's sealed `HrEvent` hierarchy); richer `reconciliation_run` schema; exactly 3 Caffeine caches (no JWKS cache).
- Version pins (Boot 4.1.2, Jackson 3, Testcontainers 2.x non-generic `PostgreSQLContainer`) not yet verified against Maven Central — will verify in P0 and apply spec §0.11 fallback (nearest same-minor patch) if needed.
