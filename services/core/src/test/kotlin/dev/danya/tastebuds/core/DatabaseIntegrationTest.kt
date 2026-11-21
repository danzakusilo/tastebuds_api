package dev.danya.tastebuds.core

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
        assertFailsWith<DataAccessException> { jdbc.sql("create table core.should_not_exist(id int)").update() }

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
        assertFailsWith<DataAccessException> {
            jdbc.sql("select count(*) from core.flyway_schema_history").query(Long::class.javaObjectType).single()
        }
    }
}
