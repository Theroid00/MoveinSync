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

Pulled at P0 (2026-10-30, linux/arm64 via Colima), digests from `docker image inspect --format '{{index .RepoDigests 0}}'`. Compose and the Dockerfiles reference these exact tags.

| Image | Tag | Digest | Used by |
|---|---|---|---|
| `postgres` | `18.6` | `sha256:979bccfde35445db618c65107d27d08c6c21339d8dc3ad4c830c6633947be0e4` | compose |
| `eclipse-temurin` | `21.0.13_9-jdk-noble` | `sha256:088afe113afcf30db6e0919cca3cded8666e10b90c876c4a317ee4318f532f4b` | Dockerfile build stage |
| `eclipse-temurin` | `21.0.13_9-jre-noble` | `sha256:03fedab2c0d7355868b5ba5330d64088b956a0bfac48f904e5c57207e261e2c9` | Dockerfile runtime stage |
| `grafana/k6` | `1.8.2` | `sha256:00fdcebafbc86cb0137d429529b1caa401a02e09025f02fc74866fa6e2b004d8` | compose profile `bench` (P11) |
| `prom/prometheus` | `v3.15.1` | `sha256:fa3302279710be2f21a8301ec17a8b4f2301fd829bd113839b3b6b02b6f191a2` | compose profile `observability` (P12) |
| `grafana/grafana` | `12.4.14` | `sha256:ae509d9ae8c1021ca32ec370a9ca975d40165eda9fccd1e1102d317ff8787cd7` | compose profile `observability` (P12) |

- Testcontainers uses `postgres:18` as §17 requires. At P0 that tag resolved to the same digest as `18.6`.
- `grafana/k6` 2.x exists; 1.8.2 is pinned to match the spec's `1.x.y` example and the k6 1.x script API.
- `grafana/grafana` 13.0 exists; 12.4.14 (latest 12.4 patch) is pinned for dashboard-provisioning stability.

## Spec ambiguities resolved (the spec wins over the plan; these are gaps inside the spec)

