# Some error paths still bypass problem+json
- **Found during:** planning of task 003 (external review of Phase 0); corrected by task 013 (spec audit 2026-11-21)
- **Location:** services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/security/SecurityConfiguration.kt (CORS), Spring Security `FilterChainProxy` (firewall), Spring Boot `/error` (BasicErrorController), Tomcat `ErrorReportValve`
- **Severity:** low
- **What:** After task 003, 401/403/413 and MVC exceptions are problem+json, but these responses are not:
  (a) CORS rejections (`CorsFilter` / `DefaultCorsProcessor`): 403, body `Invalid CORS request`, no `Content-Type`;
  (b) requests the Spring Security firewall (`StrictHttpFirewall`) rejects, for example a `;`, `//` or an encoded `.`
  in the path: `HttpStatusRequestRejectedHandler` calls `sendError(400)`, and Boot's `/error` writes its default JSON
  `{timestamp,status,error,path}` without `X-Content-Type-Options`;
  (c) errors that Tomcat raises before the application, for example oversized headers or an encoded `/` in the path:
  Tomcat writes its own text/html 400 page, and the request never reaches `/error`.
- **Why it matters:** The API's documented error contract has exceptions; clients need special cases.
- **Suggested fix:** Custom `CorsProcessor` that writes problem+json via `ProblemResponses`; an `ErrorController`
  (or `ErrorAttributes`) that renders `/error` as RFC 9457 without messages, for (b); a Tomcat-level change for (c),
  for example a custom `ErrorReportValve`, because an `ErrorController` never sees these requests. Then drop the
  "Known gaps" note in SECURITY.md.
