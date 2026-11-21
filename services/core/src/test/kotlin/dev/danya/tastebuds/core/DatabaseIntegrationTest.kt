package dev.danya.tastebuds.core

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
class DatabaseIntegrationTest(
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val dataSource: DataSource,
    @Autowired private val flyway: Flyway,
) {
    @Test
    fun `pgvector type and distance operator resolve through the app search path`() {
        val distance =
            jdbc
                .sql("select '[1,2,3]'::vector <-> '[1,2,5]'::vector")
                .query(Double::class.java)
                .single()

        assertEquals(2.0, distance)
    }

    @Test
    fun `the app connects as a non-superuser runtime role`() {
        val user = jdbc.sql("select current_user").query(String::class.java).single()
        val superuser =
            jdbc
                .sql("select rolsuper from pg_roles where rolname = current_user")
                .query(Boolean::class.javaObjectType)
                .single()

        assertEquals(TestcontainersConfiguration.APP_DB_USER, user)
        assertFalse(superuser)
    }

    @Test
    fun `the app role cannot change the schema`() {
        val error = assertFailsWith<DataAccessException> { jdbc.sql("create table core.should_not_exist(id int)").update() }

        assertPermissionDenied(error, "permission denied for schema core")

        val exists =
            jdbc
                .sql("select count(*) from information_schema.tables where table_schema = 'core' and table_name = 'should_not_exist'")
                .query(Long::class.javaObjectType)
                .single()
        assertEquals(0L, exists)
    }

    @Test
    fun `flyway uses its own connection without the statement timeout`() {
        val flywayDataSource = flyway.configuration.dataSource
        val flywayJdbc = JdbcClient.create(flywayDataSource)

        assertNotSame(dataSource, flywayDataSource)
        assertEquals("0", flywayJdbc.sql("show statement_timeout").query(String::class.java).single())
        assertEquals("5s", jdbc.sql("show statement_timeout").query(String::class.java).single())
        assertEquals(
            TestcontainersConfiguration.MIGRATOR_DB_USER,
            flywayJdbc.sql("select current_user").query(String::class.java).single(),
        )
    }

    @Test
    fun `migrations run as the non-superuser owner of the core schema`() {
        val flywayJdbc = JdbcClient.create(flyway.configuration.dataSource)

        val superuser =
            flywayJdbc
                .sql("select rolsuper from pg_roles where rolname = current_user")
                .query(Boolean::class.javaObjectType)
                .single()
        val owner =
            flywayJdbc
                .sql("select pg_get_userbyid(nspowner) from pg_namespace where nspname = 'core'")
                .query(String::class.java)
                .single()

        assertFalse(superuser)
        assertEquals(TestcontainersConfiguration.MIGRATOR_DB_USER, owner)
    }

    @Test
    fun `the app role cannot read the migration history`() {
        val error =
            assertFailsWith<DataAccessException> {
                jdbc.sql("select count(*) from core.flyway_schema_history").query(Long::class.javaObjectType).single()
            }

        assertPermissionDenied(error, "permission denied for table flyway_schema_history")
    }

    @Test
    fun `the app role cannot create objects in the public schema`() {
        val error = assertFailsWith<DataAccessException> { jdbc.sql("create table public.should_not_exist(id int)").update() }

        assertPermissionDenied(error, "permission denied for schema public")
    }

    @Test
    fun `a table and a sequence that the migrator creates after V2 are usable by the app role`() {
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
    }

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
}
