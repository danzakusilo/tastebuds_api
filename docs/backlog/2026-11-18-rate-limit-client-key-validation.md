# Rate-limit client key must validate request.remoteAddr
- **Found during:** 004 (run-task round 1 review)
- **Location:** docs/tasks/001-rate-limiting.md (to be revised to use `request.remoteAddr`)
- **Severity:** medium
- **What:** A trusted peer can set `request.remoteAddr` to any string via `X-Forwarded-For` (reviewer probe:
  `X-Forwarded-For: not-an-ip` → `remoteAddr = not-an-ip`).
- **Why it matters:** Using it unvalidated as a Redis key allows key-space blow-up and limit evasion.
- **Suggested fix:** When revising 001, validate/normalise the address (parse as IP, collapse IPv6 to /64), fall back to a
  shared bucket on garbage, and bound key length/cardinality.
