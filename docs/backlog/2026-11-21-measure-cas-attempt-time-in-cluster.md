# Measure the rate limiter's compare-and-swap attempt time in the cluster
- **Found during:** planning of task 012 (spec audit 2026-11-21; checklist rule 4)
- **Location:** services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/ratelimit/RedisRateLimiter.kt
  (`MAX_CAS_ATTEMPTS` = 32, request timeout 250 ms)
- **Severity:** low
- **What:** Task 012 caps Bucket4j at 32 compare-and-swap attempts inside the 250 ms deadline. That holds only while
  one attempt (GET + EVAL round trip) takes less than about 7.8 ms; slower attempts reach the deadline first, and those
  contended calls fail open (at most 16 per key per pod, because of the in-flight cap). Marked UNVERIFIED in the 012
  spec: localhost round trips are far faster than in-cluster ones (and Linkerd later adds latency).
- **Why it matters:** The contention fix depends on this timing.
- **Suggested fix:** When task 011 deploys to k3d, measure the p99 attempt time (e.g. a Bucket4j listener or a timer
  around `tryConsume`); if it exceeds about 7 ms, lower `MAX_CAS_ATTEMPTS` or raise the timeout, and record the value
  under Limits.
