package dev.danya.tastebuds.core

import dev.danya.tastebuds.observability.Outcome
import dev.danya.tastebuds.observability.Slf4jWideEventSink
import dev.danya.tastebuds.observability.WideEvent
import dev.danya.tastebuds.observability.WideEventSink
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import org.springframework.boot.Banner
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean

/**
 * `migrate`: applies the Flyway migrations as the schema owner, emits one wide event and returns the exit code
 * (docs/deploy/DATABASE.md). The Helm chart runs it as a pre-install/pre-upgrade Job from the same image; api and
 * worker pods never migrate (SPRING_FLYWAY_ENABLED=false). The context holds only a DataSource and Flyway: no web
 * server, no security, no Redis, no application modules.
 */
internal object MigrationCommand {
    const val NAME = "migrate"
    private const val EXIT_OK = 0
    private const val EXIT_FAILED = 1
    private const val NANOS_PER_MILLI = 1_000_000L

    private val logger = LoggerFactory.getLogger(MigrationCommand::class.java)

    /** Runs the migration with [args] (Spring Boot command-line properties). Returns 0 on success, 1 on any failure. */
    @Suppress("TooGenericExceptionCaught") // any failure must become exit code 1 and one wide event
    fun run(
        args: Array<String>,
        sink: WideEventSink = Slf4jWideEventSink(),
    ): Int {
        val event = WideEvent("job").put("job.name", NAME)
        val startedAt = System.nanoTime()
        var context: ConfigurableApplicationContext? = null
        try {
            context = start(args)
            val flyway = context.getBean(Flyway::class.java)
            val result = flyway.migrate()
            event.put("flyway.migrations_executed", result.migrationsExecuted)
            flyway
                .info()
                .current()
                ?.version
                ?.version
                ?.let { event.put("flyway.schema_version", it) }
            event.put(WideEvent.OUTCOME, Outcome.SUCCESS)
            return EXIT_OK
        } catch (e: Exception) {
            logger.error("Migration failed", e)
            event.recordError(e).put(WideEvent.OUTCOME, Outcome.ERROR)
            return EXIT_FAILED
        } finally {
            event.put(WideEvent.DURATION_MS, (System.nanoTime() - startedAt) / NANOS_PER_MILLI)
            sink.emit(event)
            context?.close()
        }
    }

    /** Starts the migration context without migrating. Opens no database connection. Visible for tests. */
    internal fun start(args: Array<String>): ConfigurableApplicationContext =
        SpringApplicationBuilder(MigrationContext::class.java)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF)
            .registerShutdownHook(false)
            .run(*args)
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
