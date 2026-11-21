# `bootTestRun` may register test controllers nested in test classes
- **Found during:** planning of task 014 (spec audit 2026-11-21)
- **Location:** services/core/src/test/kotlin/dev/danya/tastebuds/core/ (e.g. `ErrorResponsesIntegrationTest.ThrowingController`);
  `./gradlew :services:core:bootTestRun`
- **Severity:** low
- **What:** `bootTestRun` runs the app from the test classpath without Spring Boot's test-only component-scan filter,
  so controllers nested in test classes may be registered in local dev (e.g. an endpoint that throws on purpose).
  UNVERIFIED: inferred from source, not run.
- **Why it matters:** Local dev would expose test-only endpoints; confusing and a bad habit for later profiles.
- **Suggested fix:** Run `bootTestRun` and list the mappings (`/actuator/mappings` on the management port); if the test
  controllers appear, exclude them (e.g. `@TestConfiguration`-only registration or a scan filter in the test app).
