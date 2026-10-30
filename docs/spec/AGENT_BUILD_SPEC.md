# AGENT BUILD SPEC — Case 5: HR Roster Sync

You are building a complete, submission-ready project from this spec. It is the **executable** companion to `case5_hr_roster_sync_plan.md` (the design rationale). **When the two disagree, this spec wins.** Every choice is already made; do not substitute libraries, rename things or "simplify" requirements away.

---

## 0. Rules (read first, obey throughout)

1. **Read this whole file and the plan before writing any code.**
2. **Work phase by phase (§16).** After every phase run its gate command. Do not start the next phase until the gate is green. Then commit with the given message.
3. **Never** use `@Disabled`, delete a failing test, weaken an assertion, add `Thread.sleep` to make a race pass, or catch-and-ignore to get green. Fix the code.
4. **Never fabricate numbers.** Benchmark and fault-run results in docs must come from actual runs, pasted from output files. If something wasn't run, write `NOT RUN`.
5. **No H2, no in-memory DB, no mocked repositories in integration tests.** Every `*IT` uses Testcontainers `postgres:18`.
6. **Forbidden dependencies:** Lombok, jqwik, WireMock, Kafka/any broker, Spring Data JPA/Hibernate, Spring Authorization Server, Resilience4j. (Reasons: annotation-processing and JUnit-platform friction on Boot 4; jqwik 1.10 also ships an anti-AI-agent clause; JPA hides the SQL this design depends on.)
7. **Personal data** (name, email, phone, pickup point, special assistance) must never appear in logs, exception messages, error responses, metrics labels or outbox payloads. Use IDs.
8. **`core` packages are pure Java:** no `org.springframework.*`, `java.sql.*`, `javax.sql.*` or `jakarta.*` imports. A test enforces this.
9. Use Java **records** for DTOs and value objects. Constructor injection only.
10. All SQL lives in repository classes (`JdbcClient`), except Flyway migrations and test assertions.
11. If a version in §2 fails to resolve, use the nearest available patch of the **same minor** and note it in `docs/known-issues.md`. Never change the major version.
12. Keep commits small and meaningful (§16 lists the messages). History must show the build progression.

---

## 1. Prerequisites (host)

Docker Engine + Docker Compose v2, Java 21 (Temurin), bash, curl, jq, git. Testcontainers needs a running Docker daemon. Scripts assume Linux/macOS (or WSL).

---

## 2. Stack and versions

| Item | Version / artifact |
|---|---|
| Java | 21 (`maven.compiler.release=21`) |
| Spring Boot parent | `4.1.2` (fallback: latest `4.1.x`) |
| Build | Maven 3.9 via Maven Wrapper (`./mvnw`), multi-module |
| PostgreSQL | Docker `postgres:18` |
| JDBC driver | `org.postgresql:postgresql` (Boot-managed) |
| CSV | `org.apache.commons:commons-csv:1.14.1` (fallback: latest `1.14.x`) |
| Cache | `com.github.ben-manes.caffeine:caffeine` (Boot-managed) |
| Metrics | `io.micrometer:micrometer-registry-prometheus` (Boot-managed) |
| JWT | via `spring-boot-starter-oauth2-resource-server` (Nimbus JOSE included) |
| Tests | `spring-boot-starter-test`, `spring-boot-testcontainers`, `org.testcontainers:testcontainers-postgresql`, `org.testcontainers:testcontainers-junit-jupiter` (Boot-managed; **Testcontainers 2.x artifact names**), `org.awaitility:awaitility` (Boot-managed) |
| Load test | Docker `grafana/k6:<pinned>` |
| Observability | Docker `prom/prometheus:<pinned>`, `grafana/grafana:<pinned>` |

**Image pinning (P0):** never use `latest`. At P0, `docker pull` each image, choose the current stable version tag (e.g. `grafana/k6:1.x.y`), and write the exact tags into `docker-compose.yml`. Record them, with digests (`docker image inspect --format '{{index .RepoDigests 0}}'`), in `docs/known-issues.md` under "Pinned images".

### 2.2 Maven test execution (must be configured explicitly in the parent `pom.xml`)

Naming tests `*IT` does **not** make `./mvnw verify` run them. Configure in the parent `<build><pluginManagement>` and apply in every module:
- **Surefire** (`test` phase): includes `**/*Test.java`; `<excludedGroups>benchmark</excludedGroups>`.
- **Failsafe**: `<executions><execution><goals><goal>integration-test</goal><goal>verify</goal></goals></execution></executions>`; includes `**/*IT.java`; `<excludedGroups>benchmark</excludedGroups>`.
- **Profile `benchmark`**: Failsafe `<groups>benchmark</groups>`, `<excludedGroups/>` (empty), `<argLine>-Xmx256m -XX:+UseG1GC</argLine>`.
- **Both plugins:** `<failIfNoSpecifiedTests>false</failIfNoSpecifiedTests>` is **not** allowed. A filter matching nothing must fail.
- **`scripts/check-tests.sh`** (bash + `xmllint` or `grep`): reads every `target/surefire-reports/TEST-*.xml` and `target/failsafe-reports/TEST-*.xml`, and checks that **every test class named in §17** appears with `tests > 0`, `failures = 0`, `errors = 0`, `skipped = 0`. It prints missing classes and exits non-zero on any gap. The list of required classes lives in `scripts/required-tests.txt` (one fully qualified simple class name per line, maintained as phases add tests).
- **Every phase gate** = `./mvnw -q verify && scripts/check-tests.sh`.

### 2.1 Spring Boot 4 traps (avoid these)

- **Modular starters:** Boot 4 split auto-configuration into modules. Use the **starters** so auto-config is present:
  - `spring-boot-starter-webmvc` (if unresolvable, `spring-boot-starter-web`)
  - `spring-boot-starter-jdbc`, `spring-boot-starter-flyway` **plus** `org.flywaydb:flyway-database-postgresql`
  - `spring-boot-starter-validation`, `spring-boot-starter-actuator`
  - `spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server`, `spring-boot-starter-cache`
  - Tests: `spring-boot-starter-webmvc-test` for MockMvc
  - If Flyway doesn't run on startup, the Flyway starter is missing.
  - Easiest correct path: generate each service's `pom.xml` dependency block from start.spring.io (Boot 4.1.x, Java 21, Maven, deps: `web, jdbc, flyway, postgresql, validation, actuator, prometheus, security, oauth2-resource-server, cache, testcontainers`), then add the non-Boot deps above.
- **Jackson 3:** packages are `tools.jackson.databind.*` (not `com.fasterxml.jackson.databind.*`). Inject the Boot-configured `JsonMapper`/`ObjectMapper`; don't `new` one.
- **Testcontainers 2:** `org.testcontainers.postgresql.PostgreSQLContainer` (non-generic). Artifact `testcontainers-postgresql`.
- **Do not use `@ServiceConnection` for the datasource.** It would connect as the superuser, which bypasses Row-Level Security. Use `@DynamicPropertySource` with the app role (§17.2).
- **RestClient:** build it manually (`RestClient.builder().requestFactory(...)`) with `JdkClientHttpRequestFactory` and explicit timeouts. Don't rely on RestClient auto-config.
- **Postgres 18 Docker volume path:** mount the volume at `/var/lib/postgresql` (**not** `/var/lib/postgresql/data`).

---

## 3. Repository layout

```
roster-sync/
├── pom.xml                              # parent (packaging pom), modules below
├── mvnw, mvnw.cmd, .mvn/
├── event-contract/                      # plain jar: shared event envelope records (no Spring)
├── roster-service/                      # Spring Boot app, port 8080
├── booking-service/                     # Spring Boot app, port 8081
├── docker/
│   ├── postgres/init/01-roles-and-dbs.sql
│   ├── Dockerfile.roster
│   └── Dockerfile.booking
├── docker-compose.yml                   # postgres, roster, booking (+ profiles: observability, bench)
├── docker-compose.faults.yml            # overrides used by fault scripts
├── ops/prometheus.yml, ops/alerts.yml, ops/grafana/provisioning/**, ops/grafana/dashboards/roster.json
├── scripts/
│   ├── lib.sh                           # token(), psql_roster(), psql_booking(), wait_healthy(), post_json()
│   ├── demo/*.sh                        # scripted demo flows (§19)
│   ├── faults/*.sh + run-all.sh         # §13
│   └── bench/run-all.sh                 # §14
├── k6/ingest.js
├── postman/collection.json, postman/environment.json
├── bench/results/                       # committed raw outputs (JSON/CSV/TXT)
├── docs/design-note.md, decisions.md, benchmark.md, fault-runs/, known-issues.md
└── README.md
```

**Packages:** root `com.moveinsync.rostersync`. `roster-service` uses `...roster.{api, core, service, repository, outbox, security, cache, observability, config, fault, dev}`. `booking-service` uses `...booking.{api, core, service, repository, consumer, security, observability, config, fault, dev}`. `event-contract` uses `...contract`.

`event-contract` is a plain jar (no Boot plugin). The services depend on it. **Do not** make one Boot app depend on another Boot app module.

---

## 4. Databases, roles and Row-Level Security

One Postgres container, **two databases** (`roster`, `booking`), and per database an **owner** role (runs Flyway, owns tables) plus an **app** role (runtime; no `BYPASSRLS`, not owner).

`docker/postgres/init/01-roles-and-dbs.sql` (runs once at container init as superuser):

```sql
CREATE ROLE roster_owner LOGIN PASSWORD 'roster_owner_pw';
CREATE ROLE roster_app   LOGIN PASSWORD 'roster_app_pw';
CREATE ROLE booking_owner LOGIN PASSWORD 'booking_owner_pw';
CREATE ROLE booking_app   LOGIN PASSWORD 'booking_app_pw';
CREATE DATABASE roster  OWNER roster_owner;
CREATE DATABASE booking OWNER booking_owner;
\connect roster
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO roster_app;
ALTER DEFAULT PRIVILEGES FOR ROLE roster_owner IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO roster_app;
ALTER DEFAULT PRIVILEGES FOR ROLE roster_owner IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO roster_app;
\connect booking
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO booking_app;
ALTER DEFAULT PRIVILEGES FOR ROLE booking_owner IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO booking_app;
ALTER DEFAULT PRIVILEGES FOR ROLE booking_owner IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO booking_app;
```

