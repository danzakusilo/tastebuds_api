# Some error paths still bypass problem+json
- **Found during:** planning of task 003 (external review of Phase 0)
- **Location:** services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/security/SecurityConfiguration.kt (CORS), Spring Boot `/error` (BasicErrorController)
- **Severity:** low
- **What:** After task 003, 401/403/413 and MVC exceptions are problem+json, but (a) CORS rejections (`CorsFilter` /
  `DefaultCorsProcessor`, 403 `Invalid CORS request` as text/plain) and (b) errors raised by Tomcat before the
  application (oversized headers, malformed requests) rendered by Boot's `/error` endpoint as Boot's default JSON are not.
- **Why it matters:** The API's documented error contract has exceptions; clients need special cases.
- **Suggested fix:** Custom `CorsProcessor` that writes problem+json via `ProblemResponses`; an `ErrorController`
  (or `ErrorAttributes`) that renders `/error` as RFC 9457 without messages. Then drop the "Known gaps" note in SECURITY.md.
