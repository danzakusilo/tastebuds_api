# 014 — Tests name the failure reason: SQLSTATE in least-privilege tests, V2 default privileges, controllers only in the api role

- **Status:** READY
- **Phase:** 1
- **Planned by:** claude-opus-5-5 on 2026-11-21
- **Depends on:** none

## Goal
The spec audit of 2026-11-21 (Part D: D1, D2, D4) found tests that pass for the wrong reason or cover only part of a
contract (docs/agentic/SPEC_CHECKLIST.md rules 5 and 3). This task changes tests only:
1. **D1:** the least-privilege tests of the runtime role (docs/PLAN.md §8.4, task 002 AC 2 "fails with a permission
   error") assert PostgreSQL SQLSTATE `42501` and the denied object, not "any `DataAccessException`". The `migrate`
   test for a missing `app_rw` (task 008 AC 5) asserts the Postgres error in the `Migration failed` log record, not a
   substring that the migration file name also contains. This closes
   `docs/backlog/2026-11-21-migrate-test-asserts-file-name.md`.
2. **D2:** a test proves that V2's default privileges work: a table and a sequence that `migrator` creates after V2 are
   usable by the app role, with DML rights only.
3. **D4:** the api-only role contract of controllers (CLAUDE.md "Roles", PLAN D5, task 006 AC 1 and AC 7) is enforced
   for every controller by an ArchUnit rule: removing `@ConditionalOnRole(Role.API)` from `AnonSessionController` (or
   any controller) fails `./gradlew check`.

## Non-goals
- Any change to production code, migrations, `application.yaml`, build logic, `gradle/`, CI or test resources. V2 is
  immutable (Flyway checksum).
- D3 (rate-limit fail-open tests): moved to task 012. Do not touch
  `services/core/src/test/kotlin/dev/danya/tastebuds/core/platform/ratelimit/**`.
- A full worker-role `@SpringBootTest` (rejected in §4.1).
- Documentation of V2's default-privilege limit (audit C7: tables created by a role other than `migrator` get no
  grants): task 013. No test that such a table is unusable; that is a limit, not a contract.
- Write privileges on `core.flyway_schema_history` (reason in §1.4).
- The other `migrate` backlog items (`2026-11-21-migrate-console-sink-untested.md`,
  `2026-11-21-migrate-error-event-no-outcome.md`, `2026-11-21-migrate-failure-event-flyway-field.md`) stay open. Do
  not extend the new log helper to cover them.
- A shared Postgres container (`2026-11-18-shared-testcontainers-postgres.md`).
- Similar weaknesses in other test classes: record them in `docs/backlog/`, do not fix them here.
- Renaming existing tests.

## Allowed paths
- `services/core/src/test/kotlin/dev/danya/tastebuds/core/DatabaseIntegrationTest.kt`
- `services/core/src/test/kotlin/dev/danya/tastebuds/core/MigrationCommandIntegrationTest.kt`
- `services/core/src/test/kotlin/dev/danya/tastebuds/core/ArchitectureTest.kt`
- `services/core/src/test/kotlin/dev/danya/tastebuds/architecture/ControllerRoleRuleTest.kt`
- `docs/backlog/2026-11-21-migrate-test-asserts-file-name.md`

The backlog file is deleted (§2.4); it is listed so the deletion is in scope explicitly.

## Design notes

### 1. D1: least-privilege tests assert SQLSTATE 42501 (`DatabaseIntegrationTest`)

**1.1 Why the exception type is not enough.** `JdbcClient` (spring-jdbc 7.0.10) translates SQLSTATE `42501`
(insufficient_privilege) and `42P01` (undefined table) both to `BadSqlGrammarException`, and `3F000` (schema does not
exist) to `UncategorizedSQLException`. The Spring exception message does not contain the server text. The direct cause
is `org.postgresql.util.PSQLException`, whose `sqlState` is the code and whose message holds the server text, e.g.
`ERROR: permission denied for schema core\n  Position: 14`. So the assertion reads the first `java.sql.SQLException` in
the cause chain.

