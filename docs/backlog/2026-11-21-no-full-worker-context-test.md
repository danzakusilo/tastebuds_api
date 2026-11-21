# No test starts the full `worker` role context
- **Found during:** planning of task 014 (spec audit 2026-11-21)
- **Location:** services/core/src/test/kotlin/dev/danya/tastebuds/core/ (no `@SpringBootTest` with `tastebuds.role=worker`)
- **Severity:** low
- **What:** Task 014 adds an ArchUnit rule that every controller carries `@ConditionalOnRole(Role.API)`, which is the
  cheaper check for the api-only contract. No test starts the complete worker context, so a worker-only startup failure
  that is not caused by a controller (e.g. a non-controller bean that needs an api-only bean) is found only by the
  k3d smoke test (task 011, local only).
- **Why it matters:** The worker role is deployed (chart 009) but never booted in CI.
- **Suggested fix:** One `@SpringBootTest(properties = ["tastebuds.role=worker"])` context test once the worker has its
  first real job (it needs its own context and Postgres container, about 5 s).
