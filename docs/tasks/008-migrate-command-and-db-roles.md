# 008 — Run migrations as a separate `migrate` command with the non-superuser schema owner

- **Status:** READY
- **Phase:** 1
- **Planned by:** claude-opus-5-5 on 2026-11-21
- **Depends on:** none

## Goal
docs/PLAN.md §11 Phase 1: "DB roles (`app_rw`, `migrator`) with Flyway as a separate Job", §8.4 least-privilege roles.
Today the app runs Flyway at startup, and tests run Flyway as the container **superuser**, so a migration that needs
superuser rights passes CI and fails in a real cluster. This task (1) adds a `migrate` command to the same image: it
applies the migrations as the schema owner, emits one wide event and exits, so the Helm chart (task 009) can run it as a
pre-install/pre-upgrade Job while api and worker pods never hold DDL credentials; (2) makes tests mirror production:
Flyway connects as the non-superuser database owner `migrator`, and the superuser only pre-creates the untrusted
`vector` extension; (3) documents the role contract and environment variables for every environment, which resolves the
documentation half of `docs/backlog/2026-11-15-app-rw-role-provisioning.md` (provisioning in k3d is task 010).

## Non-goals
- The Helm chart and its Job (009), CloudNativePG and the local cluster (010, 011).
- OTLP export from the `migrate` command: its wide event goes to the console only (see §4).
- A `readonly` role (PLAN §8.4, digests, Phase 5), jOOQ or its codegen, any new table or migration. V1 and V2 stay
  byte-identical.
- A `Role.MIGRATE` value or any change to `Role`, `OnRoleCondition` or `TastebudsProperties` (see §1 for why).
- Flyway validation at app startup: `app_rw` cannot read `flyway_schema_history` by design (V2).
- Changing the default of `spring.flyway.enabled` (tests and `bootTestRun` keep migrating at startup).
- A shared singleton Postgres container (`docs/backlog/2026-11-18-shared-testcontainers-postgres.md`).
- README changes (task 011 adds the deployment section).

## Allowed paths
- `services/core/src/main/kotlin/dev/danya/tastebuds/core/TastebudsCoreApplication.kt`
- `services/core/src/main/kotlin/dev/danya/tastebuds/core/MigrationCommand.kt`
- `services/core/src/main/resources/application.yaml`
- `services/core/src/test/kotlin/dev/danya/tastebuds/core/TestcontainersConfiguration.kt`
- `services/core/src/test/kotlin/dev/danya/tastebuds/core/DatabaseIntegrationTest.kt`
- `services/core/src/test/kotlin/dev/danya/tastebuds/core/MigrationCommandIntegrationTest.kt`
- `services/core/src/test/resources/testcontainers/postgres-init.sql`
- `docs/deploy/DATABASE.md`
- `docs/observability/WIDE_EVENT_SCHEMA.md`
- `SECURITY.md`

## Design notes

### 1. Why a separate command and not a role
The `migrate` process must start without a web server: it is a Job, and it must not open ports while it holds the
schema owner's credentials. The full application context cannot start without one, because `SecurityConfiguration`
needs the servlet-only `HttpSecurity` bean. So the command builds its own minimal context with only DataSource and
Flyway auto-configuration, from the same jar, and the main application is unchanged.

### 2. Entry point (`TastebudsCoreApplication.kt`)
```kotlin
fun main(args: Array<String>) {
    if (args.firstOrNull() == MigrationCommand.NAME) {
        exitProcess(MigrationCommand.run(args.copyOfRange(1, args.size)))
    }
    runApplication<TastebudsCoreApplication>(*args)
}
```
In the container: `args: ["migrate"]` after the image's `ENTRYPOINT` (`java ... JarLauncher`). Every other first
argument, or none, starts the application exactly as before.

### 3. `MigrationCommand.kt` (package `dev.danya.tastebuds.core`, the root package: not part of any module)
```kotlin
/**
 * `migrate`: applies the Flyway migrations as the schema owner, emits one wide event and returns the exit code
 * (docs/deploy/DATABASE.md). The Helm chart runs it as a pre-install/pre-upgrade Job from the same image; api and
 * worker pods never migrate (SPRING_FLYWAY_ENABLED=false). The context holds only a DataSource and Flyway: no web
 * server, no security, no Redis, no application modules.
 */
internal object MigrationCommand {
    const val NAME = "migrate"

    /** Runs the migration with [args] (Spring Boot command-line properties). Returns 0 on success, 1 on any failure. */
    fun run(args: Array<String>, sink: WideEventSink = Slf4jWideEventSink()): Int

    /** Starts the migration context without migrating. Opens no database connection. Visible for tests. */
    internal fun start(args: Array<String>): ConfigurableApplicationContext
}

/**
 * Not a `@Configuration`: the main application's component scan must never pick it up. Used only as the primary
 * source of [MigrationCommand]'s context.
 */
@ImportAutoConfiguration(DataSourceAutoConfiguration::class, FlywayAutoConfiguration::class)
internal class MigrationContext {
    /** Boot must not migrate during startup: [MigrationCommand] calls `migrate()` itself to report the result. */
    @Bean
    fun migrateExplicitly(): FlywayMigrationStrategy = FlywayMigrationStrategy { _ -> }
}
```
Classes: `org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration`,
`org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration`,
`org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy` (verified in Boot 4.1.2 jars).