Datasource = app role; Flyway = owner role (`spring.flyway.user/password`). Append these default timeouts **to the same init script** (owners can't alter roles from migrations):

```sql
ALTER ROLE roster_app SET statement_timeout = '5s';
ALTER ROLE booking_app SET statement_timeout = '5s';
```

**RLS policy pattern** (in migrations, for every tenant table listed in §5 as `RLS`):

```sql
ALTER TABLE employee ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON employee
  USING (tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
```

- **Setting the tenant:** every transaction that touches RLS tables starts with `SELECT set_config('app.tenant_id', :tenantId, true)` (transaction-local). Implement once in `TenantTx` (roster) / `BookingTenantTx` (booking): `<T> T inTenant(String tenantId, TransactionCallback<T> cb)` built on `TransactionTemplate`. All services use it.
- **System tables without RLS** (internal, no PII, read cross-tenant by background workers): `outbox_event`, `processed_message`, `tenant_source`, `tenant_config`, `reconciliation_run` (filtered by `tenant_id` in SQL), `operator_alert`, `flyway_schema_history`. Every API read of these still filters by the token's tenant in SQL.

---

## 5. Schema (Flyway)

### 5.1 roster DB — `roster-service/src/main/resources/db/migration/V1__schema.sql`, `V2__rls.sql`

```sql
CREATE TABLE tenant_source (
  tenant_id text NOT NULL, source_id text NOT NULL,
  lock_key bigint NOT NULL UNIQUE,
  accepted_snapshot_cutoff bigint NOT NULL DEFAULT 0,
  absence_watermark bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (tenant_id, source_id));
CREATE TABLE tenant_config (tenant_id text PRIMARY KEY, brake_threshold_bp int NOT NULL DEFAULT 3000
  CHECK (brake_threshold_bp BETWEEN 1 AND 10000));
CREATE TABLE office (tenant_id text NOT NULL, office_code text NOT NULL, active boolean NOT NULL DEFAULT true,
  PRIMARY KEY (tenant_id, office_code));                                              -- RLS
CREATE TABLE employee (
  tenant_id text NOT NULL, source_id text NOT NULL, employee_id text NOT NULL,
  name text NOT NULL, email text NOT NULL, phone text NOT NULL, manager_id text,
  office_code text NOT NULL, shift_code text NOT NULL, active boolean NOT NULL,
  source_seq bigint NOT NULL CHECK (source_seq > 0), hr_hash bytea NOT NULL, bucket int NOT NULL CHECK (bucket BETWEEN 0 AND 65535),
  pickup_point text, special_assistance text,
  aggregate_revision bigint NOT NULL, tombstone_reason text,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, source_id, employee_id),
  FOREIGN KEY (tenant_id, office_code) REFERENCES office (tenant_id, office_code));   -- RLS
CREATE INDEX employee_bucket_active ON employee (tenant_id, source_id, bucket) WHERE active;
CREATE TABLE employee_tombstone (                -- deactivation seen for an employee we never had (order independence)
  tenant_id text NOT NULL, source_id text NOT NULL, employee_id text NOT NULL,
  source_seq bigint NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, source_id, employee_id));                                   -- RLS
CREATE TABLE ingest_event (
  id bigserial PRIMARY KEY, tenant_id text NOT NULL, source_id text NOT NULL, event_id text NOT NULL,
  payload_hash bytea NOT NULL, employee_id text, source_seq bigint, outcome text,
  error_codes text[], created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, source_id, event_id));                                           -- RLS
CREATE TABLE employee_history (
  id bigserial PRIMARY KEY, tenant_id text NOT NULL, employee_id text NOT NULL,
  source_seq bigint, aggregate_revision bigint, change text NOT NULL, origin text NOT NULL,
  run_id uuid, created_at timestamptz NOT NULL DEFAULT now());                        -- RLS
CREATE INDEX employee_history_emp ON employee_history (tenant_id, employee_id, id);
CREATE TABLE reconciliation_run (
  run_id uuid PRIMARY KEY, tenant_id text NOT NULL, source_id text NOT NULL,
  cutoff_seq bigint NOT NULL, exported_at timestamptz NOT NULL,
  file_path text NOT NULL, file_sha256 bytea, expected_rows int, expected_sha256 bytea,
  parsed_rows int NOT NULL DEFAULT 0, valid_rows int NOT NULL DEFAULT 0,
  invalid_rows int NOT NULL DEFAULT 0, unidentifiable_rows int NOT NULL DEFAULT 0,
  status text NOT NULL, flags text[] NOT NULL DEFAULT '{}',
  baseline int, missing int, ratio_bp int, assessment_hash bytea, missing_sample text[],
  buckets_mismatched int, approved_by text, approval_reason text, approved_at timestamptz,
  rejected_by text, counts jsonb NOT NULL DEFAULT '{}', phase_ms jsonb NOT NULL DEFAULT '{}',
  error_code text, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now());
CREATE UNIQUE INDEX one_active_run ON reconciliation_run (tenant_id, source_id)
  WHERE status IN ('UPLOADING','STAGING','STAGED','APPLYING');
CREATE TABLE staging_employee (
  run_id uuid NOT NULL, row_no int NOT NULL, employee_id text NOT NULL,
  name text, email text, phone text, manager_id text, office_code text, shift_code text,
  last_change_seq bigint, hr_hash bytea, bucket int, valid boolean NOT NULL, error_codes text[],
  PRIMARY KEY (run_id, employee_id));
CREATE INDEX staging_bucket ON staging_employee (run_id, bucket) WHERE valid;
CREATE TABLE record_error (
  id bigserial PRIMARY KEY, tenant_id text NOT NULL, run_id uuid, event_id text,
  rejection_id uuid,                      -- audit identity for rejected API records (eventId may be missing/invalid)
  row_no int, employee_id text, field text, code text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now());                                     -- RLS
CREATE TABLE operator_alert (
  id bigserial PRIMARY KEY, tenant_id text NOT NULL, kind text NOT NULL, run_id uuid,
  details jsonb NOT NULL, acknowledged_by text, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE outbox_event (
  id bigserial PRIMARY KEY, event_id uuid NOT NULL UNIQUE, tenant_id text NOT NULL,
  employee_id text NOT NULL, aggregate_revision bigint NOT NULL, event_type text NOT NULL,
  payload jsonb NOT NULL, state text NOT NULL DEFAULT 'PENDING',
  lease_token uuid, lease_until timestamptz, attempts int NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL DEFAULT now(), last_error_code text,
  created_at timestamptz NOT NULL DEFAULT now(), delivered_at timestamptz,
  UNIQUE (tenant_id, employee_id, aggregate_revision));
CREATE INDEX outbox_due ON outbox_event (next_attempt_at, id) WHERE state IN ('PENDING','RETRY');
CREATE INDEX outbox_claimed ON outbox_event (lease_until) WHERE state = 'CLAIMED';
```

`staging_employee` is not RLS-protected: it's keyed by `run_id`, and every access joins through `reconciliation_run` filtered by tenant. `V2__rls.sql` applies the §4 policy to `office`, `employee`, `employee_tombstone`, `ingest_event`, `employee_history`, `record_error`.

### 5.2 booking DB — `booking-service/.../db/migration/V1__schema.sql`, `V2__rls.sql`

```sql
CREATE TABLE employee_projection (tenant_id text NOT NULL, employee_id text NOT NULL,
  active boolean NOT NULL, last_revision bigint NOT NULL, effective_at timestamptz,
  PRIMARY KEY (tenant_id, employee_id));                                                    -- RLS
-- Composite (tenant_id, …) foreign keys: RLS filters rows but does NOT stop a cross-tenant reference.
CREATE TABLE trip (trip_id uuid PRIMARY KEY, tenant_id text NOT NULL, capacity int NOT NULL CHECK (capacity > 0),
  starts_at timestamptz NOT NULL, UNIQUE (tenant_id, trip_id));                             -- RLS
CREATE TABLE booking (booking_id uuid PRIMARY KEY, tenant_id text NOT NULL, employee_id text NOT NULL,
  trip_id uuid NOT NULL, scheduled_start timestamptz NOT NULL, status text NOT NULL,
  cancelled_reason text, cancelled_by_event uuid, created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, booking_id),
  FOREIGN KEY (tenant_id, trip_id) REFERENCES trip (tenant_id, trip_id));                   -- RLS
CREATE INDEX booking_emp ON booking (tenant_id, employee_id, status, scheduled_start);
CREATE TABLE seat_reservation (booking_id uuid PRIMARY KEY, tenant_id text NOT NULL,
  trip_id uuid NOT NULL, seat_no int NOT NULL, state text NOT NULL, released_at timestamptz,
  FOREIGN KEY (tenant_id, booking_id) REFERENCES booking (tenant_id, booking_id),
  FOREIGN KEY (tenant_id, trip_id) REFERENCES trip (tenant_id, trip_id));                   -- RLS
CREATE UNIQUE INDEX seat_active ON seat_reservation (trip_id, seat_no) WHERE state = 'ACTIVE';
CREATE TABLE processed_message (consumer text NOT NULL, event_id uuid NOT NULL, outcome text NOT NULL,
  processed_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (consumer, event_id));
```

---

## 6. Shared contracts (`event-contract`)

```java
public enum EventType { EmployeeCreated, EmployeeUpdated, EmployeeDeactivated, EmployeeReactivated,
                        EmployeeVersionAdvanced, EmployeeTransportUpdated }
public record EmployeeChangedEvent(UUID eventId, EventType eventType, String tenantId, String employeeId,
        long aggregateRevision, boolean active, Instant effectiveAt, List<String> changedFields) {}
public record ConsumeResult(String outcome) {}   // PROCESSED | DUPLICATE | SKIPPED_OLDER
```

The outbox `payload` jsonb **is** this record serialised. No PII fields exist in it. `changedFields` holds field **names** only.

---

## 7. Domain rules (pure `core`, roster-service)

### 7.1 Inbound HR event (API)

```java
public enum HrEventKind { UPSERT, DEACTIVATE }
public record HrRecord(String name, String email, String phone, String managerId,
                       String officeCode, String shiftCode) {}
public record HrEventRequest(String eventId, Long sourceSeq, HrEventKind eventType, String employeeId,
                             Instant effectiveAt, HrRecord hr) {}   // hr required for UPSERT, must be null for DEACTIVATE
```

- An `UPSERT` always means "employee exists and is active with exactly this HR state" (it reactivates a tombstone if the seq is higher). Deactivation happens **only** via `DEACTIVATE`.
- Unknown JSON properties → 422 `UNKNOWN_FIELD` (`spring.jackson.deserialization.fail-on-unknown-properties=true`). So an HR payload containing `pickupPoint` is rejected.

### 7.2 Validation (`RecordValidator` + `ValidationRule` subclasses)

`abstract class ValidationRule` with `Optional<FieldError> check(HrEventRequest r, ValidationContext ctx)`. `ValidationContext` provides `officeExists(code)` and `shiftExists(code)`.

| Code | Rule |
|---|---|
| `EVENT_ID_REQUIRED` | eventId non-blank, ≤100 chars |
| `EMPLOYEE_ID_FORMAT` | `^[A-Za-z0-9_-]{1,64}$` |
| `SEQ_NOT_POSITIVE` | sourceSeq ≥ 1 |
| `EFFECTIVE_AT_REQUIRED` | non-null |
| `HR_BLOCK_REQUIRED` / `HR_BLOCK_NOT_ALLOWED` | per kind |
| `NAME_REQUIRED` / `NAME_TOO_LONG` | 1..200 after trim |
| `EMAIL_FORMAT` | `^[^@\s]+@[^@\s]+\.[^@\s]+$`, ≤254 |
| `PHONE_E164` | `^\+[1-9]\d{7,14}$` |
| `OFFICE_UNKNOWN` | not in the tenant's active office catalog |
| `SHIFT_UNKNOWN` | not in {`MORNING`,`EVENING`,`NIGHT`} |
| `MANAGER_ID_FORMAT` / `MANAGER_SELF_REFERENCE` | optional; same format; ≠ employeeId |

`FieldError(String field, String code)`. **Never include the value.**

### 7.3 Canonicalisation and hashing (`CanonicalHasher`)

- Normalise:
  - `name`: Unicode NFC → trim → collapse internal whitespace to a single space.
  - `email`: trim → lower-case (`Locale.ROOT`).
  - `phone`, `officeCode`, `shiftCode`: trim.
  - `managerId`: trim; empty → null.
- Canonical string = fields joined with `\u001F` in this exact order: `employeeId, name, email, phone, managerId, officeCode, shiftCode`. Null → `\u0000`.
- `hrHash = SHA-256(canonical UTF-8 bytes)` (32 bytes).
- `payloadHash` (event identity) = SHA-256 of the canonical string + `\u001F` + kind + `\u001F` + sourceSeq + `\u001F` + effectiveAt (ISO-8601).
- `bucket(employeeId) = (b[0] & 0xFF) << 8 | (b[1] & 0xFF)`, where `b = SHA-256(employeeId UTF-8)`. Range **0..65535 (65,536 buckets)**.
  - Why 65,536 and not 1,024: with 10,000 changed IDs, 1,024 buckets would all be touched. With 65,536, about `1 − e^(−10000/65536)` ≈ 14% are touched, so the bucket filter actually saves work.
  - 65,536 digests × 32 bytes ≈ 2 MB per side: still bounded.
- **Digest element** (§9.3) = `employeeId UTF-8 || hrHash || int8(seq, big-endian)`. The version is part of the digest, so a snapshot with identical HR fields but a higher seq is detected.

### 7.4 Version decision (`VersionPolicy`)

Input: current row (nullable: `active, sourceSeq, hrHash`), tombstone seq (nullable; from `employee_tombstone`), incoming (kind, seq, hrHash), `absenceWatermark`.
Output `Decision{ outcome ∈ {APPLY, TOMBSTONE, STALE, DUPLICATE, CONFLICT}, eventType }`.

Let `floor = max(absenceWatermark, tombstoneSeq ?? 0)`. These rows apply only when **no employee row exists**:

| Current | Incoming | Decision |
|---|---|---|
| none | UPSERT, seq ≤ floor | STALE |
| none | UPSERT, seq > floor | APPLY → `EmployeeCreated` (delete the tombstone row in the same tx) |
| none | DEACTIVATE, seq > (tombstoneSeq ?? 0) | TOMBSTONE: upsert `employee_tombstone` with `source_seq = GREATEST(existing, seq)`; **no outbox event** (no downstream service knows this employee); outcome `TOMBSTONED`, HTTP 200 |
| none | DEACTIVATE, seq ≤ tombstoneSeq | STALE (same seq → DUPLICATE) |

When an employee row exists:

| Current | Incoming | Decision |
|---|---|---|
| any | seq < current.seq | STALE |
| any | seq = current.seq, same hash and same active-state intent | DUPLICATE |
| any | seq = current.seq, otherwise | CONFLICT (`VERSION_CONFLICT`) |
| active | UPSERT, seq > current, hash differs | APPLY → `EmployeeUpdated` |
| active | UPSERT, seq > current, hash same | APPLY → `EmployeeVersionAdvanced` |
| inactive | UPSERT, seq > current | APPLY → `EmployeeReactivated` |
| active | DEACTIVATE, seq > current | APPLY → `EmployeeDeactivated` |
| inactive | DEACTIVATE, seq > current | APPLY → `EmployeeVersionAdvanced` (stays inactive) |

"Same active-state intent" means UPSERT vs an active row, or DEACTIVATE vs an inactive row. For DEACTIVATE, compare hash as "no HR change". `changedFields` = names of HR fields whose normalised values differ (plus `active` when it flips).

**Observable state for order-independence** (used by `InMemoryApplier` and its tests): per employee, `(exists, active, sourceSeq, hrHash if active else ⊥)`, where a tombstone-only employee is `(false, false, tombstoneSeq, ⊥)` and an inactive row is `(true, false, seq, ⊥)`. Both mean "inactive at seq". The comparison maps both to `(inactive, seq)`.
- Example: delivering `DEACTIVATE v2` then `UPSERT v1` ends tombstoned at 2; sorted delivery ends with an inactive row at 2. These are equal under this projection.
- This is the documented semantics (DEC-20): the HR profile of an employee whose deactivation outran their creation isn't retained, because they're gone anyway.

### 7.5 Safety brake (`SafetyBrake`)

`tripped = baseline > 0 && (long) missing * 10_000 >= (long) thresholdBp * baseline`. Default `thresholdBp = 3000`.

### 7.6 Bucket diff (`BucketDiff`)

`Set<Integer> mismatched(Map<Integer, byte[]> staged, Map<Integer, byte[]> store)`. A bucket is mismatched if it's present on only one side or the digests differ (`Arrays.equals`).

### 7.7 Lifecycle and ownership

`OwnershipPolicy` declares `HR_FIELDS = [name, email, phone, managerId, officeCode, shiftCode, active]` and `TRANSPORT_FIELDS = [pickupPoint, specialAssistance]`. `assertTransportOnly(Set<String> fields)` throws `OwnershipViolation` for HR fields. DEC-07: when an applied HR change modifies `officeCode` and the employee has a non-null `pickupPoint`, add `"PICKUP_REVIEW_NEEDED"` to `changedFields`.

---

## 8. Event ingestion (roster `HrEventService`)

One transaction per event, via `TenantTx.inTenant`:

0. **Validate first** (§7.2), before opening the main transaction. Invalid → in its own transaction:
   - If `eventId` is present and well-formed, insert `ingest_event` (outcome `REJECTED`, codes, `payload_hash`).
   - Always insert `record_error` rows carrying a new `rejection_id` (UUID). `event_id` is filled only if present; the value is never stored when it's malformed.
   - Return `REJECTED` with `rejectionId` (422 single; per-record in batch).
1. Main transaction: `SET LOCAL lock_timeout = '3s'`. Then:
   - `SELECT pg_advisory_xact_lock_shared(:tenantLockKey)` (key from `tenant_source`; cached).
   - **`SELECT pg_advisory_xact_lock(hashtextextended(:tenant || '|' || :source || '|' || :employeeId, 0))`** — a per-employee lock that serialises every event for the same employee, including the "no row yet" cases (create vs deactivate vs tombstone). Hash collisions only reduce concurrency, never correctness.
   - Lock timeout → `ReconciliationInProgressException` → 503 + `Retry-After: 5`.
2. `INSERT INTO ingest_event(...) VALUES (...) ON CONFLICT (tenant_id, source_id, event_id) DO NOTHING RETURNING id`.
   - No row → `SELECT payload_hash`. Equal → `DUPLICATE`; different → `CONFLICT` (`EVENT_ID_REUSED`). Return.
3. Read state (safe under the per-employee lock):
   - `SELECT active, source_seq, hr_hash, office_code, pickup_point, aggregate_revision FROM employee WHERE <pk> FOR UPDATE`
   - `SELECT source_seq FROM employee_tombstone WHERE <pk>`
   - `SELECT absence_watermark FROM tenant_source WHERE …` (**not** from cache)
4. `VersionPolicy.decide(...)` (§7.4).
5. Execute the decision:
   - **APPLY, no row (Created):** `INSERT INTO employee (...)` with revision 1; `DELETE FROM employee_tombstone WHERE <pk>`.
   - **TOMBSTONE:** `INSERT INTO employee_tombstone(...) VALUES (...) ON CONFLICT (pk) DO UPDATE SET source_seq = GREATEST(employee_tombstone.source_seq, EXCLUDED.source_seq)`. Outcome `TOMBSTONED`, no outbox.
   - **APPLY, row exists:** run the matching guarded update below.
6. Guarded updates for existing rows:
   - UPSERT: `UPDATE employee SET <HR cols>, active = true, source_seq = :seq, hr_hash = :h, bucket = :b, aggregate_revision = aggregate_revision + 1, tombstone_reason = NULL, updated_at = now() WHERE <pk> AND source_seq < :seq RETURNING aggregate_revision`
   - DEACTIVATE: `UPDATE employee SET active = false, source_seq = :seq, tombstone_reason = 'HR_DEACTIVATED', aggregate_revision = aggregate_revision + 1, updated_at = now() WHERE <pk> AND source_seq < :seq RETURNING aggregate_revision`
   - 0 rows here is a bug → throw `IllegalStateException` (the row lock guarantees ≥1).
   - In reconciliation (§9.3), the plan also consults `employee_tombstone`: CREATE for an ID whose tombstone seq ≥ the row's `last_change_seq` → STALE; otherwise create and delete the tombstone.
7. On APPLY:
   - insert `employee_history(change = eventType, origin = 'API')`;
   - insert `outbox_event` with a new UUID `event_id`, payload = `EmployeeChangedEvent` (`effectiveAt` from the request);
   - set `ingest_event.outcome = 'APPLIED'`.
   - On TOMBSTONED / STALE / DUPLICATE / CONFLICT: set the outcome (+ `record_error` for CONFLICT).
8. Commit. Then `faultInjector.hit("AFTER_INGEST_COMMIT")`, registered via `TransactionSynchronization.afterCommit`.

**Batch** (`POST /api/v1/hr/events/batch`, `Content-Type: application/x-ndjson`, ≤1,000 lines): parse line by line; each line goes through the single-event path in its own transaction. Response `200 {results: [{line, eventId, employeeId, outcome, errors:[{field, code}]}], summary: {APPLIED:n, ...}}`. A malformed JSON line gives `{line, outcome: "REJECTED", errors: [{field: null, code: "MALFORMED_JSON"}]}`.

**Single** (`POST /api/v1/hr/events`) status codes: `APPLIED` → 200 · `TOMBSTONED` → 200 · `DUPLICATE`/`STALE` → 200 · `CONFLICT` → 409 · `REJECTED` → 422. Body `{eventId, employeeId, outcome, aggregateRevision?, rejectionId?, errors?}`.

**Transport edit** (`PATCH /api/v1/employees/{id}/transport`, `If-Match: <aggregateRevision>` required, body `{pickupPoint?, specialAssistance?}`):
- `UPDATE employee SET pickup_point = ?, special_assistance = ?, aggregate_revision = aggregate_revision + 1 WHERE <pk> AND aggregate_revision = :ifMatch RETURNING aggregate_revision`.
- 0 rows → 404 if the employee is missing, else 409 `REVISION_MISMATCH`.
- On success: history (`origin = 'ADMIN'`) + outbox `EmployeeTransportUpdated` with `changedFields` = names. Response 200 with the new revision in an `ETag`.
- Any HR field in the body → 422 `UNKNOWN_FIELD` (DTO has only transport fields + fail-on-unknown).

**Admin deactivation** (`DELETE /api/v1/employees/{id}`):
- `UPDATE employee SET active = false, tombstone_reason = 'ADMIN_DEACTIVATED', aggregate_revision = aggregate_revision + 1 WHERE <pk> AND active RETURNING aggregate_revision`.
- `source_seq` is **not** changed, because HR owns it. A later HR UPSERT with a higher seq reactivates the employee. A nightly snapshot with the same seq and hash is a no-op (§9.3).
- Emits `EmployeeDeactivated` + history (`origin = 'ADMIN'`). 204 on success; 409 `ALREADY_INACTIVE`; 404 if missing.
- Document this as DEC-17 in `docs/decisions.md`.

---

## 9. Reconciliation (roster `ReconciliationService`)

### 9.1 Upload — `POST /api/v1/reconciliations` (multipart)

Parts:
- `file`: CSV with header `employee_id,name,email,phone,manager_id,office_code,shift_code,last_change_seq`
- `manifest`: JSON `{sourceId, cutoffSeq, exportedAt, expectedRows?, sha256?}`

Steps:
1. Validate the manifest. If `cutoffSeq < tenant_source.accepted_snapshot_cutoff` → 409 `STALE_SNAPSHOT`.
   **Same-cutoff lineage rule** (checked here and again under the lock in §9.3). Look at the latest finished run with the same `cutoff_seq`:
   - none, or it ended `PARTIAL_NO_DEACTIVATIONS` / `REJECTED` / `FAILED` / `INCOMPLETE` → allowed (a corrected re-send);
   - it ended `COMPLETED` with the same `file_sha256` → the new run finishes `COMPLETED` immediately with `counts = {"duplicateSnapshot": true}` and changes nothing (idempotent replay);
   - it ended `COMPLETED` with a different `file_sha256` → 409 `SNAPSHOT_CONFLICT` (two different "complete truths" for one cutoff).
   The SHA is only known after the upload streams, so the duplicate/conflict decision runs at the end of staging (§9.2) and again in §9.3.
2. Insert the run (`UPLOADING`). The unique index enforces one active run → 409 `RUN_IN_PROGRESS`.
3. Stream the file to `${app.recon.dir}/{runId}.csv` through a `DigestInputStream`. Store `file_sha256`.
4. Respond `202 {runId, status}`.
5. Submit `processRun(runId)` to a single-thread-per-tenant executor (`ThreadPoolTaskExecutor`, core 2). Max upload 300 MB (`spring.servlet.multipart.max-file-size=300MB`, `max-request-size=310MB`).

### 9.2 Stage — `processRun`, status `STAGING`

- Read the file with Commons CSV (`CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get()`), streaming record by record.
- Malformed CSV structure (exception) → `INCOMPLETE`, `error_code = CSV_MALFORMED`. Stop.
- Per record:
  - `employee_id` blank or invalid format → `unidentifiable_rows++`, write a `record_error(row_no, code = EMPLOYEE_ID_FORMAT)`, skip.
  - Otherwise validate (§7.2 rules adapted to row fields, plus `SEQ_NOT_POSITIVE`, plus `SEQ_AFTER_CUTOFF` if `last_change_seq > cutoffSeq`).
  - Compute `hr_hash` + `bucket` for valid rows.
  - Duplicate `employee_id` within the file: exact duplicate → skip; differing → mark the first row invalid `DUPLICATE_ROW_CONFLICT` and skip the second (record an error for both).
  - Track seen IDs in a `HashSet<String>` (200k IDs ≈ 15 MB; acceptable and documented).
- Buffer rows into chunks of 5,000. For each chunk, write CSV with `CSVFormat.POSTGRESQL_CSV` into a `StringWriter`, then `pgConn.getCopyAPI().copyIn("COPY staging_employee (run_id,row_no,employee_id,name,email,phone,manager_id,office_code,shift_code,last_change_seq,hr_hash,bucket,valid,error_codes) FROM STDIN (FORMAT csv)", new StringReader(...))`.
  - Get the connection via `DataSourceUtils.getConnection(dataSource)` inside a transaction per chunk; `unwrap(PGConnection.class)`.
  - `hr_hash` as `\x` + hex. `error_codes` as a Postgres array literal `{CODE1,CODE2}`.
  - Errors also go to `record_error` (batched insert per chunk).
- At the end:
  - If `expectedRows != null && parsedRows != expectedRows` → add flag `ROW_COUNT_MISMATCH`.
  - If `sha256 != null && != file_sha256` → `INCOMPLETE`, `error_code = CHECKSUM_MISMATCH`. Stop.
  - `ANALYZE staging_employee`. Status `STAGED`. Record `phase_ms.stage`.

### 9.3 Assess + apply — one transaction (`statement_timeout` 300 s, via `SET LOCAL`)

This runs on the **bulk datasource** (§14).

1. **Lock and re-check freshness:**
   - `SELECT pg_advisory_xact_lock(:lockKey)` (exclusive); `set_config('app.tenant_id', ...)`.
   - Re-read `tenant_source` **under the lock**. If `cutoff_seq < accepted_snapshot_cutoff` → status `REJECTED`, `error_code = STALE_SNAPSHOT`; COMMIT; stop. This also protects an old run approved after a newer one completed.
   - Apply the same-cutoff lineage rule (§9.1) again under the lock.
   - Status → `APPLYING` (inside this tx; rolled back on crash).
2. **Bucket digests** — two queries, each `sha256(string_agg(convert_to(employee_id,'UTF8') || hr_hash || int8send(<seq>), ''::bytea ORDER BY employee_id)) GROUP BY bucket`:
   - staging: `<seq>` = `last_change_seq`, `WHERE run_id = ? AND valid`;
   - employee: `<seq>` = `source_seq`, `WHERE tenant/source AND active`.
   - → `BucketDiff` → `mismatched` (`int[]`). Record `phase_ms.digest`, `buckets_mismatched`.
   - Including the seq means a same-HR, higher-version snapshot row is detected and becomes `ADVANCE`.
3. **Plan first:** build `recon_plan` (step 6 SQL) **now**, before deciding anything about absences. Count actions.
   - **Flags:** `INVALID_ROWS_PRESENT` if `invalid_rows > 0`; `UNIDENTIFIABLE_ROWS` if `unidentifiable_rows > 0`; **`CONFLICTS_PRESENT` if the plan has any `CONFLICT` row**; `ROW_COUNT_MISMATCH` from staging.
   - `inferAbsences = !(INVALID_ROWS_PRESENT || UNIDENTIFIABLE_ROWS || CONFLICTS_PRESENT || ROW_COUNT_MISMATCH)`.
   - Conflicting evidence therefore never deactivates anyone and never advances the absence watermark.
4. **Assessment** (always computed, even when `inferAbsences` is false; the brake blocks the **whole** run, including updates):
   - `baseline = count(*) employee WHERE tenant/source AND active AND source_seq <= :S`
   - `missingIds = employee WHERE tenant/source AND active AND source_seq <= :S AND bucket = ANY(:mismatched) AND NOT EXISTS (SELECT 1 FROM staging_employee s WHERE s.run_id = :run AND s.employee_id = employee.employee_id)`
   - `missing = count`; `missing_sample = first 20 IDs (ORDER BY employee_id)`
   - `assessment_hash = SHA-256(S | baseline | missing | sha256(string_agg(employee_id ORDER BY employee_id)))`, computed in SQL and Java consistently.
   - `brake = SafetyBrake.tripped(...)`.
5. **If brake tripped and the run isn't approved** (or the approval's `assessment_hash` ≠ the new one):
   - Status `AWAITING_APPROVAL`; save the assessment fields (flags add `MISSING_RATIO_EXCEEDED`).
   - Insert `operator_alert(kind = 'SAFETY_BRAKE')` with details `{runId, missing, baseline, ratioBp}` (IDs/counts only); increment `roster_safety_brake_trips_total`; WARN log `event=safety_brake runId=… missing=… ratioBp=…`.
   - Record `phase_ms.assess`. **COMMIT — no employee row changed.** Stop.
   - If `ROW_COUNT_MISMATCH` is set, the run is also marked non-approvable.
