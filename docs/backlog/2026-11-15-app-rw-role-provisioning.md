# V2 migration requires an `app_rw` role that no deploy artifact creates yet
- **Found during:** 002 (human review); narrowed after 008 (spec audit 2026-11-21)
- **Location:** services/core/src/main/resources/db/migration/V2__app_rw_grants.sql
- **Severity:** medium
- **What:** V2 grants to `app_rw` and fails if the role is missing (by design). Task 008 documented the role contract,
  the bootstrap SQL for your own Postgres and the environment variables in docs/deploy/DATABASE.md, and the Helm chart
  (009) runs Flyway as the `migrate` Job with the schema owner. Remaining: no deploy artifact provisions the roles
  (`migrator`, `app_rw`, the runtime login role) in a cluster; only
  `src/test/resources/testcontainers/postgres-init.sql` creates them.
- **Why it matters:** The first cluster deploy (Phase 1, k3d/CloudNativePG) fails with "role app_rw does not exist"
  until something provisions the roles.
- **Suggested fix:** Task 010: provision the roles with CloudNativePG managed roles or bootstrap SQL, following
  docs/deploy/DATABASE.md.
