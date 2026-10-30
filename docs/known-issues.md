# Known issues, deviations and environment notes

Anything that deviates from `docs/spec/AGENT_BUILD_SPEC.md`, any version fallback, and any limitation found while building.

## Environment

- **Build host:** Apple Silicon MacBook Air (10 cores, 16 GB), macOS 27.0.1. The host had no JDK and no Docker at the start of P0. Installed with Homebrew (no sudo): `openjdk@21`, `colima`, `docker`, `docker-compose`, `docker-buildx`. Docker runs inside a Colima VM. See "Colima + Testcontainers" below for the required environment variables.
- **Java:** Homebrew `openjdk@21`, not Temurin as §1 suggests. It is the same OpenJDK 21 codebase; the Docker images use `eclipse-temurin` as the spec intends.

## Version resolution (spec §0.11)

| Item | Spec | Resolved | Note |
|---|---|---|---|
| Spring Boot parent | 4.1.2 | 4.1.2 | resolved from Maven Central, no fallback |
| commons-csv | 1.14.1 | 1.14.1 | resolved, no fallback |
| Maven (wrapper) | 3.9 | 3.9.16 | wrapper 3.3.4, `only-script` distribution |

## Pinned images

Filled in at P0 from `docker image inspect --format '{{index .RepoDigests 0}}'`.

| Image | Tag | Digest |
|---|---|---|

## Spec ambiguities resolved (the spec wins over the plan; these are gaps inside the spec)

1. **`BucketerTest` range.** §17.1 says "range 0..1023", which contradicts §7.3 and DEC-21 (65,536 buckets). The test asserts 0..65535.
2. **Same-cutoff lineage timing (§9.1).** The file's SHA-256 is known once the upload has been streamed to disk, which happens before the 202 response. The duplicate/conflict rule is therefore checked synchronously at upload (409 `SNAPSHOT_CONFLICT` as `sameCutoffDifferentFileConflicts` expects), again at the end of staging, and again under the lock in §9.3.
3. **`assessment_hash` encoding (§9.3 step 4)** is not byte-specified. It is defined in `ReconciliationRepository` and documented there; SQL and Java use the same encoding.
4. **Approving a run whose re-entry to `STAGED` collides with another active run** (`one_active_run` index) → 409 `RUN_IN_PROGRESS`.
5. **No `tenant_source` row for the tenant** at ingest → 404 `NOT_FOUND`.
6. **Bulk pool and the role `statement_timeout`.** `ALTER ROLE roster_app SET statement_timeout = '5s'` would also apply to the bulk pool, so the bulk Hikari pool sets `statement_timeout = '300s'` per connection (`connection-init-sql`), in addition to the `SET LOCAL` in §9.3.
7. **One `ErrorCode` catalog** lives in `event-contract` (plain Java) so both services share it. `SecurityMatrixIT` and `ErrorContractIT` exist in both modules: booking-only cells and codes (`TRIP_FULL`, `EMPLOYEE_INACTIVE`) need the booking app.
8. **The case-study PDF** was missing at first and added during P0 (moved to `docs/spec/case-study-5.pdf`).

## Colima + Testcontainers

Testcontainers needs to find the Colima socket:

```bash
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

Docker Desktop and Linux hosts need neither variable.

## Open limitations

(none yet)
