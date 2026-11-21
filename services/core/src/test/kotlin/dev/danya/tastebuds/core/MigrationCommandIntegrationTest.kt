package dev.danya.tastebuds.core

import dev.danya.tastebuds.core.meta.MetaController
import dev.danya.tastebuds.observability.WideEvent
import dev.danya.tastebuds.observability.WideEventSink
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.security.web.SecurityFilterChain
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@ExtendWith(OutputCaptureExtension::class)
class MigrationCommandIntegrationTest {
    private val events = mutableListOf<WideEvent>()
    private val sink = WideEventSink { events += it }

    private fun superuser(container: PostgreSQLContainer): JdbcClient =
        JdbcClient.create(DriverManagerDataSource(container.jdbcUrl, container.username, container.password))

    @Test
    fun `migrate applies every migration as the schema owner and a second run applies nothing`() {
        PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE)
            .withInitScript("testcontainers/postgres-init.sql")
            .use { container ->
                container.start()
                val args =
                    arrayOf(
                        "--spring.datasource.url=${container.jdbcUrl}",
                        "--spring.flyway.user=${TestcontainersConfiguration.MIGRATOR_DB_USER}",
                        "--spring.flyway.password=${TestcontainersConfiguration.MIGRATOR_DB_PASSWORD}",
                    )
                val db = superuser(container)

                assertEquals(0, MigrationCommand.run(args, sink))

                val applied =
                    db
                        .sql("select count(*) from core.flyway_schema_history where success and type = 'SQL'")
                        .query(Long::class.javaObjectType)
                        .single()
                val highest =
                    db
                        .sql("select version from core.flyway_schema_history where success order by installed_rank desc limit 1")
                        .query(String::class.java)
                        .single()
                val owner =
                    db
                        .sql("select pg_get_userbyid(nspowner) from pg_namespace where nspname = 'core'")
                        .query(String::class.java)
                        .single()
                val usage =
                    db
                        .sql("select has_schema_privilege('app_rw', 'core', 'USAGE')")
                        .query(Boolean::class.javaObjectType)
                        .single()
                val history =
                    db
                        .sql("select has_table_privilege('app_rw', 'core.flyway_schema_history', 'SELECT')")
                        .query(Boolean::class.javaObjectType)
                        .single()
                assertEquals(TestcontainersConfiguration.MIGRATOR_DB_USER, owner)
                assertTrue(usage)
                assertFalse(history)
                assertTrue(applied > 0)
                assertEquals(1, events.size)
                assertCommon(events[0], "success")
                assertEquals(applied.toInt(), events[0]["flyway.migrations_executed"])
                assertEquals(highest, events[0]["flyway.schema_version"])

                assertEquals(0, MigrationCommand.run(args, sink))

                assertEquals(2, events.size)
                assertCommon(events[1], "success")
                assertEquals(0, events[1]["flyway.migrations_executed"])
                assertEquals(highest, events[1]["flyway.schema_version"])
            }
    }

    @Test
    fun `migrate fails with exit code 1 when app_rw is missing and never logs the password`(output: CapturedOutput) {
        val password = "canary-" + UUID.randomUUID()
        PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE)
            .withPassword(password)
            .use { container ->
                container.start()
                val args =
                    arrayOf(
                        "--spring.datasource.url=${container.jdbcUrl}",
                        "--spring.datasource.username=${container.username}",
                        "--spring.datasource.password=${container.password}",
                        "--spring.flyway.user=${container.username}",
                        "--spring.flyway.password=${container.password}",
                    )

                assertEquals(1, MigrationCommand.run(args, sink))

                assertEquals(1, events.size)
                assertCommon(events[0], "error")
                assertEquals("org.flywaydb.core.internal.exception.FlywayMigrateException", events[0]["error.class"])
                assertTrue(Regex("[0-9a-f]{12}").matches(events[0]["error.fingerprint"].toString()))
                assertNull(events[0]["flyway.migrations_executed"])
                assertNull(events[0]["flyway.schema_version"])
                val failures = logRecords(output).filter { it.path("message").asString() == "Migration failed" }
                assertEquals(1, failures.size, "expected one 'Migration failed' record")
                val failure = failures.single()
                assertEquals("ERROR", failure.at("/log/level").asString())
                assertEquals(MigrationCommand::class.java.name, failure.at("/log/logger").asString())
                assertContains(failure.at("/error/message").asString(), "role \"app_rw\" does not exist")
                assertFalse(output.all.contains(password))
            }
    }

    @Test
    fun `migrate fails when flyway is disabled`() {
        val code =
            MigrationCommand.run(
                arrayOf("--spring.datasource.url=$UNREACHABLE_URL", "--spring.flyway.enabled=false"),
                sink,
            )

        assertEquals(1, code)
        assertEquals(1, events.size)
        assertCommon(events[0], "error")
        assertEquals("org.springframework.beans.factory.NoSuchBeanDefinitionException", events[0]["error.class"])
    }

    @Test
    fun `the migration context has no web server, security, redis or application modules`() {
        MigrationCommand.start(arrayOf("--spring.datasource.url=$UNREACHABLE_URL")).use { context ->
            assertFalse(context is WebServerApplicationContext)
            assertTrue(context.getBeansOfType(SecurityFilterChain::class.java).isEmpty())
            assertTrue(context.getBeansOfType(RedisConnectionFactory::class.java).isEmpty())
            assertTrue(context.getBeansOfType(MetaController::class.java).isEmpty())
            assertEquals(1, context.getBeansOfType(Flyway::class.java).size)
        }
    }

    private val json = JsonMapper.builder().build()

    /** Console log records (ECS JSON, one per line); lines that are not JSON are skipped. */
    private fun logRecords(output: CapturedOutput): List<JsonNode> =
        output.all
            .lines()
            .filter { it.startsWith("{") }
            .mapNotNull { line -> runCatching { json.readTree(line) }.getOrNull() }

    private fun assertCommon(
        event: WideEvent,
        outcome: String,
    ) {
        assertEquals("job", event[WideEvent.KIND])
        assertEquals("migrate", event["job.name"])
        assertEquals(outcome, event[WideEvent.OUTCOME])
        assertNotNull(event[WideEvent.DURATION_MS])
    }

    private companion object {
        const val UNREACHABLE_URL = "jdbc:postgresql://localhost:1/none"
    }
}
