# Tastebuds API

Find new media through people who share your *vibe*, across film, TV, anime, games, books, music and podcasts.
Tastebuds matches users on **why** they love things, and links works across media
(*"this book reads like this Chopin nocturne sounds"*).

This is the backend: an open-source, production-minded Kotlin/Spring system built with an emphasis on
observability, security, scalability and transparent agentic development.

> Status: **Phase 0, foundations.** See the [plan](docs/PLAN.md) for architecture, roadmap and decisions.

## Highlights
- **Modular monolith** (Spring Modulith, boundaries verified in CI) shipped as one image running `api` / `worker` roles
- **Vibe engine** (planned): LLM-extracted, media-agnostic vibe dimensions + embeddings in pgvector, explainable matches
- **LLM-ready observability**: one schema-stable *wide event* per request, correlated with OpenTelemetry traces,
  exported over OTLP (Grafana LGTM locally); digests for analytics and incident work
- **Security**: ASVS 5.0 L2 target, deny-by-default, hardened headers, supply-chain scanning ([SECURITY.md](SECURITY.md))
- **Agentic workflow**: Opus plans → Sonnet implements → Opus reviews, with tool-enforced task scope
  ([workflow](docs/agentic/WORKFLOW.md))

## Stack
Kotlin 2.3 · Java 21 (virtual threads) · Spring Boot 4.1 · Spring Security / Authorization Server · PostgreSQL 17 + pgvector ·
Flyway · Kafka · Redis · OpenTelemetry · Grafana LGTM · Testcontainers · Kubernetes (k3d / k3s) · GitHub Actions

## Run it
Requirements: JDK 21, Docker.

```bash
./gradlew check                                        # full quality gate
docker compose -f deploy/compose/compose.yaml up -d    # optional: Grafana on http://localhost:3000
./gradlew :services:core:bootTestRun                   # core API on :8080 (Postgres and Redis via Testcontainers)
curl localhost:8080/v1/meta
```
Then open Grafana → Explore → Tempo/Loki and search for service `tastebuds-core`.

## Repository map
| Path | What |
|---|---|
| `services/core` | Core API + worker (modular monolith) |
| `libs/observability` | Wide events, error fingerprints, OTLP log bridge |
| `build-logic` | Gradle convention plugins and quality gates |
| `deploy` | Local compose stack; Helm/k8s from Phase 1 |
| `docs` | [Plan](docs/PLAN.md), [tasks](docs/tasks), [backlog](docs/backlog), [wide event schema](docs/observability/WIDE_EVENT_SCHEMA.md) |
| `.claude` | Agent roles, orchestration skills, scope-guard hook |

## License
Apache License 2.0, see [LICENSE](LICENSE).