`start(args)`: `SpringApplicationBuilder(MigrationContext::class.java)` with `.web(WebApplicationType.NONE)`,
`.bannerMode(Banner.Mode.OFF)`, `.registerShutdownHook(false)`, then `.run(*args)`. `application.yaml` is loaded as
usual, so `spring.datasource.url` (`DATABASE_URL`), `spring.flyway.user`/`password` (`FLYWAY_USERNAME`/`FLYWAY_PASSWORD`),
`schemas: core` and the structured console logging apply unchanged. Hikari connects lazily and Flyway's `load()` does
not connect, so starting the context opens no connection.

`run(args, sink)`, in this order:
1. `val event = WideEvent("job").put("job.name", NAME)`; remember `System.nanoTime()`.
2. `context = start(args)`; `val flyway = context.getBean(Flyway::class.java)` (throws
   `NoSuchBeanDefinitionException` when `spring.flyway.enabled=false`: a failure, never a silent no-op).
3. `val result = flyway.migrate()`; put `flyway.migrations_executed` = `result.migrationsExecuted` and
   `flyway.schema_version` = `flyway.info().current()?.version?.version` (absent when null); put
   `WideEvent.OUTCOME` = `Outcome.SUCCESS`. Return code `0`.
4. On any `Exception` (catch `Exception`, not `Throwable`; suppress detekt `TooGenericExceptionCaught` with a reason):
   log **one** line at ERROR on the logger of `MigrationCommand` with message `Migration failed` and the exception
   (the operator needs Flyway's message, which names the migration and the SQL error; it contains no credentials).
   Then `event.recordError(e)` and `OUTCOME` = `Outcome.ERROR`. Return code `1`.
5. `finally`: put `WideEvent.DURATION_MS`; `sink.emit(event)` **before** `context?.close()`, so the event is written
   while the logging system is fully configured. Exactly one event per run, on every path.

Named constants for the exit codes (`EXIT_OK = 0`, `EXIT_FAILED = 1`) and the nanos-per-milli divisor.

### 4. Wide event of the command
`work.kind = job`, `job.name = migrate`, `outcome`, `duration_ms`, on success `flyway.migrations_executed` (int) and
`flyway.schema_version` (string, e.g. `"2"`), on failure `error.class` and `error.fingerprint` (never the message).
No `app.role` (the command is not a role). Console only: the minimal context has no OpenTelemetry SDK, so the event
is read with `kubectl logs`, not in Loki. Documented in `WIDE_EVENT_SCHEMA.md` (§8).

### 5. `application.yaml`
Comment-only change. Under `spring.flyway`, extend the existing comment with these two lines (no key changes):
```yaml
    # In Kubernetes the api and worker pods set SPRING_FLYWAY_ENABLED=false; the `migrate` command applies migrations
    # as a separate Job before new pods start (docs/deploy/DATABASE.md).
```

### 6. Test infrastructure mirrors production
`testcontainers/postgres-init.sql` (runs once as the container superuser, connected to database `test`) becomes:
```sql
-- Test-only roles mirroring production (docs/deploy/DATABASE.md). Throwaway container credentials, not secrets.
-- Like CloudNativePG at bootstrap: the non-superuser schema owner `migrator` owns the database, and the superuser
-- pre-creates the untrusted `vector` extension, so migrations never need superuser rights.
CREATE ROLE migrator LOGIN PASSWORD 'test';
-- `test` is the Testcontainers default database name (PostgreSQLContainer without withDatabaseName).
ALTER DATABASE test OWNER TO migrator;
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
CREATE ROLE app_rw NOLOGIN;
CREATE ROLE tastebuds_app LOGIN PASSWORD 'test' IN ROLE app_rw;
```
V1's `CREATE EXTENSION IF NOT EXISTS vector` then succeeds for `migrator`: PostgreSQL checks for an existing extension
before any privilege check and only logs a notice.

`TestcontainersConfiguration`:
- `spring.flyway.user` → `MIGRATOR_DB_USER`, `spring.flyway.password` → `MIGRATOR_DB_PASSWORD` (instead of the
  container superuser).
- Companion object: add `const val MIGRATOR_DB_USER = "migrator"`, `const val MIGRATOR_DB_PASSWORD = "test"`, and
  `val POSTGRES_IMAGE: DockerImageName` = the existing `pgvector/pgvector:0.8.7-pg17` image (with
  `asCompatibleSubstituteFor("postgres")`); the bean uses it, and `MigrationCommandIntegrationTest` reuses it.
- KDoc: the app connects as `tastebuds_app` (member of `app_rw`), Flyway as the non-superuser owner `migrator`; the
  superuser only runs the init script.

### 7. `docs/deploy/DATABASE.md` (new)
Sections, in this order (concise; tables where shown):
1. **Roles** table (role, kind, privileges, used by):
   - `migrator`: LOGIN, owner of the database, not superuser; DDL, owns schema `core` and everything in it; used only
     by the `migrate` command.
   - `app_rw`: NOLOGIN group role; `USAGE` on `core`, `SELECT/INSERT/UPDATE/DELETE` on its tables, `USAGE/SELECT` on
     its sequences (V2 plus default privileges for later tables); no access to `flyway_schema_history`.
   - Runtime login role (e.g. `tastebuds_app`): LOGIN, member of `app_rw`, nothing else; used by api and worker pods.
   - `readonly`: planned for the digests (PLAN §8.4, Phase 5).
2. **Bootstrap (superuser, once per database)**: create the database owned by `migrator` and run
   `CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public` before the first migration (pgvector is untrusted, so
   only a superuser may create it). Then create `app_rw` and the login role. SQL for your own Postgres:
   ```sql
   CREATE ROLE migrator LOGIN PASSWORD '<from your secret store>';
   CREATE DATABASE tastebuds OWNER migrator;
   \c tastebuds
   CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
   CREATE ROLE app_rw NOLOGIN;
   CREATE ROLE tastebuds_app LOGIN PASSWORD '<from your secret store>' IN ROLE app_rw;
   ```
3. **Where each environment provisions them**: tests and `bootTestRun`: `testcontainers/postgres-init.sql`; local k3d
   cluster: CloudNativePG (task 010); your own Postgres: the SQL above.
4. **The `migrate` command**: `java -jar services/core/build/libs/core-<version>.jar migrate` or the image with
   argument `migrate`. Exit code 0 or 1; one wide event (`work.kind=job`); in Kubernetes a Helm pre-install/
   pre-upgrade Job (task 009) runs it before new pods start, and api/worker pods set `SPRING_FLYWAY_ENABLED=false`.
   It fails loudly when `app_rw` is missing (V2) or Flyway is disabled.
5. **Rule for migrations**: during a rolling deploy the previous release keeps serving against the new schema, so every
   migration must be backward compatible with the release before it (expand, deploy, then contract in a later release).
6. **Environment variables** table: `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` (runtime login role),
   `DATABASE_POOL_SIZE`, `FLYWAY_USERNAME`, `FLYWAY_PASSWORD` (schema owner; only the migrate process),
   `SPRING_FLYWAY_ENABLED` (`false` in api/worker pods), `SPRING_FLYWAY_CONNECT_RETRIES` (migrate Job: wait for the
   database). State that without `FLYWAY_USERNAME` Flyway falls back to the app credentials, which works only when the
   app user owns the schema.

### 8. `docs/observability/WIDE_EVENT_SCHEMA.md`
- First line: "One `wide_event` log record per unit of work (HTTP request, migration job; Kafka message later)."
- Common table, `work.kind` row: description `` `http`, `job` (later `message`) ``.
- New section after "Rate limiting":
  ```markdown
  ### Jobs (`work.kind = job`)
  | Field | Type | Description |
  |---|---|---|
  | `job.name` | string | `migrate`: the Flyway migration command (docs/deploy/DATABASE.md) |
  | `flyway.migrations_executed` | int | Migrations applied by this run (0 when the schema was current); absent when the run failed |
  | `flyway.schema_version` | string | Schema version after the run, e.g. `2`; absent when the run failed or no migration was ever applied |

  The `migrate` command writes its event to the console only (no OTLP export); read it with `kubectl logs job/<release>-migrate`.
  ```

### 9. `SECURITY.md`
Insert after the bullet that starts "Raw `java.sql.Statement` use is banned":
"- Least-privilege database roles: the app connects as a member of `app_rw` (data access in schema `core` only: no
DDL, no access to the migration history); migrations run as the non-superuser schema owner `migrator` in a separate
`migrate` process, and integration tests use the same roles. See [docs/deploy/DATABASE.md](docs/deploy/DATABASE.md)"

