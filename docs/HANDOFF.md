# Remaining work and next steps

Updated 2026-10-31. Read [PROGRESS.md](../PROGRESS.md) and follow [the build spec](spec/AGENT_BUILD_SPEC.md) in phase order.

P0–P12 are complete. Observability has six tested alert rules, two healthy scrape targets, a provisioned 12-panel dashboard and a saved rendered capture in `docs/validation/`. P10 has 18 passing fault runs. P11 has real PostgreSQL 200k measurements for all six scenarios (three measured runs each, no warm-up per the user’s instruction), all four API rates, and the contention run. See [benchmark results](benchmark.md) and [raw outputs](../bench/results/). The normal gate passed 314 tests and all 42 required classes. Scheduler starvation discovered under load was fixed and covered by a regression that fails under the original configuration.

## Next: P13 submission package

Complete README in spec §20 order, the short design note, DEC-01–23, Postman collection/environment, and `scripts/demo/run-all.sh` with assertions. Use accepted benchmark measurements and current test counts. Validate from a fresh checkout: normal gate, healthy/seeded Compose startup, and the full demo. Commit `docs: README, design note, decisions, postman` and update PROGRESS.

## Release and human handoff

Check every item in spec §21 before creating `v1.0`. The human records the demo video, replaces `VIDEO_LINK_TODO`, confirms public repository visibility, pushes commits and tag, reviews the version gate and reconciliation logic, and submits the links. No assessment message is sent automatically.

The benchmark runner resets this project’s Compose volumes. Use an isolated checkout. The Linux validation JDK and Docker group setup are recorded in [known issues](known-issues.md).
