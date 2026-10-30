# Project: MoveInSync Case Study 5 — HR Roster Sync

Submission-ready Java 21 / Spring Boot 4.1.x backend for a hiring assessment. Two services (`roster-service`, `booking-service`) plus a shared `event-contract` module, PostgreSQL 18, Docker Compose.

## Sources of truth (read before doing anything)

1. `docs/spec/AGENT_BUILD_SPEC.md`: **executable spec; wins on any conflict.**
2. `docs/spec/case5_hr_roster_sync_plan.md`: design rationale, decision log and coverage audit.
3. `docs/spec/case-study-5.pdf`: the original brief.
4. `PROGRESS.md`: current phase status. **Read it first in every session.**

## How to work

- Execute phases **P0–P13 from spec §16 strictly in order**. Never start a phase until the previous phase's gate is green.
- For each phase:
  1. Implement exactly what the spec says, including the tests listed for it in spec §17.
  2. Run the gate command (default `./mvnw -q verify`, plus any phase-specific gate) and show the result.
  3. Commit with the **exact** commit message from spec §16.
  4. Update `PROGRESS.md`: mark the phase DONE with the commit hash, and note any open issues.
- Keep going phase after phase without waiting for the human, unless a gate can't be made green after 3 genuine attempts. Then stop and explain what you tried.
- At the end, verify every item of the Definition of Done (spec §21) and report the evidence for each.

## Non-negotiable rules (full list: spec §0)

- **Never** `@Disabled`, delete, skip or weaken a test. Never add `Thread.sleep` to make a race pass. Never catch-and-ignore to get green. Fix the code.
- **Never fabricate numbers.** Benchmark and fault-run results must be pasted from real output files. If something wasn't run, write `NOT RUN`.
- **Forbidden:** H2 or any in-memory DB, Lombok, jqwik, WireMock, Spring Data JPA/Hibernate, Kafka or any broker, Resilience4j, Spring Authorization Server.
- **Integration tests use Testcontainers `postgres:18` only**, connecting as the **app role** via `@DynamicPropertySource`. Never use `@ServiceConnection` for the datasource; the superuser bypasses Row-Level Security.
- **Personal data** (name, email, phone, pickup point, special assistance) must never appear in logs, exception messages, error responses, metric labels or outbox payloads. Use IDs only.
- `core` packages are pure Java: no `org.springframework.*`, `java.sql.*`, `javax.sql.*` or `jakarta.*` imports (enforced by `CoreIsPureTest`).
- All SQL lives in repository classes (`JdbcClient`), Flyway migrations or test assertions.
- Java records for DTOs and value objects; constructor injection only.
- Don't rename packages, endpoints, tables, metrics, error codes or test classes defined in the spec.

## Spring Boot 4 traps (details: spec §2.1)

- Use the modular **starters** (`spring-boot-starter-flyway` + `flyway-database-postgresql`, `spring-boot-starter-webmvc`, `spring-boot-starter-webmvc-test`, etc.). If Flyway doesn't run, the Flyway starter is missing.
- Jackson 3 packages are `tools.jackson.databind.*`. Inject the Boot-configured mapper.
- Testcontainers 2: artifact `testcontainers-postgresql`, class `org.testcontainers.postgresql.PostgreSQLContainer`.
- Build `RestClient` manually with `JdkClientHttpRequestFactory` and explicit timeouts.
- The Postgres 18 Docker volume mounts at `/var/lib/postgresql` (not `/var/lib/postgresql/data`).
- A version that doesn't resolve → use the nearest patch of the same minor and log it in `docs/known-issues.md`. Never change the major version.

## When stuck

Same error 3 times → record it in `docs/known-issues.md` (what failed, what you tried), apply the spec's fallback if one exists, otherwise stop and ask.

## Common commands

```bash
./mvnw -q verify                                  # unit + integration + concurrency tests
./mvnw -pl roster-service verify -Pbenchmark       # benchmarks (P11)
docker compose up -d --build                      # postgres + roster (8080) + booking (8081), seeded
docker compose --profile observability up -d      # + prometheus (9090) + grafana (3000)
scripts/demo/run-all.sh                           # end-to-end demo / smoke test
scripts/faults/run-all.sh                         # process-level failure injection (P10)
scripts/bench/run-all.sh                          # full benchmark suite (P11)
docker compose down -v                            # reset everything
```

## Definition of done (summary; full list in spec §21)

`./mvnw verify` green from a clean clone · `docker compose up --build` healthy and seeded · demo, fault and benchmark scripts pass with real outputs committed · every test named in spec §17 exists and passes · no `@Disabled` anywhere · `NoPiiIT` green · README, design note, decisions, benchmark and Postman collection complete (only `VIDEO_LINK_TODO` remains) · phase commits in order · tag `v1.0`.
