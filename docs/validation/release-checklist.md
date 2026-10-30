# Release acceptance

Validated 2026-10-31 on the Linux machine described in the benchmark report. P11 uses the user-requested no-warm-up protocol in place of the original warm-up requirement.

| Build-spec §21 item | Evidence / outcome |
|---|---|
| Clean checkout unit/integration/concurrency verification | `p13-fresh-demo.txt`; final current-source gate `final-fresh-checkout.txt`: 315 tests, 0 failures/errors/skips |
| Healthy and seeded Compose startup | Fresh-checkout demo and final stack: 300 future / 20 past bookings; both services healthy |
| Automated demo | `p13-demo.txt` and `p13-fresh-demo.txt`: PASS, including both real commit-crash tests and exact 200k/120k brake |
| Fault suite 3/3 for six scenarios | [18 committed PASS logs](../fault-runs/SUMMARY.txt); two commit-crash scenarios repeated in both Linux demos |
| Full benchmark execution and actual results | [Full runner output](../../bench/results/run-all.txt), six scenario JSONs, API and contention samples; [generated report](../benchmark.md) |
| Typical/heavy bound and bounded JVM | Exact outcomes and <60s digest+assessment+apply assertions passed at 256 MiB; three measured runs, no warm-up |
| Observability | `p12-promtool.txt`, live brake/alert evidence, `dashboard.png`, `final-observability.txt`: two UP targets, six healthy rules and all dashboard queries valid |
| Required test execution / no disabled tests | Final manifest: 43 classes, all green; source scan found no disabled tests |
| PII protection | NoPiiIT passed in both services as part of final full gates; error/outbox/log canary checks retained |
| Submission artifacts | README, 53-item audit, design note with architecture/sequence diagrams, 23 decisions, benchmark report, Postman collection/environment, automated demo; only video placeholder remains |
| Phase history and release | Exact phase messages are recorded in PROGRESS; annotated local v1.0 follows all local gates |

## Remaining human work

Record the video, replace the README placeholder, push commits/tag, establish public visibility, review the implementation and submit the assessment links. The unauthenticated repository API returned 404 during validation; no repository permissions were changed.

## Limits retained

The per-tenant reconciliation lock increases concurrent API latency; measured overlapping p99 was about 1.1 seconds. The HTTP relay accumulates backlog at 1,000/s; pending depth and age accompany delivered-event quantiles. Dedup and audit history are retained indefinitely and uploaded files need an operational retention policy. Postman scripts were checked offline; the complete HTTP flows were verified through the automated demo and integration suite.
