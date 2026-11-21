# Forwarded-header trust needs a review when a sidecar mesh is added
- **Found during:** 004 (run-task round 1 review); updated by task 013 (spec audit 2026-11-21)
- **Location:** docs/security/FORWARDED_HEADERS.md (operator checklist), docs/PLAN.md §8.1 (Linkerd, Phase 6)
- **Severity:** low
- **What:** With a sidecar proxy, the app's TCP peer is the local proxy (loopback), not the ingress pod. Since task 013,
  FORWARDED_HEADERS.md no longer advises narrowing `internal-proxies` to the ingress: it keeps Boot's default range,
  which includes loopback, and limits who can connect with a NetworkPolicy. Forwarded headers therefore keep working
  with a mesh, but the trust then rests only on who can reach the pod.
- **Why it matters:** If the NetworkPolicy does not fit the mesh's traffic, or is missing, any workload that reaches the
  api pods through the mesh can choose its client address and scheme.
- **Suggested fix:** When Linkerd lands, revisit: trust loopback only, check that the NetworkPolicy still admits only
  the ingress, and add the mesh's authorization policy.
