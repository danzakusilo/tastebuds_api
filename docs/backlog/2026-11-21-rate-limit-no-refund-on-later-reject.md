# Session bucket is charged when the IP bucket rejects the same request
- **Found during:** 007 (review round 1)
- **Location:** services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/ratelimit/RateLimitFilter.kt:43-56
- **Severity:** low
- **What:** The filter checks the session bucket first, then the IP bucket. When the session bucket allows and the IP
  bucket rejects, the tokens taken from the session bucket are not given back.
- **Why it matters:** A client behind a busy NAT uses up its own session bucket on 429 responses that its neighbours
  caused. Its session can then stay limited after the shared IP bucket refills.
- **Suggested fix:** Planner decision. Either record this as a known limit next to decision 1 of task 007 (and in
  `SECURITY.md`), or give the tokens back to the earlier buckets when a later bucket rejects (Bucket4j `addTokens`),
  with a test for the refund.
