# Chunked multipart bodies can exceed the documented 1 MB request body cap
- **Found during:** 003 (run-task round 1 review)
- **Location:** services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/web/RequestBodyLimit.kt (wrapper), application.yaml
- **Severity:** low
- **What:** `getParts()` delegates through the request wrapper, so Tomcat parses multipart parts from its own stream and
  the byte counter never sees them. Boot defaults are `spring.servlet.multipart.max-request-size=10MB`,
  `max-file-size=1MB`.
- **Why it matters:** SECURITY.md says "any request body 1 MB". Low today: no authentication mechanism exists yet and
  Security rejects anonymous requests before multipart parsing.
- **Suggested fix:** Set multipart limits to 1 MB (or `spring.servlet.multipart.enabled=false` until uploads exist).