**1.2 Helper** (private, in `DatabaseIntegrationTest`; exact behaviour, formatting per ktlint):
```kotlin
/** [error] must come from PostgreSQL SQLSTATE 42501 (insufficient_privilege) with [reason] in the server message. */
private fun assertPermissionDenied(
    error: DataAccessException,
    reason: String,
) {
    val cause =
        assertNotNull(
            generateSequence<Throwable>(error) { it.cause }.filterIsInstance<SQLException>().firstOrNull(),
            "no SQLException in $error",
        )
    assertEquals(INSUFFICIENT_PRIVILEGE, cause.sqlState, "unexpected SQLSTATE: ${cause.message}")
    assertContains(cause.message.orEmpty(), reason)
}

private companion object {
    const val INSUFFICIENT_PRIVILEGE = "42501"
}
```
New imports: `java.sql.SQLException`, `kotlin.test.assertContains`, `kotlin.test.assertNotNull`.

**1.3 Tests.** Every `assertFailsWith<DataAccessException>` in the class keeps its result and passes it to
`assertPermissionDenied`:

| Test | Statement (as `tastebuds_app`) | SQLSTATE | `reason` |
|---|---|---|---|
| `the app role cannot change the schema` (existing; keep the "table does not exist afterwards" check) | `create table core.should_not_exist(id int)` | 42501 | `permission denied for schema core` |
| `the app role cannot read the migration history` (existing) | `select count(*) from core.flyway_schema_history` | 42501 | `permission denied for table flyway_schema_history` |
| `the app role cannot create objects in the public schema` (new) | `create table public.should_not_exist(id int)` | 42501 | `permission denied for schema public` |
| D2 test, §3 (new) | `truncate core.default_privileges_probe` | 42501 | `permission denied for table default_privileges_probe` |

The public-schema test is the second part of the "app role cannot run DDL" contract (rule 3): the app's search path is
`core,public`, and `public` holds pgvector. Its ACL comes from PostgreSQL 15+ defaults (`public` owned by
`pg_database_owner`, no `CREATE` for `PUBLIC`), not from V2, so the core test does not cover it.

What each assertion rules out (verified): a renamed schema gives `3F000`, a renamed history table gives `42P01`, a
connection failure has no `42501`; each fails the test with the SQLSTATE in the message.

**1.4 Not tested: writes to the history table.** V2 runs `REVOKE ALL`; the realistic regression is a later migration
that runs `GRANT … ON ALL TABLES IN SCHEMA core TO app_rw` again, which restores `SELECT` too and fails the read test.

### 2. D1: the `migrate` failure test names its cause (`MigrationCommandIntegrationTest`)

**2.1 The trap.** The console log is ECS JSON (`logging.structured.format.console: ecs`; the test class has no
profile, so `application.yaml` applies). In the raw output the Postgres error appears as
`role \"app_rw\" does not exist` (quotes escaped). The backlog's literal suggestion,
`output.all.contains("role \"app_rw\" does not exist")`, does not match (verified: 0 matches) and would fail.
The existing `output.all.contains("app_rw")` also matches Flyway's file name `V2__app_rw_grants.sql`. So the test parses
the JSON records.

**2.2 Helper** (private members of the test class):
```kotlin
private val json = JsonMapper.builder().build()

/** Console log records (ECS JSON, one per line); lines that are not JSON are skipped. */
private fun logRecords(output: CapturedOutput): List<JsonNode> =
    output.all
        .lines()
        .filter { it.startsWith("{") }
        .mapNotNull { line -> runCatching { json.readTree(line) }.getOrNull() }
```
Imports: `tools.jackson.databind.JsonNode`, `tools.jackson.databind.json.JsonMapper`, `kotlin.test.assertContains`.
A line that is not JSON is never the record under test, so skipping it keeps the test from failing for an unrelated
reason.

