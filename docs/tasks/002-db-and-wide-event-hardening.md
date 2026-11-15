# 002 — Harden the database wiring and make wide events fail-safe

- **Status:** DONE
- **Phase:** 0 (hardening from the 2026-11-15 external review, items 1–4)
- **Planned by:** claude-opus-5-5 on 2026-11-15
- **Depends on:** none

## Goal
Fix four verified Phase 0 defects in the data layer and the wide-event library (docs/PLAN.md §5, §7, §8.4):
1. pgvector is unusable from the app: Hikari's `currentSchema` is `core` only, so the `vector` type and its operators
   (installed in `public`) are not on the search path.
2. Flyway shares the app pool, so migrations inherit `statement_timeout=5000` and the app's role. Flyway gets its own
   connection; integration tests run the app as a **non-superuser** member of `app_rw` (PLAN §8.4 role names) while
   Flyway runs as the owner, so privilege problems surface in tests instead of in production.
3. `Redaction` matches substrings, so the reserved fields `llm.tokens_in` / `llm.tokens_out` are redacted. Matching
   moves to key segments.
4. `WideEvent.put` throws on an invalid key, so telemetry can fail a request. It must never throw.

## Non-goals
- Provisioning DB roles in real environments (CloudNativePG / Helm / Flyway-as-a-Job are Phase 1). This task only
  adds the grants migration and the test-time roles.
- A `readonly` role, jOOQ, or any new table.
- Changing V1 (applied migrations are immutable).
- Any other change to `Redaction` (sensitive word list stays exactly as it is, string truncation unchanged) or to
  `WideEventFilter`, `Slf4jWideEventSink`, `ErrorFingerprint`.
- Hardening `Redaction.sanitize` against exotic `CharSequence.toString()` failures; only the key-validation throw is in scope.
- SSE/async wide-event timing (`docs/backlog/2026-11-15-wide-event-async-responses.md`), task 001.

## Allowed paths
- `services/core/src/main/resources/application.yaml`
- `services/core/src/main/resources/db/migration/V2__app_rw_grants.sql`
- `services/core/src/test/kotlin/dev/danya/tastebuds/core/TestcontainersConfiguration.kt`
- `services/core/src/test/kotlin/dev/danya/tastebuds/core/DatabaseIntegrationTest.kt`
- `services/core/src/test/resources/testcontainers/postgres-init.sql`
- `libs/observability/src/main/kotlin/dev/danya/tastebuds/observability/Redaction.kt`
- `libs/observability/src/main/kotlin/dev/danya/tastebuds/observability/WideEvent.kt`
- `libs/observability/src/test/kotlin/dev/danya/tastebuds/observability/RedactionTest.kt`
- `libs/observability/src/test/kotlin/dev/danya/tastebuds/observability/WideEventTest.kt`
- `docs/observability/WIDE_EVENT_SCHEMA.md`

## Design notes

### 1. Search path (application.yaml)
`spring.datasource.hikari.data-source-properties.currentSchema: core,public` (pgjdbc accepts a comma-separated list and
sets `search_path`). Update the adjacent comment: `core` first so unqualified tables resolve to the app schema, `public`
for extension types/operators (pgvector). Keep `options: "-c statement_timeout=5000"` unchanged.

### 2. Flyway connection (application.yaml)
Add under `spring.flyway` (keep `schemas` and `default-schema`):
```yaml
    # Own connection, not the app pool: no statement_timeout (migrations may legitimately run long) and the
    # schema-owning role (`migrator` in production, docs/PLAN.md §8.4). Falls back to the app credentials, which
    # only works when the app user owns the schema (V2 revokes `app_rw` access to flyway_schema_history).
    user: ${FLYWAY_USERNAME:${spring.datasource.username}}
    password: ${FLYWAY_PASSWORD:${spring.datasource.password}}
```
Rationale (verified in Boot 4.1.2 `FlywayAutoConfiguration.getMigrationDataSource`): when `spring.flyway.user` is
non-null, Boot builds a separate `SimpleDriverDataSource` derived from the app DataSource's url/username/password only,
so Hikari `data-source-properties` (`statement_timeout`, `currentSchema`) are not inherited. Do not set `spring.flyway.url`.

### 3. Grants migration `V2__app_rw_grants.sql`
Exactly these statements (comment header included):
```sql
-- Least-privilege runtime role (docs/PLAN.md §8.4). `app_rw` is a NOLOGIN group role provisioned outside Flyway
-- (infrastructure in deployed environments, testcontainers/postgres-init.sql in tests); login roles are its members.
-- Flyway runs as the schema owner, so ALTER DEFAULT PRIVILEGES covers every table later migrations create.
GRANT USAGE ON SCHEMA core TO app_rw;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA core TO app_rw;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA core TO app_rw;
ALTER DEFAULT PRIVILEGES IN SCHEMA core GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_rw;
ALTER DEFAULT PRIVILEGES IN SCHEMA core GRANT USAGE, SELECT ON SEQUENCES TO app_rw;
REVOKE ALL ON core.flyway_schema_history FROM app_rw;
```
The migration fails if `app_rw` does not exist. That is intended: deploying without the role must fail loudly.

