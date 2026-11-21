package dev.danya.tastebuds.core

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Real infrastructure for tests and for local runs via `./gradlew :services:core:bootTestRun`.
 *
 * The app connects as `tastebuds_app` (member of `app_rw`), Flyway as the non-superuser owner `migrator`; the
 * superuser only runs the init script. Privilege problems surface here instead of in production.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
    @Bean
    fun postgresContainer(): PostgreSQLContainer = PostgreSQLContainer(POSTGRES_IMAGE).withInitScript("testcontainers/postgres-init.sql")

    @Bean
    fun postgresProperties(postgres: PostgreSQLContainer): DynamicPropertyRegistrar =
        DynamicPropertyRegistrar { registry ->
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { APP_DB_USER }
            registry.add("spring.datasource.password") { APP_DB_PASSWORD }
            registry.add("spring.flyway.user") { MIGRATOR_DB_USER }
            registry.add("spring.flyway.password") { MIGRATOR_DB_PASSWORD }
        }

    companion object {
        const val MIGRATOR_DB_USER = "migrator"
        const val MIGRATOR_DB_PASSWORD = "test"
        val POSTGRES_IMAGE: DockerImageName =
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg17").asCompatibleSubstituteFor("postgres")

        const val APP_DB_USER = "tastebuds_app"
        const val APP_DB_PASSWORD = "test"
    }
}
