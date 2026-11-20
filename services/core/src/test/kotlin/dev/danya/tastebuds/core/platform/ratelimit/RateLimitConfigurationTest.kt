package dev.danya.tastebuds.core.platform.ratelimit

import io.lettuce.core.RedisClient
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.security.config.Customizer
import java.net.ServerSocket
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RateLimitConfigurationTest {
    private val closedPort = ServerSocket(0).use { it.localPort }
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration::class.java))
            .withUserConfiguration(RateLimitConfiguration::class.java)
            .withPropertyValues("spring.data.redis.port=$closedPort")

    private fun org.springframework.boot.test.context.assertj.AssertableApplicationContext.hasLimiter() =
        getBeanNamesForType(RateLimiter::class.java).isNotEmpty() &&
            getBeanNamesForType(RedisClient::class.java).any { it == "rateLimitRedisClient" } &&
            getBeanNamesForType(Customizer::class.java).any { it == "rateLimitSecurityCustomizer" }

    @Test
    fun `the api role gets the limiter without connecting to Redis at startup`() {
        runner.withPropertyValues("tastebuds.role=api").run { context ->
            assertNull(context.startupFailure)
            assertTrue(context.hasLimiter())
        }
    }

    @Test
    fun `the all role and a missing enabled property also get the limiter`() {
        runner.withPropertyValues("tastebuds.role=all").run { assertTrue(it.hasLimiter()) }
        runner.run { assertTrue(it.hasLimiter()) }
    }

    @Test
    fun `the worker role gets no limiter`() {
        runner.withPropertyValues("tastebuds.role=worker").run { context ->
            assertTrue(context.getBeanNamesForType(RateLimiter::class.java).isEmpty())
            assertTrue(context.getBeanNamesForType(RedisClient::class.java).none { it == "rateLimitRedisClient" })
            assertTrue(context.getBeanNamesForType(Customizer::class.java).none { it == "rateLimitSecurityCustomizer" })
        }
    }

    @Test
    fun `disabling rate limiting removes the limiter`() {
        runner.withPropertyValues("tastebuds.rate-limit.enabled=false").run { context ->
            assertTrue(context.getBeanNamesForType(RateLimiter::class.java).isEmpty())
            assertTrue(context.getBeanNamesForType(RedisClient::class.java).none { it == "rateLimitRedisClient" })
            assertTrue(context.getBeanNamesForType(Customizer::class.java).none { it == "rateLimitSecurityCustomizer" })
        }
    }

    @Test
    fun `invalid rate-limit settings fail startup`() {
        runner.withPropertyValues("tastebuds.rate-limit.default-cost=0").run { context ->
            val failure = assertNotNull(context.startupFailure)
            assertContains(
                generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString("\n"),
                "tastebuds.rate-limit.default-cost",
            )
        }
    }

    @Test
    fun `unsupported Redis topologies and TLS fail startup`() {
        listOf("spring.data.redis.cluster.nodes=localhost:7000", "spring.data.redis.ssl.enabled=true").forEach { setting ->
            runner.withPropertyValues(setting).run { context ->
                val failure = assertNotNull(context.startupFailure, setting)
                assertContains(
                    generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString("\n"),
                    "Rate limiting supports a standalone Redis without TLS only",
                )
            }
        }
    }
}