### 4. Test infrastructure
`services/core/src/test/resources/testcontainers/postgres-init.sql` (runs once as the container superuser):
```sql
-- Test-only roles mirroring production (docs/PLAN.md §8.4). Throwaway container credentials, not secrets.
CREATE ROLE app_rw NOLOGIN;
CREATE ROLE tastebuds_app LOGIN PASSWORD 'test' IN ROLE app_rw;
```
`TestcontainersConfiguration` (also used by `bootTestRun`, so local dev gets the same setup):
- Remove `@ServiceConnection`: in Boot 4.1 it registers both `JdbcConnectionDetails` **and**
  `FlywayConnectionDetails` (`FlywayContainerConnectionDetailsFactory`) with the superuser, which would hide privilege bugs.
- Container bean: same image, plus `.withInitScript("testcontainers/postgres-init.sql")`.
- New bean `fun postgresProperties(postgres: PostgreSQLContainer): DynamicPropertyRegistrar`
  (`org.springframework.test.context.DynamicPropertyRegistrar`) that registers:
  - `spring.datasource.url` → `postgres.jdbcUrl`
  - `spring.datasource.username` → `"tastebuds_app"`, `spring.datasource.password` → `"test"`
  - `spring.flyway.user` → `postgres.username`, `spring.flyway.password` → `postgres.password` (superuser = owner;
    it can also create the non-trusted `vector` extension in V1)
  Use constants `APP_DB_USER = "tastebuds_app"` / `APP_DB_PASSWORD = "test"` in a `companion object` so the
  integration test can reference the user name.
- KDoc on the class: app connects as non-superuser `tastebuds_app` (member of `app_rw`), Flyway as the container owner.

### 5. Redaction: substring match with an exact-key exemption (`Redaction.isSensitive`)
*Amended 2026-11-15 after human review: segment matching (rounds 1–2) failed open for plurals (`auth.tokens`,
`user.emails`) and run-together names (`auth.accesstoken`). Redaction must fail closed.*

Signature unchanged: `fun isSensitive(key: String): Boolean`.
- Keep the original substring rule: sensitive if `key.lowercase()` contains any of `password`, `passwd`, `secret`,
  `token`, `authorization`, `cookie`, `api_key`, `apikey`, `email`, `phone`, `ssn` (the original list, unchanged).
- Exception: keys in `private val notSensitive = setOf("llm.tokens_in", "llm.tokens_out")` (exact, whole-key equality)
  are not sensitive. These are the counter fields reserved in `WIDE_EVENT_SCHEMA.md`. Nothing else is exempt; a new
  counter whose name contains a sensitive word must be added to this set deliberately.
- KDoc on `isSensitive` states the rule, that false positives are preferred over leaks, and where to add exemptions.

### 6. WideEvent never throws
- New constant `const val INVALID_KEYS = "wide_event.invalid_keys"` in the companion object.
- `put(key, value)`: if `key` does not match `KEY_PATTERN`, do **not** store it; increment `INVALID_KEYS` by 1 by
  writing `fields` directly (not via `put`, no recursion) and return `this`. The rejected key is never recorded
  anywhere (it may be user-derived / high-cardinality). No logging.
- `increment(key, by)`: `val current = (fields[key] as? Number)?.toLong() ?: 0L` (so an existing `Int`, e.g. from
  `put("db.query_count", 3)`, continues as `Long`); a non-numeric existing value is treated as 0 and overwritten.
  Result stored via `put` (so invalid keys are dropped and counted the same way).
- Update the class KDoc: invalid keys are dropped and counted, never thrown.

### 7. Schema doc (`WIDE_EVENT_SCHEMA.md`)
- Rules, keys bullet: replace "Enforced." with "Invalid keys are dropped (never thrown) and counted in `wide_event.invalid_keys`."
- Rules, redaction bullet: "No PII or secrets. Keys containing `password`, `passwd`, `secret`, `token`,
  `authorization`, `cookie`, `api_key`, `apikey`, `email`, `phone`, `ssn` are force-redacted (substring match: false
  positives are preferred over leaks). The only exemptions are the exact keys `llm.tokens_in` and `llm.tokens_out`;
  add new ones in `Redaction.kt` deliberately. No raw user input; no exception messages."
- Common table: add row `wide_event.invalid_keys` | long | lib | Number of `put`/`increment` calls dropped for an invalid key (absent when 0).

## Acceptance criteria
1. Through the application `JdbcClient`, `select '[1,2,3]'::vector <-> '[1,2,5]'::vector` returns `2.0` without schema qualification.
2. The application datasource connects as `tastebuds_app`, which is not a superuser, and cannot run DDL in `core`
   (`create table core.x(...)` fails with a permission error).
