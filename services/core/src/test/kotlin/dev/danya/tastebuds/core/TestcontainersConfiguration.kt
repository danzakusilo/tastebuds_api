package dev.danya.tastebuds.core

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Real infrastructure for tests and for local runs via `./gradlew :services:core:bootTestRun`.
 *
 * The app connects as the non-superuser `tastebuds_app` (member of `app_rw`), while Flyway connects as the container
 * owner, so privilege problems surface here instead of in production.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
    @Bean
    fun postgresContainer(): PostgreSQLContainer =
        PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.7-pg17").asCompatibleSubstituteFor("postgres"))
            .withInitScript("testcontainers/postgres-init.sql")

    @Bean
    fun postgresProperties(postgres: PostgreSQLContainer): DynamicPropertyRegistrar =
        DynamicPropertyRegistrar { registry ->
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { APP_DB_USER }
            registry.add("spring.datasource.password") { APP_DB_PASSWORD }
            registry.add("spring.flyway.user") { postgres.username }
            registry.add("spring.flyway.password") { postgres.password }
        }

    companion object {
        const val APP_DB_USER = "tastebuds_app"
        const val APP_DB_PASSWORD = "test"
    }
}
