-- The runtime role is deliberately not the table owner (RLS must apply to it), so it cannot ANALYZE
-- staging_employee by default. PostgreSQL 17+ grants exactly that capability via MAINTAIN, letting the
-- reconciliation refresh planner statistics for a freshly staged run (spec §9.2).
GRANT MAINTAIN ON TABLE staging_employee TO roster_app;
