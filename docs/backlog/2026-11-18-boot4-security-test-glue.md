# `@WithMockUser` silently does nothing with `@AutoConfigureMockMvc` (Boot 4 security test module missing)
- **Found during:** 003 (run-task round 1 review)
- **Location:** services/core/build.gradle.kts (test dependencies)
- **Severity:** medium
- **What:** Boot 4 moved `SecurityMockMvcAutoConfiguration` (applies `springSecurity()` to the autoconfigured MockMvc)
  into `org.springframework.boot:spring-boot-security-test`, pulled in by `spring-boot-starter-security-test`. Only the
  bare `spring-security-test` is on the classpath, so `@WithMockUser` has no effect: a controller throwing
  `AccessDeniedException` returns 401 instead of 403 (verified by the reviewer in a scratch copy).
- **Why it matters:** Every future authenticated or wrong-owner negative test (required by CLAUDE.md) will hit this and
  either hand-build MockMvc (as `ErrorResponsesIntegrationTest` had to) or pass for the wrong reason.
- **Suggested fix:** `testImplementation("org.springframework.boot:spring-boot-starter-security-test")` (BOM-managed),
  replacing bare `spring-security-test`; then simplify `ErrorResponsesIntegrationTest` to the autoconfigured MockMvc.
