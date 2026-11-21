# `migrate` wide event has no outcome when an `Error` is thrown
- **Found during:** 008 (run-task round 1 review)
- **Location:** services/core/src/main/kotlin/dev/danya/tastebuds/core/MigrationCommand.kt
- **Severity:** low
- **What:** The command catches `Exception` only (per spec). On an `Error` (e.g. `NoClassDefFoundError`), the event
  emitted in `finally` has no `outcome` and is logged at INFO.
- **Why it matters:** Dashboards and digests that filter on `outcome=error` miss the failed job.
- **Suggested fix:** In `finally`, set `outcome=error` when no outcome is set yet.
