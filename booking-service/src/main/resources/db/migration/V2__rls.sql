-- Row-Level Security on every tenant table (spec §4); processed_message is a system table.

ALTER TABLE employee_projection ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee_projection FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON employee_projection
  USING (tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE trip ENABLE ROW LEVEL SECURITY;
ALTER TABLE trip FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON trip
  USING (tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE booking ENABLE ROW LEVEL SECURITY;
ALTER TABLE booking FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON booking
  USING (tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE seat_reservation ENABLE ROW LEVEL SECURITY;
ALTER TABLE seat_reservation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON seat_reservation
  USING (tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
