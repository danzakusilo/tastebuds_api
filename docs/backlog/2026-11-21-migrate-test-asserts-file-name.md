# Missing-`app_rw` test passes on the migration file name, not on the error
- **Found during:** 008 (run-task round 1 review)
- **Location:** services/core/src/test/kotlin/dev/danya/tastebuds/core/MigrationCommandIntegrationTest.kt:115
- **Severity:** low
- **What:** The test asserts `output.all.contains("app_rw")`. Flyway's own log line names `V2__app_rw_grants.sql`,
  which also satisfies the check, so the test still passes if `MigrationCommand` stops logging `Migration failed`.
- **Why it matters:** AC 5 of task 008 ("the cause is visible") is not actually proven.
- **Suggested fix:** Assert `Migration failed` and `role "app_rw" does not exist`.
