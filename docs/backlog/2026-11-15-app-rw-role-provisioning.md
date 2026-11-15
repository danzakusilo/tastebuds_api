# V2 migration requires an `app_rw` role that nothing outside tests creates
- **Found during:** 002 (human review)
- **Location:** services/core/src/main/resources/db/migration/V2__app_rw_grants.sql
- **Severity:** medium
- **What:** V2 grants to `app_rw` and fails if the role is missing (by design). Only
  `src/test/resources/testcontainers/postgres-init.sql` creates it; no deploy artifact or doc does. Running the app
  against any other Postgres fails with "role app_rw does not exist". Separately, running without `FLYWAY_USERNAME`
  only works when the app user owns the schema.
- **Why it matters:** First deploy (Phase 1, k3d/CloudNativePG) and anyone running the app against their own database hit it.
- **Suggested fix:** Phase 1 DB-roles task: provision `migrator` and `app_rw` (CloudNativePG managed roles or a bootstrap
  SQL), run Flyway as a Job with `migrator`, and document required roles and env vars in README.
