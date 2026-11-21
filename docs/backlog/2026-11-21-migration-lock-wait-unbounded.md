# Migration lock waits have no bound, and the migrate Job's deadline does not stop them
- **Found during:** spec audit 2026-11-21 (tasks 002, 008; checklist rule 4)
- **Location:** services/core/src/main/resources/application.yaml:20-29 (Flyway connection: no `statement_timeout`,
  no `lock_timeout`; removed by docs/tasks/002-db-and-wide-event-hardening.md §2), MigrationCommand.kt:45,
  deploy/helm/tastebuds-core/values.yaml (`migration.activeDeadlineSeconds: 600`)
- **Severity:** medium
- **What:** A DDL migration that waits for an ACCESS EXCLUSIVE lock behind an app transaction keeps its place in the
  lock queue after the Job deadline kills the pod: Postgres does not notice the dead client
  (`client_connection_check_interval` = 0). App queries on that table queue behind it and fail at the app's 5 s
  `statement_timeout`. The audit reproduced it in `pgvector/pgvector:0.8.7-pg17` (`kill -9` on the migrator client;
  `pg_stat_activity` still shows the backend waiting on `Lock`; a new app `SELECT` times out). Flyway's own lock uses
  `pg_try_advisory_lock` + `lockRetryCount`, so a `lock_timeout` does not affect it. The connect phase fits:
  6 retries ≈ 63 s backoff + 7 × 10 s pgjdbc `connectTimeout` ≈ 133 s < 600 s.
- **Why it matters:** The first migration that alters an existing table can take the api down for longer than the
  Job runs.
- **Suggested fix:** Before the first such migration: set `lock_timeout` on the migration connection only (e.g.
  `spring.flyway.init-sqls`), consider `client_connection_check_interval`, add the Limits chain
  `lock_timeout` < Job deadline < Helm `--timeout`, and state the rule in docs/deploy/DATABASE.md "Rule for migrations".
