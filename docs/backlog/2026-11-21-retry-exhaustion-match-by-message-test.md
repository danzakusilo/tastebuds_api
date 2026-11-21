# No test pins the exact-class match of retry exhaustion against a message-based rewrite
- **Found during:** 012 (review round 1, mutation check)
- **Location:** services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/ratelimit/RedisRateLimiter.kt
  (`isRetryExhaustion`)
- **Severity:** low
- **What:** A mutant that matches retry exhaustion by the message prefix "CAS operation failed" instead of the exact
  class `BucketExecutionException` with no cause survives all tests. Today only Bucket4j's `maxRetriesExceeded` writes
  that text, so the mutant is equivalent in practice.
- **Why it matters:** A future refactor to a message match would turn any exception that carries that text into a 429
  instead of a fail open, without a test noticing.
- **Suggested fix:** Add a test whose supplier throws `IllegalStateException("CAS operation failed after 32 retry
  attempts")` and assert the result is `Unavailable`.
