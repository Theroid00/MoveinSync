# Release handoff

Updated 2026-10-31. P0–P13 are complete. [PROGRESS.md](../PROGRESS.md) records the phase commits; [release checks](validation/release-checklist.md) link the evidence.

## Completed

- Final main install and isolated-checkout verification: **315 tests**, zero failures/errors/skips; all **43 required classes** executed.
- P11: six real PostgreSQL 200k scenarios, three measured runs each with **no warm-up** per the user instruction, a 256 MiB JVM cap, exact mutation assertions and timing bounds. All four 60-second API rates and contention completed; [raw measurements](../bench/results/) and [report](benchmark.md) are committed.
- P12: both Prometheus targets UP, six healthy/tested alert rules, a live safety-brake alert, 12 rendered Grafana panels and successful datasource/query checks. A temporary renderer supplied the capture and was removed.
- P13: README/53-item coverage audit, concise design note, DEC-01–23, 29-request Postman collection/environment and auth self-check, full automated demo. Fresh-checkout demo proved both real commit crashes and the exact 200k baseline / 120k export / 80k missing / zero-mutation brake. The stack was restored to seeded data.

## Human actions

1. Record the demo video and replace `VIDEO_LINK_TODO` in README.
2. Publish the intended repository and push the commits and annotated `v1.0` tag. Unauthenticated GitHub API access currently returns 404, so public visibility is not established. Confirm visibility while logged out.
3. Review the source-version gate and snapshot apply logic and rehearse the ownership questions in the plan.
4. Submit repository and video links through the assessment channel.

No publication, permission change or assessment message is sent automatically.

## Run again

Use JDK 21 including javac, Docker/Compose, bash, curl, jq and Python 3. The Linux JDK is `/home/theroid/.local/share/jdks/temurin-21.0.13`; select it with `JAVA_HOME` and put its bin directory on PATH. This session uses `sg docker` for the account’s existing Docker-group membership.

The fault, benchmark and demo runners reset this project’s Compose volumes. Use an isolated test checkout. See [benchmark instructions](../bench/README.md), [Postman instructions](../postman/README.md), [observability](../ops/README.md), and [known limits](known-issues.md). The relay backlogs at 1,000 HTTP events/s even while ingest stays fast; dedup/history/upload retention still needs an operational policy.
