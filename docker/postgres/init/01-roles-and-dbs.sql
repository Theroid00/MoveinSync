-- Runs once at container init as the superuser (docker-entrypoint-initdb.d).
-- Per database: an owner role (runs Flyway, owns tables) and an app role (runtime;
-- not owner, no BYPASSRLS), so FORCE ROW LEVEL SECURITY applies to every runtime query.
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
-- Default timeouts (owners can't alter roles from migrations).
ALTER ROLE roster_app SET statement_timeout = '5s';
ALTER ROLE booking_app SET statement_timeout = '5s';
