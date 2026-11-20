# Lettuce reconnect logs bypass the limiter's one-WARN-per-minute throttle
- **Found during:** 001 (review round 1, unverified)
- **Location:** services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/ratelimit/RedisRateLimiter.kt (Lettuce client), logging config
- **Severity:** low
- **What:** When an established Redis connection drops, Lettuce's `ConnectionWatchdog` logs reconnect lines at
  INFO/WARN with its own quiet time (`LOGGING_QUIET_TIME_MS`), independent of the limiter's throttle.
- **Why it matters:** A Redis outage may produce more log lines than the limiter's design intends (log cost, noise in digests).
- **Suggested fix:** In the ops/Helm task, measure the log volume during a Redis outage; if it is too high, set a logger
  level for `io.lettuce.core.protocol`.
