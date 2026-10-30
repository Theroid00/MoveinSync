-- Row-Level Security on every tenant table (spec §4). FORCE makes it apply to the table owner too;
-- the runtime role is neither owner nor BYPASSRLS. current_setting(..., true) is NULL when unset,
-- so a connection that never set app.tenant_id sees no rows at all.

ALTER TABLE office ENABLE ROW LEVEL SECURITY;
ALTER TABLE office FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON office
  USING (tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE employee ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON employee
  USING (tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE employee_tombstone ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee_tombstone FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON employee_tombstone
  USING (tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE ingest_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE ingest_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ingest_event
  USING (tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE employee_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee_history FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON employee_history
  USING (tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE record_error ENABLE ROW LEVEL SECURITY;
ALTER TABLE record_error FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON record_error
  USING (tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
