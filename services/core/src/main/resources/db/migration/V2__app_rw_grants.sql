-- Least-privilege runtime role (docs/PLAN.md §8.4). `app_rw` is a NOLOGIN group role provisioned outside Flyway
-- (infrastructure in deployed environments, testcontainers/postgres-init.sql in tests); login roles are its members.
-- Flyway runs as the schema owner, so ALTER DEFAULT PRIVILEGES covers every table later migrations create.
GRANT USAGE ON SCHEMA core TO app_rw;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA core TO app_rw;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA core TO app_rw;
ALTER DEFAULT PRIVILEGES IN SCHEMA core GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_rw;
ALTER DEFAULT PRIVILEGES IN SCHEMA core GRANT USAGE, SELECT ON SEQUENCES TO app_rw;
REVOKE ALL ON core.flyway_schema_history FROM app_rw;
