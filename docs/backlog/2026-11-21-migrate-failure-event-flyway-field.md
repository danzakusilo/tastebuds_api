# `migrate` failure event can carry `flyway.migrations_executed`
- **Found during:** 008 (run-task round 1 review)
- **Location:** services/core/src/main/kotlin/dev/danya/tastebuds/core/MigrationCommand.kt:46-52
- **Severity:** low
- **What:** `flyway.migrations_executed` is put before `flyway.info()` runs. If `info()` throws after a successful
  `migrate()`, the failure event already holds that field.
- **Why it matters:** WIDE_EVENT_SCHEMA.md says `flyway.*` fields are absent when the run failed.
- **Suggested fix:** Compute both values first, then put both.
