# A request with an anonymous session can wait 2 × the rate-limit timeout in the limiter
- **Found during:** spec audit 2026-11-21 (tasks 001, 007; checklist rule 4)
- **Location:** services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/ratelimit/RateLimitFilter.kt:43-56,
  RedisRateLimiter.kt:145, RateLimitProperties.kt:19-20; docs/tasks/001-rate-limiting.md:335-336, :546-547
- **Severity:** medium
- **What:** Bucket4j starts a new deadline for every `tryConsume`. Since task 007 a request with a valid session token
  calls the store twice in sequence (session bucket, then IP bucket), and each call can use almost the full `timeout`
  and still succeed. A slow Redis therefore adds up to 2 × `timeout` (500 ms by default, 10 s at the allowed 5 s
  maximum), plus connection setup on the request that opens the connection. 007 stops after `Unavailable`, which
  covers a dead Redis but not a slow one. The audit measured it with a proxy that adds 70 ms per reply: 212 ms + 212 ms,
  both `Allowed`, at `timeout` 250 ms. 001 AC 10 ("at most `timeout`") is false for these requests.
- **Why it matters:** PLAN SLO p95 < 300 ms for reads; neither spec compares the limiter's worst case with an outer
  budget.
- **Suggested fix:** Either one deadline per request across all buckets (skip the next bucket when the budget is
  spent), or document "at most number of buckets × `timeout`" in the `timeout` KDoc and under Limits, and accept it.
