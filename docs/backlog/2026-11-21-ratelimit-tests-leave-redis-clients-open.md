# Rate-limit tests leave Lettuce clients open
- **Found during:** 012 (review rounds 1 and 2)
- **Location:** services/core/src/test/kotlin/dev/danya/tastebuds/core/platform/ratelimit/RedisRateLimiterContentionTest.kt,
  RedisRateLimiterTest.kt
- **Severity:** low
- **What:** Both test classes create `RedisClient`s and never shut them down. The event-loop threads belong to the
  shared `ClientResources`, which each class shuts down in `@AfterAll`, so no threads leak; but each client keeps its
  connections open until then. `RedisRateLimiterFailOpenTest` already shuts its proxy clients down in `finally`.
- **Why it matters:** Extra open connections to the shared Redis container add noise to the load-sensitive
  contention tests.
- **Suggested fix:** Shut each client down in `@AfterEach`/`@AfterAll` (or `use`-style helpers), as the fail-open test
  does.