**2.3 Changes in `migrate fails with exit code 1 when app_rw is missing and never logs the password`.**
- Replace `assertNotNull(events[0]["error.class"])` with
  `assertEquals("org.flywaydb.core.internal.exception.FlywayMigrateException", events[0]["error.class"])` (Flyway
  12.4.0; same style as the existing `NoSuchBeanDefinitionException` test. If a Flyway upgrade moves the class, this
  line fails and names the new class; update the literal then).
- Replace `assertTrue(output.all.contains("app_rw"))` with:
  ```kotlin
  val failures = logRecords(output).filter { it.path("message").asString() == "Migration failed" }
  assertEquals(1, failures.size, "expected one 'Migration failed' record")
  val failure = failures.single()
  assertEquals("ERROR", failure.at("/log/level").asString())
  assertEquals(MigrationCommand::class.java.name, failure.at("/log/logger").asString())
  assertContains(failure.at("/error/message").asString(), "role \"app_rw\" does not exist")
  ```
- Keep every other assertion, including `assertFalse(output.all.contains(password))`.

The reason under test is SQLSTATE 42704 `role "app_rw" does not exist`, logged by `MigrationCommand` (not by Flyway's
own `DbMigrate` ERROR line). If `MigrationCommand` stops logging `Migration failed`, the count assertion fails.

**2.4 Backlog.** Delete `docs/backlog/2026-11-21-migrate-test-asserts-file-name.md`: §2.3 does what it suggests
(assert `Migration failed` and `role "app_rw" does not exist`), with the JSON decoding that its literal suggestion lacks.

### 3. D2: V2's default privileges work (`DatabaseIntegrationTest`)

**3.1 Why an explicit sequence.** PostgreSQL does not check sequence privileges for identity columns: an insert into a
`generated always as identity` column succeeded after `REVOKE ALL` on its sequence (verified). A probe with an identity
column would pass without V2's sequence default. `bigserial` and explicit `nextval` do need `USAGE`. The probe therefore
creates an explicit sequence and calls `nextval` through the app.

**3.2 Test** `a table and a sequence that the migrator creates after V2 are usable by the app role`:
```kotlin
val migrator = JdbcClient.create(flyway.configuration.dataSource)
try {
    migrator.sql("create table core.default_privileges_probe(id bigint primary key, note text not null)").update()
    migrator.sql("create sequence core.default_privileges_probe_seq").update()

    val id = jdbc.sql("select nextval('core.default_privileges_probe_seq')").query(Long::class.javaObjectType).single()
    val inserted =
        jdbc.sql("insert into core.default_privileges_probe(id, note) values (:id, 'created')").param("id", id).update()
    val updated =
        jdbc.sql("update core.default_privileges_probe set note = 'updated' where id = :id").param("id", id).update()
    val note =
        jdbc
            .sql("select note from core.default_privileges_probe where id = :id")
            .param("id", id)
            .query(String::class.java)
            .single()
    val deleted = jdbc.sql("delete from core.default_privileges_probe where id = :id").param("id", id).update()
    val truncate = assertFailsWith<DataAccessException> { jdbc.sql("truncate core.default_privileges_probe").update() }

    assertEquals(1, inserted)
    assertEquals(1, updated)
    assertEquals("updated", note)
    assertEquals(1, deleted)
    assertPermissionDenied(truncate, "permission denied for table default_privileges_probe")
} finally {
    migrator.sql("drop table if exists core.default_privileges_probe").update()
    migrator.sql("drop sequence if exists core.default_privileges_probe_seq").update()
}
```
- The Flyway DataSource connects as `migrator` (asserted by the existing
  `flyway uses its own connection without the statement timeout`); `jdbc` is the app's `tastebuds_app` client.
- A missing table default fails at the insert (42501 `permission denied for table …`); a missing sequence default fails
  at `nextval` (42501 `permission denied for sequence default_privileges_probe_seq`). The truncate check proves the
  defaults grant DML only (`arwd`, no `TRUNCATE`).
