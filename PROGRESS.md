# PROGRESS

Tracks execution of `docs/spec/AGENT_BUILD_SPEC.md` §16 phases. Read this file first in every session.

Gate command (default): `./mvnw -q verify && scripts/check-tests.sh`

**Resume at P12.** P11 completed on the Linux machine with three measured runs and no warm-up (user override, 2026-10-31). Real measurements and raw outputs are in `docs/benchmark.md` and `bench/results/`. P12, P13 and `v1.0` remain pending.

| Phase | Deliverable | Status | Commit | Notes |
|---|---|---|---|---|
| P0 | Parent + 3 modules, wrapper, Surefire/Failsafe config, `scripts/check-tests.sh`, pinned images, docker/ init SQL, Dockerfiles, compose, empty apps with health | DONE | e489494 | verify + check-tests green (HealthIT 4/4); compose healthy in 9 s |
| P1 | Flyway V1/V2 both DBs, roles, RLS, `TenantTx`, `RlsIT` | DONE | 550f460 | RlsIT 6/6 (both DBs); bulk pool + `TenantTx` bulk variant wired (BulkPoolIT lands in P3 with the error contract) |
| P2 | `core` (§7) + unit tests + `CoreIsPureTest` + `OrderIndependenceTest` + `ReplayIdempotenceTest` | DONE | 37023cb | 10 unit classes, 70 tests; mutation of tombstone floor caught at seed 1; `FaultInjectorTest` lands with the faults bean in P8 |
| P3 | Ingest (§8): single, batch, transport PATCH, admin DELETE, history, outbox, error contract | DONE | 707b0aa | 8 IT classes / 39 tests + BulkPoolIT; tenant via `X-Tenant-Id` stand-in until P6; `BriefExampleIT.fileCutAt120k…` lands with reconciliation (P7) |
| P4 | booking-service consumer + booking API (§11) | DONE | 1e47790 | ConsumerIT 5, BookingApiIT 4, SeatAllocatorTest 3; caller via headers until P6 |
| P5 | Relay (§10) + `StubBookingServer` ITs | DONE | 9853361 | RelayIT 10/10, RetryPolicyTest 4; service token supplied once P6 adds `TokenService` |
| P6 | Security (§12): issuer, JWKS, resource servers, authz matrix, tenant 404s | DONE | 4be40a3 | SecurityMatrixIT 80 tests (roster 51, booking 29); reconciliation rows join the matrix in P7 |
| P7 | Reconciliation (§9) incl. approval, startup recovery | DONE | 57751f7 | ReconciliationIT 24/24, BriefExampleIT 2/2, matrix 115 cells; lineage checked against COMPLETED runs (known-issues #18) |
| P8 | Caching, metrics, structured logs, PII guard, faults bean, dev seeders | DONE | 5a2c1c4 | NoPiiIT, CacheIT, MetricsIT (x2), FaultInjectorTest (x2) green; compose seeding verified end to end |
| P9 | Concurrency ITs (§17.3), run 3x consecutively | DONE | a0cb156 | 9 concurrency ITs green in 3 consecutive runs (full gate + 2 targeted runs); suite ≈ 30 s |
| P10 | `docker-compose.faults.yml` + fault scripts (§18) | DONE | 78fa078 | All 6 scenarios PASS 3/3; 18 logs and summary in docs/fault-runs/. Fixed rollback masking DB timeouts (4f9b105); full gate 313 tests, 42 required classes green |
| P11 | Benchmarks (§19): generator, `ReconciliationBenchmarkIT`, k6, contention run | DONE | pending phase commit | Full runner exited zero; all six scenarios × three measured runs, no warm-up, 256 MiB cap; four 60s API rates and contention passed. Normal gate 314 tests / 42 classes. Queue metrics starvation fixed in 57a766f; raw results/report committed with this phase. |
| P12 | Observability profile: prometheus.yml, alerts.yml, grafana | TODO | | |
| P13 | Docs: README, design-note, decisions, benchmark.md, Postman, demo scripts | TODO | | |
| — | Tag `v1.0` | TODO | | |

## Open issues

(see `docs/known-issues.md`)

## Pre-flight notes (step 2, before any code)

- `docs/spec/case-study-5.pdf` was added by the user during P0; it matches the plan's §1 audit.
- Resolved spec/plan contradictions in favor of the spec: bucket digest includes `int8send(seq)`; "missing" query is bucket-scoped not full-table; reconciliation status machine uses spec's exact states (incl. `STAGING`); `HrEventRequest`/`HrEventKind` unified record (not plan's sealed `HrEvent` hierarchy); richer `reconciliation_run` schema; exactly 3 Caffeine caches (no JWKS cache).
- Versions verified in P0: Boot 4.1.2, commons-csv 1.14.1, Testcontainers 2.0.5 all resolve; no fallback needed.

## Environment (this machine)

- JDK: Homebrew `openjdk@21`; Docker: Colima VM (4 CPU / 8 GB). Testcontainers needs
  `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.
- Commits: several logical, green commits per phase; the last one in each phase carries the exact §16 message.
