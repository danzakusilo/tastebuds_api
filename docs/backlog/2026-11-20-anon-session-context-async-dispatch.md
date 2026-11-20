# Anonymous-session authentication is not saved in the security context repository, so async dispatches lose it
- **Found during:** 006 (review round 1)
- **Location:** services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/security/AnonSessionAuthentication.kt (`AnonSessionAuthenticationFilter`)
- **Severity:** medium
- **What:** The filter sets the `SecurityContext` but does not save it through the `SecurityContextRepository`. With
  `STATELESS`, Spring Security uses `RequestAttributeSecurityContextRepository`, and `AuthorizationFilter` also checks
  ASYNC dispatches by default, while the filter skips them (`OncePerRequestFilter`).
- **Why it matters:** The first async endpoint for anonymous sessions (SSE or streaming, likely Phase 3 quick-match
  refinement) loses the `ANON_SESSION` authentication on the async dispatch and returns 401/403.
- **Suggested fix:** Save the context with `RequestAttributeSecurityContextRepository` after authentication (as
  `BearerTokenAuthenticationFilter` does), with a test on an async endpoint. Do it before the first async endpoint that
  requires `ANON_SESSION`.
