# Task specs

Every change made by an implementer agent starts as a spec in this folder: `NNN-short-slug.md`, created from
[TEMPLATE.md](TEMPLATE.md) by the planner. See [docs/agentic/WORKFLOW.md](../agentic/WORKFLOW.md).

| ID | Title | Phase | Status |
|----|-------|-------|--------|
| [001](001-rate-limiting.md) | Cost-weighted rate limiting (Redis + Bucket4j) | 1 | READY |
| [002](002-db-and-wide-event-hardening.md) | Harden DB wiring (pgvector search path, Flyway connection, non-superuser tests) and fail-safe wide events | 0 | READY |
| [003](003-error-responses-and-body-limit.md) | problem+json for security errors, request body cap, truthful security docs, LICENSE | 0 | READY |
| [004](004-forwarded-headers.md) | Trust forwarded headers only from internal proxies (depends on 003) | 0 | READY |
| [005](005-build-and-scope-guard-hardening.md) | Build info without time, dependency locking, fail-closed scope guard | 0 | READY |
