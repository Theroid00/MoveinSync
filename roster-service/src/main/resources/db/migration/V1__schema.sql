-- roster DB schema (spec §5.1). Runs as roster_owner; roster_app gets DML via default privileges.

CREATE TABLE tenant_source (
  tenant_id text NOT NULL, source_id text NOT NULL,
  lock_key bigint NOT NULL UNIQUE,                       -- advisory-lock key, collision-free per source
  accepted_snapshot_cutoff bigint NOT NULL DEFAULT 0,
  absence_watermark bigint NOT NULL DEFAULT 0,           -- last cutoff that inferred absences
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
  pickup_point text, special_assistance text,                                         -- transport-owned
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
