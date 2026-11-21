# `ClientKey.toString()` prints the client address or session id
- **Found during:** 007 (review round 1)
- **Location:** services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/ratelimit/ClientKeys.kt:16
- **Severity:** low
- **What:** `ClientKey` is a data class, so its generated `toString()` includes `value`, which is a client IP address
  or an anonymous session id.
- **Why it matters:** No code logs a `ClientKey` today. A future `log.debug("{}", key)`, an exception message or a test
  assertion message would put personal data or a session id into logs and wide events.
- **Suggested fix:** Override `toString()` to redact `value`, as `BearerCredential.Present` does
  (`platform/security/BearerCredentials.kt:14`), and add a unit test for it.
