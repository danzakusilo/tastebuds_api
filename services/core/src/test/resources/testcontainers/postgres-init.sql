-- Test-only roles mirroring production (docs/deploy/DATABASE.md). Throwaway container credentials, not secrets.
-- Like CloudNativePG at bootstrap: the non-superuser schema owner `migrator` owns the database, and the superuser
-- pre-creates the untrusted `vector` extension, so migrations never need superuser rights.
CREATE ROLE migrator LOGIN PASSWORD 'test';
-- `test` is the Testcontainers default database name (PostgreSQLContainer without withDatabaseName).
ALTER DATABASE test OWNER TO migrator;
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
CREATE ROLE app_rw NOLOGIN;
CREATE ROLE tastebuds_app LOGIN PASSWORD 'test' IN ROLE app_rw;
