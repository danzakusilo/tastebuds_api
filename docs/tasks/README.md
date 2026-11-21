# Task specs

Every change made by an implementer agent starts as a spec in this folder: `NNN-short-slug.md`, created from
[TEMPLATE.md](TEMPLATE.md) by the planner. See [docs/agentic/WORKFLOW.md](../agentic/WORKFLOW.md).

| ID | Title | Phase | Status |
|----|-------|-------|--------|
| [001](001-rate-limiting.md) | Cost-weighted rate limiting per client IP (Redis + Bucket4j; revised 2026-11-20, depends on 003–005) | 1 | DONE |
| [002](002-db-and-wide-event-hardening.md) | Harden DB wiring (pgvector search path, Flyway connection, non-superuser tests) and fail-safe wide events | 0 | DONE |
| [003](003-error-responses-and-body-limit.md) | problem+json for security errors, request body cap, truthful security docs, LICENSE | 0 | DONE |
| [004](004-forwarded-headers.md) | Trust forwarded headers only from internal proxies (depends on 003) | 0 | DONE |
| [005](005-build-and-scope-guard-hardening.md) | Build info without time, dependency locking, fail-closed scope guard | 0 | DONE |
| [006](006-anon-session-tokens.md) | Anonymous sessions: signed bearer tokens, `POST /v1/sessions/anonymous`, authentication | 1 | DONE |
| [007](007-rate-limit-per-anon-session.md) | Rate-limit bucket per anonymous session, in addition to the IP bucket (depends on 006) | 1 | DONE |
| [008](008-migrate-command-and-db-roles.md) | `migrate` command (Flyway as a separate process with the non-superuser schema owner), test roles mirror production, DB role docs | 1 | READY |
| [009](009-helm-chart-core.md) | Helm chart for core: api/worker Deployments, migration Job hook, restricted pod security, `scripts/k8s/lint.sh` in CI (depends on 008) | 1 | READY |
| [010](010-local-k3d-cluster.md) | Local k3d cluster: pinned tools, CloudNativePG with roles and pgvector, Redis (depends on 008, 009) | 1 | READY |
| [011](011-deploy-core-local-smoke.md) | Deploy core to k3d with in-cluster LGTM; exit-criterion smoke test (depends on 009, 010) | 1 | READY |