- **Lifecycle (rule 6).** Created at the start of the test, on the existing Spring context's database (no new context
  or container). Dropped in `finally` with `if exists`, also when an assertion or the `create` fails. If the JVM dies,
  Ryuk removes the container. No other test uses these names.

**3.3 V2 statements and their tests.** `GRANT USAGE ON SCHEMA core` and `REVOKE ALL ON core.flyway_schema_history`:
`MigrationCommandIntegrationTest` and the read test. The two `ALTER DEFAULT PRIVILEGES`: §3.2. `GRANT … ON ALL TABLES`
and `GRANT … ON ALL SEQUENCES`: at V2 time `core` holds only `flyway_schema_history` (then revoked) and no sequence, so
these two have no observable effect to test (verified).

**3.4 One place is enough.** The default ACL rows depend only on the role that ran V2 (`defaclrole = migrator`). Both
migration paths, Boot's startup migration in Spring tests and the `migrate` command, run V2 as `migrator`; existing
tests assert that for each. So the test lives only in `DatabaseIntegrationTest`, which needs no extra container.

### 4. D4: controllers exist only in the api role (ArchUnit)

**4.1 Decision: an ArchUnit rule, not a worker-role context test.** Existing infrastructure: `ArchitectureTest`
(ArchUnit 1.5.1 JUnit 5 engine, `@AnalyzeClasses(packages = ["dev.danya.tastebuds"])`, `DoNotIncludeTests`);
`ApplicationContextRunner` tests per configuration (`AnonSessionConfigurationTest`, `RateLimitConfigurationTest`,
`RoleConditionTest`); every `@SpringBootTest` runs role `all`. No worker-role context test exists.
- ArchUnit rule: no new runtime cost (reuses `ArchitectureTest`'s class import); covers every present and future
  controller by construction; the failure names the class.
- Worker `@SpringBootTest`: a new context key, so a new Postgres container and context start (the
  `DatabaseIntegrationTest` context took 4.7 s). It fails only for a controller with an api-only dependency
  (`AnonSessionController` needs `AnonSessionIssuer`). `MetaController` without the annotation starts fine in the worker
  role (it needs only `TastebudsProperties` and `ObjectProvider<BuildProperties>`, present in every role), so this
  option does not cover every controller unless it also asserts "no controller beans".
- Trade-off: the rule checks annotations, not a running worker context. Worker startup as a whole stays covered by the
  per-configuration tests and, after audit item A4, by the task 011 smoke test.

**4.2 Rule** (in `ArchitectureTest`; shared with the self-test, so it is defined once):
```kotlin
@ArchTest
fun `controllers exist only in the api role`(classes: JavaClasses) {
    controllersOnlyInApiRole.check(classes)
}

companion object {
    private val apiRole =
        DescribedPredicate.describe("@ConditionalOnRole(Role.API)") { annotation: JavaAnnotation<*> ->
            annotation.rawType.isEquivalentTo(ConditionalOnRole::class.java) &&
                (annotation.get("value").orElse(null) as? JavaEnumConstant)?.name() == Role.API.name
        }

    /** CLAUDE.md, Roles: HTTP controllers get @ConditionalOnRole(Role.API). Shared with ControllerRoleRuleTest. */
    internal val controllersOnlyInApiRole: ArchRule =
        classes()
            .that()
            .areMetaAnnotatedWith(Controller::class.java)
            .should()
            .beAnnotatedWith(apiRole)
            .because("only the api role serves HTTP; a controller in the worker role fails startup or exposes an endpoint")
}
```
Imports: `com.tngtech.archunit.base.DescribedPredicate`, `com.tngtech.archunit.core.domain.JavaAnnotation`,
`com.tngtech.archunit.core.domain.JavaEnumConstant`, `com.tngtech.archunit.lang.ArchRule`,
`com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes`, `dev.danya.tastebuds.core.platform.ConditionalOnRole`,
`dev.danya.tastebuds.core.platform.Role`, `org.springframework.stereotype.Controller`. The name is camelCase on
purpose (ktlint `property-naming`); the predicate description must stay exactly `@ConditionalOnRole(Role.API)`, because
the self-test matches the violation text.

- **Scope.** `areMetaAnnotatedWith(Controller)` matches `@Controller` and `@RestController`, not
  `@RestControllerAdvice` (`ProblemDetailsAdvice` stays outside). It equals Spring MVC 7.0.10's handler detection
  (`RequestMappingHandlerMapping.isHandler`: `AnnotatedElementUtils.hasAnnotation(beanType, Controller.class)`), except
  that ArchUnit reads only the class's own annotations: a class that gets `@Controller` only from a superclass or
  interface is not matched. No such class exists; the convention puts the annotations on the class.
- **Wrong value counts.** `@ConditionalOnRole(Role.WORKER)` or `(Role.ALL)` breaks the rule.
- **Empty check.** ArchUnit's default `failOnEmptyShould=true` fails the rule ("failed to check any classes") when no
  class is in scope, so the rule cannot pass vacuously. Do not call `allowEmptyShould`.
- Test-source classes are not imported (`DoNotIncludeTests`), so `ErrorResponsesIntegrationTest.ThrowingController` and
  the fixtures of §4.3 do not break the rule.

Violation text (ArchUnit 1.5.1; the angle brackets are literal):
`Class <dev.danya.tastebuds.core.sessions.AnonSessionController> is not annotated with @ConditionalOnRole(Role.API) in (AnonSessionController.kt:0)`.

**4.3 Self-test** `dev.danya.tastebuds.architecture.ControllerRoleRuleTest` (plain JUnit 5, no Spring). The rule's
must-fail case is tested with its reason (rule 5), because the rule has a custom predicate. Package
`dev.danya.tastebuds.architecture` is outside the component-scan root `dev.danya.tastebuds.core`, so no Spring context
(tests or `bootTestRun`) registers the fixture controllers.
```kotlin
class ControllerRoleRuleTest {
    @RestController
    class WithoutRole

    @RestController
    @ConditionalOnRole(Role.WORKER)
    class WorkerRole

    @Controller
    class PlainWithoutRole

    @RestController
    @ConditionalOnRole(Role.API)
    class ApiRole

    @RestControllerAdvice
    class Advice

    private fun violations(vararg types: Class<*>): List<String> =
        ArchitectureTest.controllersOnlyInApiRole
            .evaluate(ClassFileImporter().importClasses(*types))
            .failureReport
            .details

    private fun expected(type: Class<*>) = "Class <${type.name}> is not annotated with @ConditionalOnRole(Role.API)"
    // tests: see "Required tests"
}
```
`evaluate` throws `AssertionError` ("failed to check any classes") when no imported class is in the rule's scope. So
`each production controller is in the rule's scope and passes it` fails if a controller drops out of scope, and the
advice is only evaluated together with `ApiRole`.

### 5. Coverage per contract (rule 3)

| Contract | Part | Test |
|---|---|---|
| App role cannot run DDL | schema `core` | `the app role cannot change the schema` (§1.3) |
| | schema `public` | `the app role cannot create objects in the public schema` (§1.3) |
| App role cannot read the migration history | `core.flyway_schema_history` | `the app role cannot read the migration history` (§1.3) |
| V2 grants on objects `migrator` creates later | tables (`arwd`) | §3.2 |
| | sequences (`rU`) | §3.2 (explicit sequence, §3.1) |
| | nothing more (`TRUNCATE`) | §3.2 |
| `migrate` failure names its cause | `app_rw` missing | §2.3 |
| | Flyway disabled | existing `migrate fails when flyway is disabled` (exact `error.class`) |
| Api-only beans absent in the worker role | `MetaController`, `AnonSessionController`, every future controller | §4.2 rule, §4.3 self-test |
| | `AnonSessionConfiguration` | existing `AnonSessionConfigurationTest` |
| | `RateLimitConfiguration` | existing `RateLimitConfigurationTest` |

### 6. Mutation evidence
The planner ran these mutations against the drafted tests (see "Assumptions verified"). The implementer does not
repeat them: the scope guard blocks edits to production code. The reviewer may repeat them in a scratch worktree.
- `AnonSessionController` compiled without `@ConditionalOnRole(Role.API)`: `ArchitectureTest` fails with the §4.2
  violation text.
- `REVOKE ALL` on the probe's sequence from `app_rw`: `nextval` fails with 42501 `permission denied for sequence …`.
- Renamed schema / history table: SQLSTATE `3F000` / `42P01` instead of `42501`.

## Assumptions verified
Image and versions: `pgvector/pgvector:0.8.7-pg17` (PostgreSQL 17.12), the locked test classpath of `services/core`
(spring-jdbc 7.0.10, Spring Boot 4.1.2, Flyway 12.4.0, ArchUnit 1.5.1, Jackson 3.1.7, Testcontainers 2.0.5), JVM
`eclipse-temurin:21-jre@sha256:18bb4a91…` (the Dockerfile's base). Main classes: `services/core/build/classes` compiled
from the current main sources (`services/` is identical to `origin/main` 68a07fa).

| Claim | How checked |
|---|---|
| Server messages are English: `lc_messages = en_US.utf8` | `show lc_messages` in the pinned image |
| As `tastebuds_app` after `postgres-init.sql` + V1 + V2 (applied by the real `migrate` command as `migrator`): `create table core.x` → 42501 `permission denied for schema core`; `select … from core.flyway_schema_history` → 42501 `permission denied for table flyway_schema_history`; `create table public.x` → 42501 `permission denied for schema public`; controls `create table nosuch.x` → 3F000, `select … from core.no_such_history` → 42P01 | psql with `VERBOSITY verbose` against the pinned image |
| `JdbcClient` maps 42501 and 42P01 to `BadSqlGrammarException`, 3F000 to `UncategorizedSQLException`; the cause is `PSQLException` with `getSQLState()` = the code and the server text in `message`; the Spring message has no server text | Java harness with `JdbcClient` as `tastebuds_app`, printed the cause chain |
| `pg_default_acl` after V2: two rows, `defaclrole = migrator`, namespace `core`: `r` → `{app_rw=arwd/migrator}`, `S` → `{app_rw=rU/migrator}` | psql query |
| Table and sequence created by `migrator` after V2 get `app_rw=arwd` / `app_rw=rU`; the app can insert, select, update, delete, `nextval`; `truncate` → 42501 `permission denied for table default_privileges_probe`; `drop` → 42501 `must be owner of table …` | psql as `migrator`, then as `tastebuds_app` |
| Identity insert needs no sequence privilege; `bigserial` insert and explicit `nextval` fail 42501 `permission denied for sequence …` without `USAGE` | psql: `REVOKE ALL` on each sequence from `app_rw`, then inserts as `tastebuds_app` |
| After V2, `core` holds only `flyway_schema_history` and its two indexes, no sequence | `pg_class` query |
| `migrate` against a database without `app_rw` (superuser credentials, as in the test): ECS console; exactly one record with `message` `Migration failed`, `log.level` `ERROR`, `log.logger` `dev.danya.tastebuds.core.MigrationCommand`, `error.type` `org.flywaydb.core.internal.exception.FlywayMigrateException`, decoded `error.message` containing `role "app_rw" does not exist` (and `V2__app_rw_grants.sql`); raw output: escaped form 1 match, unescaped form 0 matches; wide event `error.class` = `org.flywaydb.core.internal.exception.FlywayMigrateException`; password absent | ran `TastebudsCoreApplicationKt migrate …` on the locked runtime classpath; decoded with a JSON parser |
| ArchUnit 1.5.1: `areMetaAnnotatedWith(Controller)` matches direct `@Controller` and `@RestController`, not `@RestControllerAdvice`; on the main classes it matches exactly `MetaController` and `AnonSessionController`; the rule passes; violation text as in §4.2 | Java harness on the main classes and on compiled fixtures |
| `failOnEmptyShould` is on by default; `check` and `evaluate` throw "failed to check any classes" for an import with no class in scope | same harness (advice-only and empty package imports) |
| `DoNotIncludeTests` excludes `build/classes/kotlin/test`: with it `ThrowingController` is not imported, without it it is (and would break the rule) | harness with the test classes on the classpath |
| Spring MVC 7.0.10 handler detection is `AnnotatedElementUtils.hasAnnotation(beanType, Controller.class)` | `RequestMappingHandlerMapping.isHandler` in the spring-webmvc 7.0.10 sources jar |
| `AnonSessionController` compiled without the annotation → `ArchitectureTest` fails with `Class <dev.danya.tastebuds.core.sessions.AnonSessionController> is not annotated with @ConditionalOnRole(Role.API)` | kotlinc 2.3.21 recompiled the class into a copy of the main classes; ran `ArchitectureTest` with the JUnit Platform launcher |
| All code in §1–§4 compiles with kotlinc 2.3.21 (`-Werror -Xjsr305=strict -Xannotation-default-target=param-property`); the 22 tests of `DatabaseIntegrationTest`, `MigrationCommandIntegrationTest`, `ArchitectureTest` and `ControllerRoleRuleTest` pass (Testcontainers, JUnit Platform launcher, not Gradle) | scratch drafts of the four files |
| The spec's check commands work: `git diff --name-only origin/main...HEAD -- services/core/src/main services/core/src/test/resources build-logic gradle` prints nothing today; `grep -n 'output.all.contains("app_rw")'` prints line 115 today and nothing on the draft; `grep -c 'assertFailsWith<DataAccessException>'` prints 2 today and 4 on the draft | ran on the current tree and the drafts |
| ktlint and detekt accept the new code | UNVERIFIED: implementer runs `./gradlew ktlintFormat`, then `./gradlew check` |

## Limits
| Limit | Value | Wrapped by (must be larger) |
|---|---|---|
| App DataSource `statement_timeout` (each app statement in the new tests; all are single-row) | 5 s (`application.yaml`, Hikari `options`) | CI `check` job `timeout-minutes: 30`; no JUnit or Gradle test timeout is set |
| Hikari `connection-timeout` | 5 s | CI `check` job, 30 min |
| `migrator` DataSource `statement_timeout` (the §3.2 `create`/`drop`) | none (0; asserted by the existing Flyway test) | CI `check` job, 30 min. No lock wait is possible: only this test touches the probe objects, and every app statement autocommits before `finally` runs. When the job timeout fires, the runner kills the JVM and Ryuk removes the containers; nothing persists |
| Testcontainers startup | unchanged: no new container and no new Spring context | — |

## Acceptance criteria
1. Only the Allowed paths change: `scripts/agentic/scope.sh diff docs/tasks/014-tests-name-the-reason.md` exits 0, and
   `git diff --name-only origin/main...HEAD -- services/core/src/main services/core/src/test/resources build-logic gradle`
   prints nothing.
2. `DatabaseIntegrationTest` has the helper of §1.2. `the app role cannot change the schema`,
   `the app role cannot read the migration history` and the new `the app role cannot create objects in the public schema`
   pass their caught `DataAccessException` to it with the reasons of §1.3. `grep -c 'assertFailsWith<DataAccessException>'`
   on the file prints 4, and each of the 4 results is passed to `assertPermissionDenied`. The core test still asserts
   that `core.should_not_exist` does not exist afterwards.
3. `DatabaseIntegrationTest` has `a table and a sequence that the migrator creates after V2 are usable by the app role`
   as in §3.2: DDL on the Flyway DataSource, `nextval`/insert/update/select/delete through `jdbc`, truncate denied with
   42501 and `permission denied for table default_privileges_probe`, both objects dropped in `finally`. The probe uses
   an explicit sequence, not an identity column.
4. In `migrate fails with exit code 1 when app_rw is missing and never logs the password`: `error.class` equals
   `org.flywaydb.core.internal.exception.FlywayMigrateException`; exactly one parsed log record has `message`
   `Migration failed`, with `log.level` `ERROR`, `log.logger` equal to `MigrationCommand::class.java.name`, and
   `error.message` containing `role "app_rw" does not exist`; the password assertion is unchanged.
   `grep -n 'output.all.contains("app_rw")' services/core/src/test/kotlin/dev/danya/tastebuds/core/MigrationCommandIntegrationTest.kt`
   prints nothing.
5. `docs/backlog/2026-11-21-migrate-test-asserts-file-name.md` is deleted.
6. `ArchitectureTest` has `controllers exist only in the api role`, which checks `controllersOnlyInApiRole` defined as
   in §4.2 (scope `areMetaAnnotatedWith(Controller::class.java)`, annotation value `Role.API`, no `allowEmptyShould`).
   It passes on the current main classes.
7. `ControllerRoleRuleTest` exists in package `dev.danya.tastebuds.architecture` with the fixtures and tests of §4.3 and
   "Required tests". Each negative test asserts exactly one violation whose text starts with `expected(fixture)`:
   `Class <`, then the fixture's `Class.name` (e.g.
   `dev.danya.tastebuds.architecture.ControllerRoleRuleTest$WithoutRole`), then
   `> is not annotated with @ConditionalOnRole(Role.API)`. The angle brackets are literal ArchUnit output.
8. No test outside the Allowed paths changes; existing test names are unchanged.
9. `./gradlew check` passes, including `koverVerify` (aggregate line coverage ≥ 70%). No production line changes, so
   production coverage does not drop.

## Required tests
- `DatabaseIntegrationTest` (existing, `@SpringBootTest`, same context):
  - `the app role cannot change the schema`: 42501, `permission denied for schema core`; table absent afterwards.
  - `the app role cannot read the migration history`: 42501, `permission denied for table flyway_schema_history`.
  - `the app role cannot create objects in the public schema` (new): 42501, `permission denied for schema public`.
  - `a table and a sequence that the migrator creates after V2 are usable by the app role` (new): §3.2.
- `MigrationCommandIntegrationTest` (existing):
  - `migrate fails with exit code 1 when app_rw is missing and never logs the password`: §2.3.
- `ArchitectureTest` (existing, ArchUnit engine):
  - `controllers exist only in the api role` (new): §4.2.
- `ControllerRoleRuleTest` (new, plain JUnit 5):
  - `a rest controller without the role annotation breaks the rule`: `violations(WithoutRole)` has size 1 and starts
    with `expected(WithoutRole)`.
  - `a rest controller for the worker role breaks the rule`: same for `WorkerRole`.
  - `a plain controller without the role annotation breaks the rule`: same for `PlainWithoutRole`.
  - `an api controller and a controller advice pass the rule`: evaluating `ApiRole` and `Advice` together has no
    violation.
  - `each production controller is in the rule's scope and passes it`: `violations(type)` is empty for
    `AnonSessionController` and for `MetaController`, each evaluated alone (an out-of-scope class fails with "failed
    to check any classes").

## Quality gates
- `./gradlew check` passes (ktlint, detekt, unit + integration tests, ArchUnit, Spring Modulith verification).
- Kover: no production code changes; the global floor (70%) holds.
- No wide event fields change.
- No new dependency (ArchUnit, Jackson 3 and kotlin-test are already on the test classpath).

## Review log
<!-- Appended by the run-task orchestrator. One entry per round: verdict + numbered findings + resolution. -->
