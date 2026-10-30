-- booking DB schema (spec §5.2). Runs as booking_owner; booking_app gets DML via default privileges.

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