3. Flyway's DataSource is a different object from the application `DataSource` bean; `show statement_timeout` on it
   returns `0` while on the app datasource it returns `5s`; `current_user` on it is the container owner.
4. Migrations V1 and V2 apply on a fresh container; existing tests in `CoreApplicationIntegrationTest` and
   `ManagementEndpointsIntegrationTest` pass unchanged.
5. `llm.tokens_in` and `llm.tokens_out` are stored unredacted; every key the original substring rule redacted is still
   redacted, including plurals and run-together names (`auth.tokens`, `user.emails`, `auth.accesstoken`).
6. `WideEvent.put` and `increment` never throw for any key string; invalid keys are absent from `snapshot()` and
   `wide_event.invalid_keys` equals the number of rejected calls.
7. `increment` on a key holding an `Int` continues the count (e.g. `put("n", 3)` then `increment("n")` → `4L`).
8. `WIDE_EVENT_SCHEMA.md` reflects the new rules and field.

## Required tests
- `DatabaseIntegrationTest` (new, `services/core/src/test/kotlin/dev/danya/tastebuds/core/`; `@SpringBootTest`,
  `@ActiveProfiles("test")`, `@Import(TestcontainersConfiguration::class)`; injects `JdbcClient`, `DataSource`, `Flyway`):
  - `pgvector type and distance operator resolve through the app search path` (AC1)
  - `the app connects as a non-superuser runtime role` (`current_user` = `TestcontainersConfiguration.APP_DB_USER`,
    `select rolsuper from pg_roles where rolname = current_user` = false) (AC2)
  - `the app role cannot change the schema` (negative: `create table core.should_not_exist(id int)` throws a
    `DataAccessException`, and the table does not exist afterwards) (AC2)
  - `flyway uses its own connection without the statement timeout` (AC3; query the Flyway DataSource with
    `JdbcClient.create(flyway.configuration.dataSource)`)
- `RedactionTest` (new, unit): parameterised or table-driven
  - `redacts keys containing a sensitive word`: `user.email`, `auth.refresh_token`, `db.password_hash`,
    `http.request.header.authorization`, `client.api_key`, `client.apikey`, `session.cookie`, `oauth.client_secret`,
    `db.passwd`, `user.phone`, `user.ssn`, `auth.token`
  - `redacts plurals and run-together names`: `auth.tokens`, `user.emails`, `db.passwords`, `http.cookies`,
    `client.api_keys`, `auth.accesstoken`, `auth.refreshtoken`, `user.userpassword`
  - `exempts only the exact reserved llm token counters`: `llm.tokens_in`, `llm.tokens_out` not sensitive;
    `llm.tokens_in.raw`, `x.llm.tokens_in`, `llm.tokens_total` sensitive
  - `keeps ordinary keys`: `llm.cost_usd`, `http.route`, `api.keyboard_layout`, `db.query_count`
- `WideEventTest` (update):
  - Replace `rejects keys outside the naming convention` (it asserts the old throwing contract, which this task
    removes) with `drops and counts keys outside the naming convention instead of throwing`: `"Bad Key"`, `"trailing."`,
    `""`, `"UPPER.case"` → none in `snapshot()`, `wide_event.invalid_keys == 4L`, valid fields put in between are kept.
  - `increment with an invalid key is dropped and counted`
  - `increment continues from an existing Int value`
  - `increment overwrites a non-numeric value`
  - `llm token counters are not redacted` (`put("llm.tokens_in", 120)` → `120`)
  - Keep all other existing tests unchanged.

## Quality gates
- `./gradlew check` passes (ktlint, detekt, unit + integration tests, ArchUnit, Spring Modulith verification).
- Kover: new/changed code line coverage ≥ **80%** (global floor: 70%).
- Wide event fields added for new behaviour are documented in docs/observability/WIDE_EVENT_SCHEMA.md.
- No new dependency (all classes used are already on the classpath).

## Review log
<!-- Appended by the run-task orchestrator. One entry per round: verdict + numbered findings + resolution. -->

### Round 1: CHANGES_REQUESTED
1. AC3 test only asserts Flyway's `current_user` is not `tastebuds_app`; it must assert it equals the container owner.
   Fix: inject the `PostgreSQLContainer` bean and `assertEquals(postgres.username, <flyway current_user>)`, removing the
   `assertFalse(x == y)` pattern. (`DatabaseIntegrationTest.kt`, `flyway uses its own connection without the statement timeout`)
   Resolution: fixed in round 2 (`assertEquals(postgres.username, ...)`).

### Round 2: APPROVE
none

### Human review (after round 2): spec amended
1. Segment-based redaction failed open for plurals and run-together names (`auth.tokens`, `user.emails`,
   `auth.accesstoken`). §5 amended to substring matching with an exact-key exemption for `llm.tokens_in`/`llm.tokens_out`.
2. Flyway fallback comment was misleading (fallback only works when the app user owns the schema). §2 amended.

### Round 3: APPROVE
none
