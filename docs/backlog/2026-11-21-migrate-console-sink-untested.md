# No test runs the `migrate` command with its production wide-event sink
- **Found during:** 008 (run-task round 1 review)
- **Location:** services/core/src/main/kotlin/dev/danya/tastebuds/core/MigrationCommand.kt:37
- **Severity:** low
- **What:** All tests pass a recording `WideEventSink`; the default `Slf4jWideEventSink()` is never exercised.
- **Why it matters:** Nothing proves the event reaches the console as a `wide_event` record while logging is still
  configured (before `context.close()`), which is how operators read it (`kubectl logs`).
- **Suggested fix:** One OutputCapture test calling `run(args)` without a sink, asserting a `wide_event` line with `job.name`.
