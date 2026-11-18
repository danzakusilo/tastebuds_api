# Narrowing internal-proxies to the ingress CIDR breaks once a sidecar mesh is added
- **Found during:** 004 (run-task round 1 review)
- **Location:** docs/security/FORWARDED_HEADERS.md (operator checklist), docs/PLAN.md §8.1 (Linkerd, Phase 6)
- **Severity:** low
- **What:** With a sidecar proxy, the app's TCP peer is the local proxy (loopback), not the ingress pod. Narrowing
  `internal-proxies` to the ingress CIDR would then stop honouring forwarded headers entirely.
- **Why it matters:** Either client IP/scheme are lost (HSTS off, rate limiting per pod) or the range is widened again.
- **Suggested fix:** When Linkerd lands, revisit: trust loopback only, and enforce who can reach the pod via NetworkPolicy
  / mesh authorization policy instead of the IP range.