6. **Plan SQL** (built in step 3; applied here):
   ```sql
   CREATE TEMP TABLE recon_plan ON COMMIT DROP AS
   SELECT s.employee_id, s.last_change_seq AS new_seq, s.hr_hash AS new_hash, s.bucket,
          e.active AS old_active, e.source_seq AS old_seq, e.hr_hash AS old_hash,
          CASE WHEN e.employee_id IS NULL AND t.source_seq >= s.last_change_seq THEN 'STALE'   -- tombstoned
               WHEN e.employee_id IS NULL THEN 'CREATE'
               WHEN s.last_change_seq < e.source_seq THEN 'STALE'
               WHEN s.last_change_seq = e.source_seq AND s.hr_hash = e.hr_hash THEN 'DUPLICATE'   -- also respects admin deactivation
               WHEN s.last_change_seq = e.source_seq THEN 'CONFLICT'
               WHEN NOT e.active THEN 'REACTIVATE'
               WHEN s.hr_hash = e.hr_hash THEN 'ADVANCE'
               ELSE 'UPDATE' END AS action
   FROM staging_employee s
   LEFT JOIN employee e ON e.tenant_id = :t AND e.source_id = :src AND e.employee_id = s.employee_id
   LEFT JOIN employee_tombstone t ON t.tenant_id = :t AND t.source_id = :src AND t.employee_id = s.employee_id
   WHERE s.run_id = :run AND s.valid AND s.bucket = ANY(:mismatched);
   ```
   CREATE also runs `DELETE FROM employee_tombstone WHERE … AND employee_id IN (created ids)` in the same tx.
   **Apply in order, each statement feeding history and outbox through a CTE** (see the pattern below):
   - CREATE → `INSERT INTO employee ... SELECT ... FROM staging_employee JOIN recon_plan USING (employee_id) WHERE action = 'CREATE' AND run_id = :run` → revision 1 → `EmployeeCreated`.
   - UPDATE / ADVANCE / REACTIVATE → `UPDATE employee e SET <HR cols from staging>, active = true, source_seq = p.new_seq, hr_hash = p.new_hash, aggregate_revision = e.aggregate_revision + 1, tombstone_reason = NULL FROM recon_plan p JOIN staging_employee s ON s.run_id = :run AND s.employee_id = p.employee_id WHERE p.action IN ('UPDATE','ADVANCE','REACTIVATE') AND e.<pk matches> AND e.source_seq < p.new_seq RETURNING e.employee_id, e.aggregate_revision, p.action` → `EmployeeUpdated` / `EmployeeVersionAdvanced` / `EmployeeReactivated`. `changedFields` for UPDATE comes from a Java follow-up comparing old vs new **only if cheap**; otherwise `["hr"]`. (Acceptable; document it.)
   - CONFLICT → `record_error(code = 'VERSION_CONFLICT')`.
   - **If `inferAbsences`:** `UPDATE employee SET active = false, source_seq = :S, tombstone_reason = 'ABSENT_IN_SNAPSHOT', aggregate_revision = aggregate_revision + 1 WHERE tenant/source AND active AND source_seq <= :S AND bucket = ANY(:mismatched) AND NOT EXISTS (staging presence) RETURNING employee_id, aggregate_revision` → `EmployeeDeactivated` (`effectiveAt = exported_at`).
   - `faultInjector.hit("DURING_RECON_APPLY")` right after the deactivation statement (inside the tx).
