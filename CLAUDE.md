# Tastebuds API

Backend for Tastebuds: people find new media (film, TV, anime, games, books, music, podcasts) through users with a
similar *vibe*, including cross-media links ("this book reads like this nocturne sounds").
Open source, portfolio-grade, built to carry real traffic. **Read `docs/PLAN.md` before any design work.**

## Scope discipline (most important rule)
- Do exactly what the task asks. **Never fix an unrelated issue in place**, even in the same file, even if trivial.
  Record it in `docs/backlog/` (format in `docs/backlog/README.md`) and carry on.
- When `.claude/active-task` exists, a task spec is active: you may only edit its **Allowed paths**. A hook enforces
  this. Don't work around it with shell commands; report `SCOPE_BLOCKED` instead.
- Larger work goes through the agent workflow: `/plan-task` → `/run-task`. See `docs/agentic/WORKFLOW.md`.
- Don't change decisions recorded in `docs/PLAN.md` §2 without the user's explicit agreement.

## Commands
```bash
./gradlew check                         # everything CI runs: tests, ktlint, detekt, ArchUnit, Modulith, coverage
./gradlew ktlintFormat                  # auto-format before check
./gradlew :services:core:test --tests '*WideEvent*'
./gradlew :services:core:bootTestRun    # run core locally with Testcontainers-managed Postgres and Redis (needs Docker)
docker compose -f deploy/compose/compose.yaml up -d   # Grafana LGTM at http://localhost:3000, OTLP on 4317/4318
scripts/agentic/scope.sh diff docs/tasks/NNN-x.md     # check a diff against a task's allowed paths
```

## Layout
- `build-logic/`: Gradle convention plugins (`tastebuds.kotlin`, `tastebuds.spring-library`, `tastebuds.spring-service`,
  `tastebuds.coverage-aggregate`). Versions live in `gradle/libs.versions.toml`. Build config changes go there, not in modules.
- `libs/observability/`: wide events (`WideEvent`, `WideEventFilter`), error fingerprints, OTLP log bridge. Auto-configured.
- `services/core/`: modular monolith (Spring Modulith). Top-level packages under `dev.danya.tastebuds.core` are modules;
  `platform` holds cross-cutting config (roles, security, error handling). Runs as role `api`, `worker` or `all`.
- `deploy/`: compose (local observability), later Helm/k8s. `docs/`: plan, tasks, backlog, security, observability, concepts.

## Conventions
- **Kotlin**, Java 21, Spring Boot 4 (Jackson 3: `tools.jackson.*`), virtual threads. Compiler warnings are errors.
- **Modules** talk via public types in a module's base package or via events. Never reach into another module's
  sub-packages (Modulith verification fails the build).
- **Roles:** HTTP controllers get `@ConditionalOnRole(Role.API)`; Kafka listeners and schedulers get `@ConditionalOnRole(Role.WORKER)`.
- **SQL:** jOOQ or bound parameters only. String-built SQL is forbidden (ArchUnit bans `java.sql.Statement`).
- **Errors:** throw domain exceptions; `ProblemDetailsAdvice` maps them to RFC 9457 problem+json. Never leak messages
  of unexpected exceptions to clients.
- **Observability:** enrich the current request's wide event (`WideEvent.current()?.put("module.field", value)`)
  instead of adding ad-hoc log lines. Keys are lowercase dotted; values low-cardinality scalars; never PII or secrets
  (sensitive-looking keys are auto-redacted, but don't rely on it). Document new fields in
  `docs/observability/WIDE_EVENT_SCHEMA.md`.
- **Security:** deny by default; every new endpoint states its auth rule in `SecurityConfiguration` and has a negative
  test (unauthenticated / wrong owner). Validate and bound all input (sizes, page limits). No secrets in the repo:
  it is public.
- **Tests:** JUnit 5 + kotlin.test; integration tests use Testcontainers via `TestcontainersConfiguration` and
  `@ActiveProfiles("test")`. Test names are sentences in backticks. Behaviour, not implementation.
- **Dependencies:** add versions to `gradle/libs.versions.toml` (or rely on the Spring Boot BOM); justify new ones in the task spec.
- **Commits:** Conventional Commits (`feat(catalog): ...`), referencing the task ID when there is one.
