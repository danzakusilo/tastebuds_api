package dev.danya.tastebuds.core

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * Redis for rate-limit tests and `bootTestRun`. One container per JVM, not per Spring context: tests share it and use
 * distinct client keys. Not a bean, so no closing context stops it; Testcontainers' Ryuk removes it when the JVM exits.
 */
@TestConfiguration(proxyBeanMethods = false)
class RedisTestcontainersConfiguration {
    @Bean
    fun redisProperties(): DynamicPropertyRegistrar =
        DynamicPropertyRegistrar { registry ->
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.getMappedPort(REDIS_PORT) }
        }

    /** Kotlin cannot use `GenericContainer<Nothing>` fluently (`Nothing` makes the next statement unreachable, an error here). */
    class RedisContainer(
        image: DockerImageName,
    ) : GenericContainer<RedisContainer>(image)

    companion object {
        const val REDIS_PORT = 6379

        val redis: RedisContainer by lazy {
            RedisContainer(DockerImageName.parse("redis:8.10.3-alpine")).withExposedPorts(REDIS_PORT).apply { start() }
        }
    }
}
