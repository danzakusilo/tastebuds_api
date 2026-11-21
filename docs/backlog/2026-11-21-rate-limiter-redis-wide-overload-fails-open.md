# Redis-wide overload from many keys can still make the rate limiter fail open
- **Found during:** planning of task 012 (spec audit 2026-11-21; checklist rule 7)
- **Location:** services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/ratelimit/RedisRateLimiter.kt,
  RateLimitFilter.kt
- **Severity:** medium
- **What:** Task 012 bounds contention on one key (in-flight cap per key + Bucket4j retry cap → 429). A client that
  spreads thousands of parallel requests per pod over many keys (IPv6 /64s, or many anonymous sessions) can still
  overload Redis or the shared connection until calls hit the deadline, and every timed-out call fails open. With a
  session, an `Unavailable` session call also skips the IP bucket. This was true before 012; 012 does not make it
  worse.
- **Why it matters:** Fail open under load is the cost-DoS case the limiter exists for.
- **Suggested fix:** A per-client in-flight limit at the ingress (Traefik, PLAN D21), or a global in-flight limit across
  the whole limiter that rejects instead of failing open when it is full; decide in the ingress task.
