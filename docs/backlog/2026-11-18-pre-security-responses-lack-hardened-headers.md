# 413 written by RequestBodyLimitFilter lacks security headers
- **Found during:** 003 (run-task round 1 review)
- **Location:** services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/web/ProblemResponses.kt
- **Severity:** low
- **What:** The filter runs before Spring Security's `HeaderWriterFilter`, so its 413 response has no
  `X-Content-Type-Options: nosniff`, CSP, HSTS or `X-Frame-Options`.
- **Why it matters:** Inconsistent hardening across responses; scanners flag it.
- **Suggested fix:** Have `ProblemResponses.write` set at least `X-Content-Type-Options: nosniff` (and
  `Cache-Control: no-store`), or move header writing to a filter ordered before the body limit.