7. **Finish:**
   - `tenant_source.accepted_snapshot_cutoff = GREATEST(current, :S)`; if `inferAbsences`, `absence_watermark = GREATEST(current, :S)`.
   - Counts jsonb `{created, updated, advanced, reactivated, deactivated, stale, duplicate, conflict, unchangedBuckets}`.
   - Status `COMPLETED` if `inferAbsences`, else `PARTIAL_NO_DEACTIVATIONS` (flags show why: invalid rows, unidentifiable rows, conflicts, row-count mismatch). Record `phase_ms.apply`. COMMIT.
   - Then delete the staging rows for the run (separate tx).

**CTE pattern for apply + history + outbox:**

```sql
WITH changed AS ( <INSERT/UPDATE ... RETURNING employee_id, aggregate_revision, source_seq, <event_type expr> AS event_type, active> ),
ev AS ( SELECT gen_random_uuid() AS eid, * FROM changed ),
hist AS ( INSERT INTO employee_history(tenant_id, employee_id, source_seq, aggregate_revision, change, origin, run_id)
          SELECT :t, employee_id, source_seq, aggregate_revision, event_type, 'RECONCILIATION', :run FROM ev )
INSERT INTO outbox_event(event_id, tenant_id, employee_id, aggregate_revision, event_type, payload)
SELECT eid, :t, employee_id, aggregate_revision, event_type,
       jsonb_build_object('eventId', eid::text, 'eventType', event_type, 'tenantId', :t, 'employeeId', employee_id,
                          'aggregateRevision', aggregate_revision, 'active', active, 'effectiveAt', :exportedAt,
                          'changedFields', <jsonb array>)
FROM ev;
```