## Acceptance criteria
1. With first argument `migrate`, `main` runs `MigrationCommand.run` with the remaining arguments and exits with its
   code; any other first argument, or none, starts the application unchanged (review).
2. `MigrationCommand.start` returns a context that is not a `WebServerApplicationContext` and has no
   `SecurityFilterChain`, no `RedisConnectionFactory` and no `MetaController` bean, but exactly one `Flyway` bean; it
   starts successfully against an unreachable database URL (proves no connection at startup and no migration during
   startup).
3. Against a fresh database with the test roles, `run` with `migrator` credentials returns `0`; afterwards the schema
   `core` is owned by `migrator`, `app_rw` has `USAGE` on `core` and no `SELECT` on `core.flyway_schema_history`. A
   second run returns `0` and executes 0 migrations.
4. Every run emits exactly one wide event through the given `WideEventSink` with `work.kind=job`, `job.name=migrate`,
   `outcome`, `duration_ms`; success adds `flyway.migrations_executed` (equal to the number of successful `SQL` rows in
   `flyway_schema_history` on the first run, 0 on the second) and `flyway.schema_version` (equal to the highest
   applied version); failure adds `error.class` and a 12-hex `error.fingerprint` and has no `flyway.*` field.
5. Against a database without `app_rw`, `run` returns `1`; the console output contains `app_rw` (the cause is
   visible) and never the database password.
