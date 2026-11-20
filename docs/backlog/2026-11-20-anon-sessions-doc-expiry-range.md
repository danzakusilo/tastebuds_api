# ANON_SESSIONS.md verification order does not mention the out-of-range expiry case
- **Found during:** 006 (review round 2)
- **Location:** docs/security/ANON_SESSIONS.md:26-28 ("Verification order")
- **Severity:** low
- **What:** The amended spec 006 §3 step 5 maps a correctly signed token whose expiry is outside the `Instant` range to
  `MALFORMED`. The doc only says "the payload is decoded", which covers this loosely.
- **Why it matters:** Readers of the security doc do not learn that `verify` never throws on this input.
- **Suggested fix:** Add a short clause to the "Verification order" paragraph the next time the doc is edited.