The `effectiveAt` value must serialise in the same ISO-8601 form Jackson produces for `Instant`. Pass it as a pre-formatted string parameter.

### 9.4 Approval and lifecycle endpoints

- `POST /reconciliations/{id}/approve` (ADMIN) `{assessmentHash (hex), reason}`.
  - Only from `AWAITING_APPROVAL`; non-approvable (`ROW_COUNT_MISMATCH`) → 409 `RUN_NOT_APPROVABLE`.
  - Hash mismatch with stored → 409 `APPROVAL_STALE`.
  - Store approver/reason, set status `STAGED`, resubmit `processRun` (it goes straight to §9.3). §9.3 re-assesses: if the new hash ≠ the approved hash → back to `AWAITING_APPROVAL` (approval cleared).
- `POST /reconciliations/{id}/reject` (ADMIN, OPERATOR) → `REJECTED`; staging deleted.
- `GET /reconciliations/{id}`, `GET /reconciliations/{id}/errors?page=&size=` (row_no, employeeId, field, code), `GET /reconciliations/pending-approval`.

**Startup recovery** (`ApplicationReadyEvent`):
- Runs in `UPLOADING`/`STAGING` → `FAILED` (`error_code = INTERRUPTED`) and delete their staging rows.
- Runs in `STAGED` → resubmit `processRun` (goes to §9.3). Note `APPLYING` can't persist after a crash because it's set inside the rolled-back tx.
- `POST /reconciliations/{id}/retry` (OPERATOR): `FAILED` with the file present → restage.

---

## 10. Outbox relay (roster `OutboxRelay`)

- `@Scheduled(fixedDelayString = "${app.relay.poll-ms:200}")`, enabled by `app.relay.enabled` (default true). Worker pool of 4 virtual threads for sends.
- **Claim** (no RLS on outbox):
  ```sql
  WITH next AS (
    SELECT o.id FROM outbox_event o
    WHERE ((o.state IN ('PENDING','RETRY') AND o.next_attempt_at <= now())
        OR (o.state = 'CLAIMED' AND o.lease_until < now()))
      AND NOT EXISTS (SELECT 1 FROM outbox_event p
                      WHERE p.tenant_id = o.tenant_id AND p.employee_id = o.employee_id
                        AND p.aggregate_revision < o.aggregate_revision AND p.state <> 'DELIVERED')
    ORDER BY o.id LIMIT :batch FOR UPDATE SKIP LOCKED)
  UPDATE outbox_event SET state = 'CLAIMED', lease_token = :token, lease_until = now() + make_interval(secs => :leaseSecs),
         attempts = attempts + 1                       -- attempts = number of send attempts (incremented on every claim)
  WHERE id IN (SELECT id FROM next) RETURNING id, event_id, payload, attempts;
  ```
  Then `faultInjector.hit("RELAY_AFTER_CLAIM")`.
- **Send:** `POST {app.booking.base-url}/internal/events` with the payload, header `Authorization: Bearer <service token>` and `Idempotency-Key: <eventId>`. `JdkClientHttpRequestFactory`: connect 1 s, read 3 s.
- **Result:**
  - 2xx → `UPDATE ... SET state = 'DELIVERED', delivered_at = now(), lease_token = NULL WHERE id = ? AND lease_token = ?`.
  - 4xx except 408/429 → `DEAD` immediately (`last_error_code = HTTP_4xx`).
  - 5xx / 408 / 429 / IO error / timeout → `state = 'RETRY'`, `last_error_code`, `next_attempt_at = now() + backoff`, where backoff = `min(2^attempts, 300) s × (0.8 + 0.4 × random)`. `attempts` was already incremented at claim.
  - Failure with `attempts ≥ app.relay.max-attempts (10)` → `DEAD`.
  - So one failure followed by a successful retry ends `DELIVERED` with `attempts = 2`. A reclaimed expired lease also counts as an attempt, because the first send may have happened.
  - Every state update is lease-token-guarded.
- `GET /api/v1/outbox/status` (OPERATOR): counts by state + oldest pending age, tenant-filtered.
- `POST /api/v1/outbox/{id}/replay` (OPERATOR): `DEAD` → `PENDING`, `attempts = 0`, same `event_id`.
- **Purge:** `@Scheduled` hourly: delete `DELIVERED` older than `app.relay.retention-days` (7).
- **Service token:** `TokenService.serviceToken()` mints and caches a token (role `SERVICE`, tenant `*`, aud `booking`, 10 min, refreshed at 80%).

---

## 11. booking-service

### 11.1 Consumer — `POST /internal/events` (role SERVICE)

One transaction, `BookingTenantTx.inTenant(event.tenantId())`:

1. `INSERT INTO processed_message(consumer, event_id, outcome) VALUES ('booking', :id, 'PROCESSING') ON CONFLICT DO NOTHING`. 0 rows → return `200 {outcome: DUPLICATE}` (no other changes).
2. `SELECT * FROM employee_projection WHERE … FOR UPDATE` (insert if absent with `last_revision = 0` first, via `ON CONFLICT DO NOTHING`).
3. If `event.aggregateRevision <= last_revision` → outcome `SKIPPED_OLDER`.
4. Else update the projection (`active`, `last_revision`, `effective_at`). If `eventType == EmployeeDeactivated`:
   ```sql
   UPDATE booking SET status = 'CANCELLED', cancelled_reason = 'EMPLOYEE_DEACTIVATED', cancelled_by_event = :eventId
   WHERE tenant_id = :t AND employee_id = :e AND status = 'RESERVED' AND scheduled_start > :effectiveAt
   RETURNING booking_id;
   UPDATE seat_reservation SET state = 'RELEASED', released_at = now()
   WHERE booking_id = ANY(:ids) AND state = 'ACTIVE';
   ```
5. Update the `processed_message` outcome (`PROCESSED`/`SKIPPED_OLDER`). Commit. Then `faultInjector.hit("AFTER_CONSUMER_COMMIT")` (afterCommit). Return `200 {outcome}`.

Metrics: `booking_consumer_events_total{result}`, `booking_cancellations_total`, `booking_seats_released_total`.

### 11.2 Booking API

- `POST /api/v1/trips` (ADMIN) `{capacity, startsAt}`.
- `GET /api/v1/trips/{id}/seats` → `{capacity, reserved, available}`, with `available = capacity − count(ACTIVE reservations)`.
- `POST /api/v1/bookings` (EMPLOYEE for self, or ADMIN) `{employeeId, tripId}`, one tx:
  1. Lock the projection row `FOR UPDATE`; absent or inactive → 409 `EMPLOYEE_INACTIVE`.
  2. `SELECT … FROM trip WHERE trip_id = ? FOR UPDATE`.
  3. Compute the first free `seat_no` in 1..capacity; none → 409 `TRIP_FULL`.
  4. Insert `booking(RESERVED, scheduled_start = trip.starts_at)` and `seat_reservation(ACTIVE)`.
  5. The unique index `seat_active` is the final guard (retry once on unique violation).
- `GET /api/v1/bookings/{id}` (owner EMPLOYEE or ADMIN). `GET /api/v1/employees/{id}/bookings` (self or ADMIN).

---

## 12. Security

### 12.1 Tokens (roster-service issues; both services validate)

