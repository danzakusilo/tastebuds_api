# The migrate command's error log can contain row data from the database
- **Found during:** spec audit 2026-11-21 (task 008)
- **Location:** services/core/src/main/kotlin/dev/danya/tastebuds/core/MigrationCommand.kt:56;
  docs/tasks/008-migrate-command-and-db-roles.md §3 step 4 ("contains no credentials")
- **Severity:** low
- **What:** The command logs the full exception. pgjdbc appends the server DETAIL by default
  (`logServerErrorDetail=true`), Flyway's `FlywaySqlException.getMessage()` appends the cause, and Postgres puts row
  values into DETAIL for constraint errors (audit: `DETAIL: Key (email)=(person@example.com) already exists.`). The
  spec checked the message only for credentials. No data migration exists yet.
- **Why it matters:** A failed data migration (backfill, dedup) would write user data to the pod log; PLAN §7 forbids
  PII in logs.
- **Suggested fix:** Decide between `logServerErrorDetail=false` on the migrate connection and keeping the detail for
  operators; record the decision in docs/deploy/DATABASE.md.
