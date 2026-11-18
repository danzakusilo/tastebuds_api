# Every distinct Spring test context starts its own Postgres container
- **Found during:** 004 (run-task round 1 review)
- **Location:** services/core/src/test/kotlin/dev/danya/tastebuds/core/TestcontainersConfiguration.kt
- **Severity:** low
- **What:** The Postgres container is declared as a bean, so each distinct context (different imports, properties,
  web environment) starts a new container. Task 004 adds two such contexts; more will follow.
- **Why it matters:** Test time and Docker resource use grow with each context variant.
- **Suggested fix:** A shared singleton container (static, started once per JVM, Ryuk cleans up) with
  `@ServiceConnection`, or reduce context variants.