6. With `--spring.flyway.enabled=false`, `run` returns `1` and `error.class` is
   `org.springframework.beans.factory.NoSuchBeanDefinitionException`.
7. Every Spring integration test runs Flyway as `migrator`, which is not a superuser and owns schema `core`; the app
   connection still runs as `tastebuds_app` and cannot read `core.flyway_schema_history`.
8. All existing tests pass; V1 and V2 are unchanged (`git diff` shows no change under `db/migration/`).
9. `docs/deploy/DATABASE.md`, `WIDE_EVENT_SCHEMA.md` and `SECURITY.md` contain the content of §7–§9; the
   `application.yaml` change is comment-only.

## Required tests
- `MigrationCommandIntegrationTest` (new, `services/core/src/test/kotlin/dev/danya/tastebuds/core/`; plain JUnit, no
  Spring test context; `@ExtendWith(OutputCaptureExtension::class)`; a recording `WideEventSink` collects events; each
  database test starts its **own** `PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE)` and closes it with
  `use {}`; query the database as the container superuser through `JdbcClient.create(DriverManagerDataSource(...))`):
  - `migrate applies every migration as the schema owner and a second run applies nothing` (container with
    `withInitScript("testcontainers/postgres-init.sql")`; args `--spring.datasource.url=<jdbcUrl>`,
    `--spring.flyway.user=migrator`, `--spring.flyway.password=test`; AC 3, AC 4 success fields).
  - `migrate fails with exit code 1 when app_rw is missing and never logs the password` (container **without** the
    init script and `withPassword("canary-" + UUID.randomUUID())`; Flyway as the container superuser, so V1 succeeds
    and V2 fails; AC 4 failure fields, AC 5; assert `output.all` does not contain the canary password).
  - `migrate fails when flyway is disabled` (no container; `--spring.datasource.url=jdbc:postgresql://localhost:1/none`,
    `--spring.flyway.enabled=false`; AC 6).
  - `the migration context has no web server, security, redis or application modules` (no container; same unreachable
    URL; `MigrationCommand.start(...).use { ... }`; AC 2).
- `DatabaseIntegrationTest` (update):
  - `flyway uses its own connection without the statement timeout`: Flyway's `current_user` equals
    `TestcontainersConfiguration.MIGRATOR_DB_USER` (replaces the comparison with `postgres.username`; drop the
    `PostgreSQLContainer` injection if it becomes unused).
  - New `migrations run as the non-superuser owner of the core schema`: on Flyway's DataSource `rolsuper` is false for
    `current_user`, and `select pg_get_userbyid(nspowner) from pg_namespace where nspname = 'core'` returns `migrator`.
  - New `the app role cannot read the migration history` (negative): `select count(*) from core.flyway_schema_history`
    through the app `JdbcClient` throws `DataAccessException`.
  - All other tests unchanged.

## Quality gates
- `./gradlew check` passes (ktlint, detekt, unit + integration tests, ArchUnit, Spring Modulith verification).
- Kover: new/changed code line coverage ≥ **80%** (global floor: 70%). `main` itself is exempt (two lines).
- Wide event fields added for new behaviour are documented in docs/observability/WIDE_EVENT_SCHEMA.md.
- No new dependency (Spring Boot JDBC/Flyway, Testcontainers and `DriverManagerDataSource` are already on the classpath).

## Review log
<!-- Appended by the run-task orchestrator. One entry per round: verdict + numbered findings + resolution. -->