1. **`BucketerTest` range.** §17.1 says "range 0..1023", which contradicts §7.3 and DEC-21 (65,536 buckets). The test asserts 0..65535.
2. **Same-cutoff lineage timing (§9.1).** The file's SHA-256 is known once the upload has been streamed to disk, which happens before the 202 response. The duplicate/conflict rule is therefore checked synchronously at upload (409 `SNAPSHOT_CONFLICT` as `sameCutoffDifferentFileConflicts` expects), again at the end of staging, and again under the lock in §9.3.
3. **`assessment_hash` encoding (§9.3 step 4)** is not byte-specified. Defined as `SHA-256("S|baseline|missing|" + hex(sha256(missing ids joined by ',' in id order)))`. The inner hash is computed in SQL, the outer in Java (`SnapshotApplier.assessmentHash`).
4. **Approving a run whose re-entry to `STAGED` collides with another active run** (`one_active_run` index) → 409 `RUN_IN_PROGRESS`.
5. **No `tenant_source` row for the tenant** at ingest → 404 `NOT_FOUND`.
6. **Bulk pool and the role `statement_timeout`.** `ALTER ROLE roster_app SET statement_timeout = '5s'` would also apply to the bulk pool, so the bulk Hikari pool sets `statement_timeout = '300s'` per connection (`connection-init-sql`), in addition to the `SET LOCAL` in §9.3.
7. **One `ErrorCode` catalog** lives in `event-contract` (plain Java) so both services share it. `SecurityMatrixIT` and `ErrorContractIT` exist in both modules: booking-only cells and codes (`TRIP_FULL`, `EMPLOYEE_INACTIVE`) need the booking app.
8. **`EVENT_TYPE_REQUIRED`** is an added validation code: §7.2 has no code for a missing `eventType` (an unknown enum value is a JSON error; a missing one reaches the validator).
9. **Validation field names** are the logical names (`eventId`, `employeeId`, `sourceSeq`, `effectiveAt`, `eventType`, `hr`, `name`, `email`, `phone`, `managerId`, `officeCode`, `shiftCode`) for both API events and snapshot rows.
10. **Single-event 409/422 bodies** are ProblemDetails that also carry the §8 result fields (`eventId`, `employeeId`, `outcome`, `rejectionId`, `errors`), satisfying both §8 and the §13.1 error contract.
11. **Transport PATCH replaces both transport fields** (the spec's SQL sets both columns); `null` clears a value. `If-Match` accepts `5`, `"5"` or `W/"5"`; missing → 422 `IF_MATCH_REQUIRED`.
12. **JSON shape errors** (wrong type, unknown enum) are 400 `MALFORMED_JSON` with the property path; unknown properties are 422 `UNKNOWN_FIELD` with the dotted path (e.g. `hr.pickupPoint`). Field names only, never values.
13. **Batch and `RECONCILIATION_IN_PROGRESS`:** a lock timeout during a batch line propagates as 503 for the whole request. Lines already processed are committed, and a retry is absorbed by event identity.
14. **JDBC exception translation:** Spring 6+ defaults `JdbcTemplate` to the SQLSTATE-subclass translator, which leaves PostgreSQL `55P03` (lock_timeout) uncategorised. Our `JdbcClient`s use `SQLErrorCodeSQLExceptionTranslator`, so it becomes `CannotAcquireLockException` → 503.
15. **SERVICE column of the roster matrix:** the relay's real service token has `aud = ["booking"]` (§10), so roster rejects it with 401 before any role check. The matrix's 403 cells are exercised with a SERVICE-role token carrying both audiences. `SecurityMatrixIT.wrongAudienceIs401` also proves the real service token cannot call roster.
16. **EMPLOYEE "self only"** on `GET /employees/{id}`: another employee's id in the same tenant is 403. Another tenant's employee is 404, as is any other tenant's resource. In booking, another employee's booking is 404 (no existence leak), and booking for someone else is 403.
17. **Booking test decoder:** §17's "test JwtDecoder bean" is a Spring Framework `@TestBean` that replaces the JWKS-backed decoder with one trusting a test keypair. It applies the same production validators (`SecurityConfig.validator()`).
18. **Same-cutoff lineage is checked against COMPLETED runs.** Spec §9.1 says "latest finished run with the same cutoff". Read literally, an upload rejected *for* `SNAPSHOT_CONFLICT` becomes the latest finished run, and a second upload of the same conflicting file would then be allowed. The rule is therefore implemented as: if any COMPLETED run exists for the cutoff, the same SHA is a no-op duplicate and a different SHA is a conflict; otherwise (none, or only partial/failed/rejected/incomplete runs) the upload is allowed. For every non-adversarial sequence this gives the same result. `sameCutoffDifferentFileConflicts` re-uploads the conflicting file to prove the gap is closed.
19. **`V3__staging_maintenance.sql`** grants `MAINTAIN` on `staging_employee` to `roster_app`. The app role is deliberately not the owner (RLS), and without MAINTAIN, `ANALYZE staging_employee` (§9.2) is skipped with a warning.
20. **Reconciliation `changedFields` (DEC-19)** are computed exactly, in SQL, using PostgreSQL 18's `RETURNING old.*/new.*`: the names of the HR fields that changed, `active` when it flips, and `PICKUP_REVIEW_NEEDED` on office change with a pickup point. The `["hr"]` fallback is not needed.
21. **Duplicate rows in a snapshot:** an exact duplicate is skipped. A conflicting duplicate marks the first occurrence invalid (`DUPLICATE_ROW_CONFLICT`), and the second is skipped, counted as invalid and reported. `parsed_rows` counts every data record (compared with `expectedRows`).
22. **Reject** is allowed only from `AWAITING_APPROVAL`, so it never races an apply in progress. Anything else is 409 `INVALID_RUN_STATE`.
23. **Startup recovery in tests:** application contexts are cached and shared across test classes, so `app.recon.recover-on-startup=false` in tests. `ReconciliationIT.startupRecoveryResumesStagedRun` invokes the recovery explicitly.
24. **The case-study PDF** was missing at first and added during P0 (moved to `docs/spec/case-study-5.pdf`).

## Colima + Testcontainers

Testcontainers needs to find the Colima socket:

```bash
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

Docker Desktop and Linux hosts need neither variable.

## Open limitations

- *(resolved in P6)* P3–P5 read the tenant from an `X-Tenant-Id` header as a build-order stand-in. Since P6 the tenant, roles and employee id come only from the validated JWT.