- **Key:** RSA 2048. Loaded from `${app.jwt.key-path}` (PEM PKCS#8); if absent, generate and **write** it there (so restarts keep the key). Compose mounts a named volume `jwtkeys:/data/jwt`.
- **Issuer endpoint** (only when `app.auth.dev-issuer.enabled=true`, true in compose): `POST /api/v1/auth/token {username, password}` → `{accessToken, expiresIn}`.
  - Users come from `app.auth.users[]` (username, password (plain in config → BCrypt-hashed at startup), tenant, roles, employeeId?).
  - Claims: `iss = "roster-sync"`, `aud = ["roster-sync","booking"]`, `sub`, `tenant`, `roles` (string list), `employeeId?`, `exp = 15 min`. Sign with `NimbusJwtEncoder` + `RSAKey` JWK (`kid` = SHA-256 thumbprint).
- **JWKS:** `GET /.well-known/jwks.json` (public).
- **Roster resource server:** `NimbusJwtDecoder.withPublicKey(pub)` + validators for issuer, audience (`roster-sync`) and timestamps.
- **Booking resource server:** `jwk-set-uri = ${APP_ROSTER_JWKS_URI}` + issuer + audience (`booking`) validators.
- **Authorities:** a `JwtAuthenticationConverter` maps `roles` → `ROLE_<x>`. `TenantContext.current()` reads the `tenant` claim. `SERVICE` tokens carry tenant `*` and may only call `/internal/**`.

### 12.2 Demo users (both tenants: `acme`, `globex`; password `demo123`)

`hr@<t>` [HR_INTEGRATION] · `admin@<t>` [ADMIN] · `ops@<t>` [OPERATOR] · `emp001@<t>` [EMPLOYEE, employeeId `EMP-001`].

### 12.3 Authorization matrix (enforce with `@PreAuthorize` + tenant checks; test every cell)

| Endpoint | EMPLOYEE | OPERATOR | ADMIN | HR_INTEGRATION | SERVICE |
|---|---|---|---|---|---|
| POST /hr/events, /hr/events/batch | 403 | 403 | 403 | ✓ | 403 |
| POST /reconciliations | 403 | ✓ | 403 | ✓ | 403 |
| GET /reconciliations/** | 403 | ✓ | ✓ | 403 | 403 |
| POST …/approve | 403 | 403 | ✓ | 403 | 403 |
| POST …/reject | 403 | ✓ | ✓ | 403 | 403 |
| POST …/retry | 403 | ✓ | 403 | 403 | 403 |
| GET /employees/{id} | self only | ✓ | ✓ | 403 | 403 |
| GET /employees/{id}/history | 403 | 403 | ✓ | 403 | 403 |
| PATCH /employees/{id}/transport | 403 | 403 | ✓ | 403 | 403 |
| DELETE /employees/{id} | 403 | 403 | ✓ | 403 | 403 |
| GET /outbox/status, POST /outbox/{id}/replay | 403 | ✓ | 403 | 403 | 403 |
| booking: POST /trips | 403 | 403 | ✓ | 403 | 403 |
| booking: POST /bookings | self only | 403 | ✓ | 403 | 403 |
| booking: GET /trips/{id}/seats | ✓ | ✓ | ✓ | 403 | 403 |
| booking: POST /internal/events | 403 | 403 | 403 | 403 | ✓ |

Another tenant's resource → **404**. No token → 401. `GET /actuator/health` public; `/actuator/prometheus` permitted from the compose network (permitAll is acceptable for the demo; document it).

---

## 13. Errors, faults, logging, caching, metrics

### 13.1 Error contract

`GlobalExceptionHandler` (`@RestControllerAdvice`) → `ProblemDetail` with properties `errorCode`, `traceId` (from MDC) and optional `errors: [{field, code}]`.

**Catalog** (`ErrorCode` enum, with HTTP status): `VALIDATION_FAILED` 422, `UNKNOWN_FIELD` 422, `MALFORMED_JSON` 400, `NOT_FOUND` 404, `VERSION_CONFLICT` 409, `EVENT_ID_REUSED` 409, `REVISION_MISMATCH` 409, `ALREADY_INACTIVE` 409, `RUN_IN_PROGRESS` 409, `STALE_SNAPSHOT` 409, `SNAPSHOT_CONFLICT` 409, `RUN_NOT_APPROVABLE` 409, `APPROVAL_STALE` 409, `INVALID_RUN_STATE` 409, `EMPLOYEE_INACTIVE` 409, `TRIP_FULL` 409, `UNAUTHENTICATED` 401, `FORBIDDEN` 403, `PAYLOAD_TOO_LARGE` 413, `RECONCILIATION_IN_PROGRESS` 503, `DB_UNAVAILABLE` 503, `INTERNAL_ERROR` 500.

Messages are fixed strings per code (`title`). Never interpolate request values. 503s set `Retry-After`. Map `CannotGetJdbcConnectionException`, `QueryTimeoutException`, `TransientDataAccessResourceException` and socket timeouts → `DB_UNAVAILABLE`.

### 13.2 Fault injection

`FaultInjector` bean in both services: `app.faults.point` (default empty). `hit(String point)`: if equal → `log.error("event=fault_halt point={}", point)` → `Runtime.getRuntime().halt(137)`.

Points: `AFTER_INGEST_COMMIT`, `DURING_RECON_APPLY`, `RELAY_AFTER_CLAIM` (roster); `AFTER_CONSUMER_COMMIT` (booking). Unit-test with an overridable halter.

### 13.3 Logging

- `logging.structured.format.console=ecs`.
- An MDC filter sets `traceId` (incoming `X-Request-Id` or a new UUID; echoed in the response header), `tenantId` and `principal` (sub).
- Services add `eventId`, `employeeId`, `runId` to MDC around work.
- Log lines use `key=value` messages with IDs/codes/counts only.
- A Logback `TurboFilter` (`PiiGuardFilter`) denies events whose arguments are instances of request/entity DTO types, as defence in depth.

### 13.4 Caching (Caffeine via `CacheManager`)

| Cache | Spec | Key → value |
|---|---|---|
| `officeCatalog` | `maximumSize=10000,expireAfterWrite=5m,recordStats` | tenantId → Set<officeCode> |
| `tenantConfig` | `maximumSize=10000,expireAfterWrite=60s,recordStats` | tenantId → threshold bp |
| `tenantSource` | `maximumSize=10000,expireAfterWrite=10m,recordStats` | tenant\|source → lockKey, watermark |

Evict on writes (`PUT /api/v1/admin/offices/{code}` (ADMIN) and `PUT /api/v1/admin/config` (ADMIN) exist for this).

> ⚠ **The `tenantSource` absence watermark must not be read from cache in the ingest path.** Read it in-transaction. Cache only `lockKey`.

Register cache metrics (Boot does this for `CacheManager` caches with `recordStats`).

### 13.5 Metrics (names exactly)

**Roster:** `roster_events_total{outcome}`, `roster_event_apply_seconds` (histogram, publishPercentileHistogram), `roster_validation_rejections_total{code}`, `outbox_queue_depth{state}` (gauge, polled every 5 s), `outbox_oldest_pending_age_seconds` (gauge), `outbox_delivery_attempts_total{result}`, `outbox_retries_total`, `outbox_dead_total`, `outbox_delivery_lag_seconds` (histogram; created → delivered), `reconciliation_phase_seconds{phase}`, `reconciliation_runs_total{status}`, `roster_safety_brake_trips_total`, `roster_reconciliation_lock_seconds`.

**Booking:** see §11.1.

Labels are bounded enums only.

---

## 14. Configuration

**roster `application.yml`** (env overrides in compose):

```yaml
server.port: 8080
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:5432/roster?socketTimeout=10&connectTimeout=2
    username: roster_app
    password: roster_app_pw
    hikari: { maximum-pool-size: 20, connection-timeout: 2000 }
  flyway: { user: roster_owner, password: roster_owner_pw }
  jackson.deserialization.fail-on-unknown-properties: true
  servlet.multipart: { max-file-size: 300MB, max-request-size: 310MB }
  cache.type: caffeine
logging.structured.format.console: ecs
management:
  endpoints.web.exposure.include: health,info,prometheus
  metrics.distribution.percentiles-histogram.roster_event_apply_seconds: true
app:
  tenant-source-default: hr-main
  relay: { enabled: true, poll-ms: 200, batch: 100, lease-secs: 30, max-attempts: 10, retention-days: 7 }
  booking.base-url: http://localhost:8081
  recon.dir: /tmp/recon
  jwt.key-path: /tmp/jwt/key.pem
  auth.dev-issuer.enabled: true
  faults.point: ""
  dev-seed.enabled: false
  # Second pool for reconciliation: socketTimeout must exceed the 300 s statement_timeout,
  # otherwise the driver kills a legitimately long apply. Wired manually (see below).
  bulk-datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:5432/roster?socketTimeout=330&connectTimeout=2
    username: roster_app
    password: roster_app_pw
    maximum-pool-size: 3
    connection-timeout: 5000
```

**Bulk datasource wiring (roster):**
- The primary `DataSource` (`@Primary`) serves the API, relay and seeders. Its socketTimeout of 10 s keeps the DB-pause test's fast 503.
- `BulkDbConfig` builds a second `HikariDataSource` from `app.bulk-datasource`, plus `bulkTxManager` (`DataSourceTransactionManager`), `bulkTxTemplate` and `bulkJdbcClient`, all `@Qualifier("bulk")`.
- Defining a second `DataSource` bean can switch off Boot's datasource auto-config, so declare the primary explicitly: `@Bean @Primary @ConfigurationProperties("spring.datasource.hikari") HikariDataSource dataSource(DataSourceProperties p)` built from `p.initializeDataSourceBuilder()`. Also keep a `@Primary` `PlatformTransactionManager` and `JdbcClient`.
- Flyway still uses its own `spring.flyway.user/password` (owner role) on the primary URL.
- `ReconciliationService` (staging COPY and §9.3) uses only the bulk beans. `TenantTx` gets a `bulk` variant.
- Both pools use the same role, so RLS and advisory locks behave identically.
- **Test:** `BulkPoolIT.longApplyNotKilledBySocketTimeout`: run `SELECT pg_sleep(15)` through the bulk client (must succeed) and through the primary client (must fail with `DB_UNAVAILABLE`).

**booking `application.yml`:** port 8081, `booking` DB with the booking roles, `app.roster.jwks-uri`, `app.faults.point`, `app.dev-seed.enabled`.

---

## 15. Dev seeding (compose sets `APP_DEV_SEED_ENABLED=true`)

- **Roster `DevSeeder`** (`ApplicationReadyEvent`, idempotent):
  - For tenants `acme` and `globex`: `tenant_source(hr-main, lock_key = abs(hash))`, `tenant_config`, offices `BLR-A`, `BLR-B`, `GGN-A`.
  - Then ingest employees `EMP-001`..`EMP-100` through `HrEventService` with `sourceSeq` 1..100 (skip if they already exist).
- **Booking `DevSeeder`:**
  - For each tenant: create 20 trips (capacity 10, `starts_at` = now + 1..20 days) if none exist.
  - Then poll the projection every 2 s (max 120 s) until ≥50 active employees.
  - Then give each of `EMP-001`..`EMP-050` **3 future bookings** on 3 different trips (150 bookings ≤ 200 seats). Skip if already present.
  - Also create one **past** booking for `EMP-001`..`EMP-010` (trip `starts_at` = now − 1 day, inserted directly by the seeder) so tests can show past bookings aren't cancelled.
- **`scripts/lib.sh` `next_seq()`** returns `$(( $(date +%s) * 1000000 + COUNTER ))`, with `COUNTER` incremented on each call (< 1,000,000). It's always above seeded seqs and monotonic across runs.
- **Portability:** scripts must run on Linux and macOS bash 3.2+. No GNU-only flags (`date +%N`/`%3N`, `sed -i` without a suffix argument, `readlink -f`, `grep -P`, `timeout`). Use `jq` for JSON and `curl -sS -w '%{http_code}'` for status codes.

---

## 16. Build phases (execute in order; gate must pass before committing)

Gate command for every phase unless stated: `./mvnw -q verify && scripts/check-tests.sh` (all unit + IT tests green **and** every required test class for the phases done so far actually executed; see §2.2).

| # | Deliverable | Gate (in addition to verify) | Commit message |
|---|---|---|---|
| P0 | Parent + 3 modules, wrapper, Surefire/Failsafe config + `scripts/check-tests.sh` + `scripts/required-tests.txt` (§2.2), pinned image tags, `docker/` init SQL, Dockerfiles, compose (postgres, roster, booking), empty apps with health | `docker compose up -d --build` → both `/actuator/health` UP within 120 s | `chore: scaffold multi-module project and compose` |
| P1 | Flyway V1/V2 both DBs, roles, RLS, `TenantTx`; `RlsIT` (unscoped query sees only own tenant; superuser not used) | verify | `feat(db): schemas, roles and row-level security` |
| P2 | `core` (§7) + unit tests + `CoreIsPureTest` + `OrderIndependenceTest` + `ReplayIdempotenceTest` (§17.1) | verify | `feat(core): validation, hashing, version policy, brake, bucket diff` |
| P3 | Ingest (§8): single, batch, transport PATCH, admin DELETE, history, outbox rows, error contract | ingest ITs green | `feat(roster): versioned event ingestion with outbox` |
| P4 | booking-service consumer + booking API (§11) | booking ITs green | `feat(booking): idempotent consumer, bookings and seats` |
| P5 | Relay (§10) + `StubBookingServer` ITs | relay ITs green | `feat(roster): outbox relay with leases, ordering, retries, dead letters` |
| P6 | Security (§12): issuer, JWKS, both resource servers, authorization matrix, tenant 404s | `SecurityMatrixIT` green | `feat(security): JWT auth, roles and tenant isolation` |
| P7 | Reconciliation (§9) incl. approval, startup recovery | recon ITs green | `feat(roster): streaming reconciliation with bucket diff and safety brake` |
| P8 | Caching, metrics, structured logs, PII guard, faults bean, dev seeders | `NoPiiIT`, `CacheIT`, `MetricsIT` green | `feat(ops): caching, metrics, structured logging, PII guard` |
| P9 | Concurrency ITs (§17.3) | green, run 3× consecutively | `test: concurrency suite` |
| P10 | `docker-compose.faults.yml` + fault scripts (§18) | `scripts/faults/run-all.sh` passes; outputs saved to `docs/fault-runs/` | `test: process-level failure injection` |
| P11 | Benchmarks (§19): generator, `ReconciliationBenchmarkIT`, k6, contention run | `scripts/bench/run-all.sh` completes; results in `bench/results/` | `perf: benchmarks with raw results` |
| P12 | Observability profile: prometheus.yml, alerts.yml, grafana provisioning + dashboard | `docker compose --profile observability up -d` → Prometheus target UP, rules loaded | `feat(ops): prometheus alerts and grafana dashboard` |
| P13 | Docs: README, design-note, decisions (DEC-01..17), benchmark.md (real numbers), Postman collection, demo scripts | fresh clone → `docker compose up --build` → `scripts/demo/run-all.sh` passes | `docs: README, design note, decisions, postman` |
| — | Tag `v1.0` | all gates green | `git tag v1.0` |

**P1 note:** `tenant_source` rows are needed by later phases; ITs insert them in fixtures.

---

## 17. Test catalog (implement every test; names exact)

**Test infra:**
- `AbstractPostgresIT` (per module): a static `PostgreSQLContainer("postgres:18")` copying `docker/postgres/init/01-roles-and-dbs.sql` to `/docker-entrypoint-initdb.d/` via `withCopyFileToContainer`. Start the container once per JVM (static block or a JUnit extension).
- `@DynamicPropertySource` sets the datasource URL (db `roster`/`booking`) + app user, and the Flyway user = owner.
- A `TestTokens` helper mints JWTs (roster: real `TokenService`; booking: a test `JwtDecoder` bean with a test keypair, `@TestConfiguration`).
- `StubBookingServer` (JDK `com.sun.net.httpserver.HttpServer`, random port) with programmable responses: 200, 500, delay(ms), close-connection, and a records-received list.

### 17.1 Unit (pure core, `*Test`)

`RecordValidatorTest` (each code, boundaries) · `CanonicalHasherTest` (order, whitespace, case, NFC, null vs empty, stability golden value) · `BucketerTest` (range 0..1023, stability golden values) · `VersionPolicyTest` (every row of §7.4 table) · `SafetyBrakeTest` (baseline 0; 299/1000 false; 300/1000 true; 400/1000 true; threshold 2500) · `BucketDiffTest` · `OwnershipPolicyTest` · `CoreIsPureTest` (scan `src/main/java/**/core/**` sources for forbidden imports) · `FaultInjectorTest`.

**`OrderIndependenceTest`** (replaces jqwik):
- 1,000 iterations, seeds 1..1000 (`new Random(seed)`).
- Generate 1–20 employees, 1–15 events each (UPSERT/DEACTIVATE, random HR states, increasing seq per employee).
- Delivered list = shuffle + duplicate ~20% of events.
- Apply both delivered and seq-sorted lists via `InMemoryApplier` (uses `VersionPolicy`).
- Assert equal final maps under the **observable-state projection** (§7.4: an inactive row and a tombstone both mean "inactive at seq"). On failure, the message includes the seed.
- The generator must include cases where a DEACTIVATE has a higher seq than every UPSERT of a never-created employee.

**`ReplayIdempotenceTest`:** applying any generated feed twice == once (1,000 seeds).

### 17.2 Integration — roster (`*IT`, Testcontainers)

`IngestApiIT`
- `appliesCreate`
- `duplicateEventIsNoop` (1 history, 1 outbox)
- `eventIdReusedWithDifferentPayloadIsConflict`
- `staleVersionIgnored`
- `equalSeqDifferentHashIsConflict`
- `deactivateUnknownCreatesTombstone` (200 `TOMBSTONED`, no outbox row)
- `deactivateBeforeCreateEndsInactive` (DEACTIVATE v2 then UPSERT v1 → STALE; no active employee)
- `upsertAboveTombstoneCreatesAndClearsTombstone`
- `rejectedRecordWithoutEventIdHasRejectionId` (no `ingest_event` row; `record_error.rejection_id` set; response has `rejectionId`)
- `reactivationRequiresHigherSeq`
- `oldEventCannotResurrectAfterSnapshotAbsence`

`BriefExampleIT`
- `transferV7BeforeShiftV6` (final office from v7, shift from v7, v6 `STALE`)
- `fileCutAt120kBlocksAndShowsMissingCount` (scaled: 2,000 active, file with 1,200 → `AWAITING_APPROVAL`, missing=800, 0 deactivated)

`ValidationIT`
- `malformedPhoneRejectedWithCode`
- `unknownOfficeRejectedWithCode`
- `oneBadRecordDoesNotFailBatch` (100 lines, 3 bad: 97 applied)
- `errorBodiesNeverEchoValues`

`OwnershipIT`
- `hrUpdateKeepsPickupAndAssistance`
- `hrPayloadWithPickupRejected422`
- `transportPatchRejectsHrField`
- `transportPatchRevisionMismatch409`
- `officeTransferFlagsPickupReview`

`OutboxIT`
- `oneOutboxRowPerAppliedChange`
- `noOutboxRowForStaleDuplicateConflict`
- `employeeAndOutboxRollBackTogether` (force a failure after the employee write via a test hook)
- `payloadContainsNoPii`

`ReplayIT`
- `replayFeedTwiceChangesNothing` (500 events; snapshot = `md5(string_agg(row::text ORDER BY pk))` of employee + count of outbox/history, before and after the second replay)

`RelayIT` (StubBookingServer)
- `deliversAndMarksDelivered`
- `retriesWithBackoffOn500`
- `deadLettersAfterMaxAttempts`
- `fourxxGoesDeadImmediately`
- `expiredLeaseIsReclaimed`
- `staleLeaseCannotAck`
- `perEmployeeOrderPreserved`
- `deadPredecessorBlocksSuccessorOnly`
- `replayDeadEventSameEventId`
- `attemptsCountEverySend` (stub fails once, then succeeds → `DELIVERED`, `attempts = 2`)

`ReconciliationIT`
- `initialLoadCreatesAll`
- `typicalNightAppliesOnlyChanges` (asserts exact counts: updated / created / deactivated)
- `quietNightTouchesFewBuckets` (2,000 employees, 5 changed → `buckets_mismatched ≤ 10` of 65,536)
- `sameHrHigherSeqAdvancesVersion` (snapshot row with identical HR but higher `last_change_seq` → `ADVANCE`; stored seq updated; a later API event with seq between old and new → `STALE`)
- `newerApiChangeSurvivesSnapshot`
- `newJoinerAfterCutoffNotDeactivated`
- `invalidRowsSuppressAllDeactivations` (status `PARTIAL_NO_DEACTIVATIONS`)
- `unidentifiableRowSuppressesDeactivations`
- `rowCountMismatchNotApprovable`
- `checksumMismatchIncomplete`
- `staleSnapshotRejected`
- `oneActiveRunPerTenant`
- `approvalWithStaleHashRejected`
- `approvalReassessesAndApplies`
- `rejectDeletesStaging`
- `startupRecoveryResumesStagedRun`
- `errorsEndpointHasNoValues`
- `alertCreatedOnBrake`
- `conflictsSuppressDeactivations` (equal seq, different hash in the file → `PARTIAL_NO_DEACTIVATIONS`, flag `CONFLICTS_PRESENT`, 0 deactivated, watermark unchanged)
- `oldRunApprovedAfterNewerCompletesIsRejected` (run A cutoff 100 blocked; run B cutoff 200 completes; approve A → `REJECTED`/`STALE_SNAPSHOT`, nothing applied)
- `sameCutoffSameFileIsNoop`
- `sameCutoffDifferentFileConflicts` (409 `SNAPSHOT_CONFLICT`)
- `correctedFileAfterPartialRunAllowed`
- `tombstonedIdInSnapshotIsStale`

`SecurityMatrixIT`
- Parameterised over every cell of §12.3.
- Plus `crossTenantReadIs404`, `noTokenIs401`, `expiredTokenIs401`, `wrongAudienceIs401`.

`RlsIT`
- `unscopedQueryReturnsOnlyCurrentTenantRows`
- `appRoleIsNotOwnerAndHasNoBypass`

`NoPiiIT`
- Canaries: name `Zyxw Canary`, email `canary.pii.7731@example.test`, phone `+919999977731`, pickup `Canary Lane 7731`.
- Run ingest (valid + invalid + conflict), transport patch, reconciliation with an invalid canary row, and relay to a stub.
- Capture logs with `OutputCaptureExtension`; assert no canary substring and no `[^\s@]+@[^\s@]+\.[a-z]+` / `\+\d{10,15}` match in logs, all error bodies, outbox payloads and `record_error` rows.

`CacheIT`
- `officeCatalogHitsAfterFirstLoad`
- `officeAddVisibleAfterEvict`
- `staleWithinTtlBoundOnly` (Caffeine `Ticker` injected via a test config)

`MetricsIT`
- Every metric name in §13.5 is present on `/actuator/prometheus` after a scenario.

`ErrorContractIT`
- Each `ErrorCode` path returns `ProblemDetail` with `errorCode` + `traceId`, and no stack trace.

### 17.3 Concurrency (roster/booking `*IT`; use `CyclicBarrier`/`CountDownLatch`, no sleeps)

- `RacingVersionsIT` (500 iterations: v6 ∥ v7 → final 7; ≤2 outbox rows)
- `ConcurrentDuplicatesIT` (50 threads, same event → 1 `APPLIED`, 49 `DUPLICATE`, 1 outbox row)
- `ConcurrentCreatesIT` (20 threads, seq 1..20 for a new employee → final 20, one row)
- `TransportVsHrIT` (200 iterations PATCH ∥ HR update → both owners' fields preserved)
- `SnapshotVsApiIT` (apply ∥ 200 API events with seq > S → none lost/deactivated)
- `RelayCompetitionIT` (3 relay instances (beans) on 5,000 events against the stub → every eventId received ≥1, order preserved per employee)
- `BookingRaceIT` (create booking ∥ deactivation event → no ACTIVE reservation for an inactive employee after both)
- `SeatRaceIT` (capacity 4, 20 concurrent bookings → exactly 4 succeed)
- `CreateVsDeactivateUnknownIT` (200 iterations: UPSERT v1 ∥ DEACTIVATE v2 for a new employee → always ends inactive at seq 2; never active)

### 17.4 booking-service (`*IT`)

`ConsumerIT`
- `deactivationCancelsOnlyFutureReservedBookings`
- `seatsReleasedOnce`
- `duplicateEventAbsorbed`
- `olderRevisionSkipped`
- `reactivationDoesNotRestoreBookings`

`BookingApiIT`
- `inactiveEmployeeCannotBook`
- `tripFull409`
- `employeeCanOnlyBookSelf`
- `seatsDerivedFromReservations`

---

## 18. Fault scripts (`scripts/faults/`, bash; each exits non-zero on assertion failure; output tee'd to `docs/fault-runs/<name>-<n>.txt`)

`docker-compose.faults.yml` defines no services. Scripts pass env through `docker compose run`/`up` with `-e`, or use per-script override files:

```yaml
# docker-compose.faults.yml
services:
  roster:  { environment: { APP_FAULTS_POINT: "${ROSTER_FAULT:-}", APP_RELAY_ENABLED: "${ROSTER_RELAY:-true}" }, restart: "no" }
  booking: { environment: { APP_FAULTS_POINT: "${BOOKING_FAULT:-}" }, restart: "no" }
```

| Script | Steps | Assertions |
|---|---|---|
| `kill-roster-after-commit.sh` | `ROSTER_FAULT=AFTER_INGEST_COMMIT ROSTER_RELAY=false docker compose -f docker-compose.yml -f docker-compose.faults.yml up -d --force-recreate roster`; wait healthy; DEACTIVATE `EMP-010` (3 future bookings); expect a connection error / no 200; wait for roster exit code 137; `docker compose up -d --force-recreate roster` (normal); wait for the outbox to drain | employee inactive; outbox row `DELIVERED`; 3 bookings `CANCELLED`; 3 seats `RELEASED`; `processed_message` 1 row |
| `kill-consumer-after-commit.sh` | `BOOKING_FAULT=AFTER_CONSUMER_COMMIT` recreate booking; DEACTIVATE `EMP-011`; wait for booking exit 137; outbox attempts ≥1, state `RETRY`; recreate booking normally; wait for drain | cancellations = 3 (not 6); seats released 3; `processed_message` for the event = 1 row; outbox `DELIVERED` with attempts ≥2 |
| `kill-during-reconciliation.sh` | roster with `ROSTER_FAULT=DURING_RECON_APPLY`; upload a 20k-row file missing 10 employees (below threshold); wait for exit 137; snapshot `count(*) WHERE NOT active`; recreate roster normally; wait for the run to finish | inactive count unchanged before restart (no partial apply); after restart, run `COMPLETED`, exactly 10 deactivated, no duplicate outbox rows |
| `kill-relay-after-claim.sh` | roster with `ROSTER_FAULT=RELAY_AFTER_CLAIM`; generate 50 events; exit 137; recreate normally; wait (lease 30 s) | all 50 delivered; none lost |
| `consumer-down.sh` | `docker compose stop booking`; 20 deactivations; observe `outbox_queue_depth{state="RETRY"}` > 0 and `outbox_oldest_pending_age_seconds` rising via `/actuator/prometheus`; wait 60 s; `docker compose start booking`; drain | all delivered; cancellations correct |
| `db-pause.sh` | background loop of 200 events at ~20/s with retry on 503/connection error (same eventId); `docker compose pause postgres` 10 s; unpause | some 503 `DB_UNAVAILABLE` observed; after the loop, every eventId has exactly one `ingest_event` row; outbox count = applied count; no orphan rows |

`run-all.sh` runs each 3 times sequentially, resetting state between runs (`docker compose down -v && up -d --build`, wait for seeding). It prints a summary table.

---

## 19. Benchmarks (`scripts/bench/run-all.sh`) — results must be real

- **Machine info** captured automatically into `bench/results/machine.txt`: `uname -a`, `nproc`, `free -h` (or `sysctl hw.memsize`), `java -version`, `docker info --format '{{.NCPU}} {{.MemTotal}}'`, Postgres `SELECT version()`.
- **Generator:** `roster-service/src/test/java/.../bench/ExportGenerator.java` (fixed seed 42). Writes to `target/bench/`:
  - `initial.csv`: 200,000 rows, EMP-000001..EMP-200000, `last_change_seq` = row index.
  - `typical.csv`: 3% changed (seq + 300,000), 1% new, 1% missing.
  - `heavy.csv`: 50% changed.
  - `cut.csv`: first 120,000 rows of the initial set.
  - `invalid.csv`: typical with 0.5% bad phones.
  - `quiet.csv`: baseline with 100 rows changed (0.05%), none new, none missing.
  - Changed rows get `last_change_seq` + 300,000 and at least one HR field changed.
  - Plus a manifest JSON for each.
- **`ReconciliationBenchmarkIT`** (`@Tag("benchmark")`; excluded by default; run via the Maven profile `-Pbenchmark`, which sets the failsafe `argLine=-Xmx256m -XX:+UseG1GC`):
  - **Baseline restore before every iteration (warm-up and each measured run):** `TRUNCATE employee, employee_history, outbox_event, staging_employee, record_error, reconciliation_run, employee_tombstone, ingest_event` (bulk client), then `BenchmarkBaseline.load()`.
    - `load()` COPYs the identical 200,000 baseline rows (from `initial.csv`, hashes and buckets computed in Java) **directly** into `employee`.
    - This reset is **not** measured, so every measured run starts from the same state.
    - Scenario `initial` instead starts from an empty `employee` table each iteration.
  - Then 1 warm-up + 3 measured runs per scenario.
  - **After each run, assert the expected mutation counts.** If they don't match, the benchmark fails rather than reporting a no-op replay:
    - initial: created 200,000
    - typical: updated 6,000, created 2,000, deactivated 2,000
    - heavy: updated 100,000
    - quiet: updated 100
    - cut: `AWAITING_APPROVAL`, 0 mutations
    - invalid: `PARTIAL_NO_DEACTIVATIONS`, 0 deactivated
  - Calls the service methods directly.
  - Records `phase_ms` (stage, digest, assess, apply), total, rows/s, peak heap (`MemoryMXBean` sampled every 100 ms in a background thread), `buckets_mismatched`, outbox rows created.
  - Writes `bench/results/recon-<scenario>.json`. Asserts `digest+assess+apply < 60_000 ms` for `typical` and `heavy`. Records `buckets_mismatched` per scenario (expected ≈14% of 65,536 for typical, ≈0.15% for quiet, nearly 100% for heavy). These are reported as measured, not asserted, except `quiet < 1%`.
- **k6** (`k6/ingest.js`):
  - `setup()` fetches an HR token.
  - Scenario `constant-arrival-rate` at rates 100, 250, 500, 1000 /s, 60 s each, preAllocatedVUs 200, maxVUs 1000.
  - Payload mix: 50% new employees, 46% updates over 10k IDs, 2% duplicates (resend a previous eventId+payload), 2% out-of-order (seq − 1000).
  - `sourceSeq` = `1e9 + exec.scenario.iterationInTest`.
  - Thresholds recorded, not enforced.
  - Run: `docker compose --profile bench run --rm k6 run --summary-export=/results/k6-<rate>.json /scripts/ingest.js -e RATE=<rate>`.
- **Contention run:** start the `typical` reconciliation through the API while k6 runs at 250/s. Capture API p99 during the apply window and `roster_reconciliation_lock_seconds`.
- **Delivery lag:** scrape `/actuator/prometheus` after the runs; extract `outbox_delivery_lag_seconds` quantiles from the histogram buckets.
- **`docs/benchmark.md`:** generated from the JSON (a small script, or filled by hand **from the JSON only**). Report the median and min–max, p50/p95/p99/p99.9, achieved rate, error %, the knee (rate where p99 > 100 ms), and machine info.

---

## 20. Docs and deliverables (P13)

- **`README.md`** (order is mandatory):
  1. Summary + demo-video link placeholder `VIDEO_LINK_TODO` (the human records the video).
  2. Results table (from `docs/benchmark.md` + test counts from Surefire/Failsafe reports + fault-run summary).
  3. Judgement calls (DEC list, one line each).
  4. Quick start (`docker compose up --build`, get a token, 5 curls).
  5. Coverage audit table (copy §1 of the plan, with links to test classes).
  6. Architecture diagram (Mermaid).
  7. Trade-offs, scaling limits, left out.
  8. How to run tests / faults / benchmarks.
  9. Language choice paragraph.
- **`docs/design-note.md`:** ≤2 pages — consistency model paragraph, architecture diagram, the reconciliation→relay→consumer-crash sequence diagram, failure-mode table (copy plan §16 and adjust to match the implementation).
- **`docs/decisions.md`:** DEC-01..DEC-16 from the plan + DEC-17 (admin deactivation doesn't touch `source_seq`) + DEC-18 (staging tracks seen IDs in memory, ~15 MB per 200k) + DEC-19 (`changedFields` = `["hr"]` for reconciliation updates, if implemented that way) + DEC-20 (tombstones for deactivations of unknown employees; observable-state equivalence) + DEC-21 (65,536 buckets, version included in the digest) + DEC-22 (separate bulk connection pool with socketTimeout > statement_timeout) + DEC-23 (same-cutoff lineage: duplicate = no-op, different file = conflict, re-send allowed after partial/failed runs). Template: Context / Decision / Rejected alternative / Consequence.
- **`postman/collection.json`** (v2.1): every endpoint, with a pre-request script that fetches tokens per role. `environment.json` with base URLs.
- **`scripts/demo/run-all.sh`:** executes the demo flow (plan §18) end to end with assertions. It doubles as a smoke test.
- **`docs/known-issues.md`:** any deviation, version fallback or limitation encountered. Empty sections are fine.

---

## 21. Definition of done (all must be true)

- [ ] `./mvnw verify` green from a clean clone (unit + IT + concurrency; benchmark excluded).
- [ ] `docker compose up --build` from a clean clone brings up postgres + roster + booking healthy, and seeds data.
- [ ] `scripts/demo/run-all.sh` passes.
- [ ] `scripts/faults/run-all.sh` passes 3/3 for every script; outputs committed.
- [ ] `scripts/bench/run-all.sh` completed; `bench/results/*` and `docs/benchmark.md` contain real numbers; typical-night diff < 60 s at `-Xmx256m`.
- [ ] `docker compose --profile observability up -d`: Prometheus shows both targets UP and the alert rules loaded; the Grafana dashboard renders.
- [ ] `scripts/check-tests.sh` passes: every test class named in §17 executed with 0 failures, 0 errors, 0 skipped.
- [ ] Every test named in §17 exists and passes; `CoreIsPureTest` passes; no `@Disabled` anywhere (`grep -r "@Disabled" --include=*.java` is empty).
- [ ] No PII canary appears anywhere (`NoPiiIT` green).
- [ ] README, design note (≤2 pages), decisions, benchmark, Postman collection complete; no `TODO` except `VIDEO_LINK_TODO`.
- [ ] Git history shows the phase commits in order; tag `v1.0`.

---

## 22. Human handoff (not done by the agent)

- Record the demo video (plan §18 script) and replace `VIDEO_LINK_TODO` in the README.
- Create the **public** GitHub repository, push all commits **and** the `v1.0` tag (`git push --follow-tags`), and confirm the repo is visible when logged out.
- Fresh-clone check in a second folder or on a second machine: `docker compose up --build`, then `scripts/demo/run-all.sh`.
- Read the version-gate code (§8) and reconciliation apply (§9.3) yourself; rehearse the plan's §19 ownership checklist without AI.
- Submit the repository link (and video link) through the channel named in the assessment email before the deadline.
