# The default-privileges test's cleanup can hide the original failure
- **Found during:** 014 (review round 1)
- **Location:** services/core/src/test/kotlin/dev/danya/tastebuds/core/DatabaseIntegrationTest.kt:142-145
- **Severity:** low
- **What:** In `a table and a sequence that the migrator creates after V2 are usable by the app role`, a failing
  `drop table` in `finally` replaces the original assertion failure and skips `drop sequence`. Nothing else reads the
  `core` catalog and Ryuk removes the container, so the impact is only a misleading failure message.
- **Why it matters:** A broken test would report the cleanup error instead of the real cause.
- **Suggested fix:** Use `drop table if exists` / `drop sequence if exists` in separate `runCatching` blocks, or one
  `drop ... cascade` statement, so the cleanup never masks the test result.
