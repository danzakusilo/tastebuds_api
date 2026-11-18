# Boot creates an unused in-memory user with a generated password
- **Found during:** 003 (run-task round 1 review; pre-existing)
- **Location:** services/core (Spring Boot `UserDetailsServiceAutoConfiguration`)
- **Severity:** low
- **What:** Test and app logs show "Using generated security password". httpBasic and formLogin are disabled, so the
  user is unusable, but the bean exists and the log line looks like a credential leak.
- **Why it matters:** Noise in logs; confuses security review; a future change enabling a login mechanism would expose it.
- **Suggested fix:** Exclude `UserDetailsServiceAutoConfiguration` (or define an empty `UserDetailsService`) until the
  OAuth2 resource server lands.
