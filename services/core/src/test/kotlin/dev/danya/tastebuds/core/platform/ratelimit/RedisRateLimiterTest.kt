package dev.danya.tastebuds.core.platform.ratelimit

import dev.danya.tastebuds.core.RedisTestcontainersConfiguration
import io.lettuce.core.ClientOptions
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.resource.ClientResources
import io.lettuce.core.resource.DefaultClientResources
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RedisRateLimiterTest {
    private val properties = RateLimitProperties(capacity = 3, refillTokens = 3, refillPeriod = Duration.ofHours(1))

    private fun details() =
        object : DataRedisConnectionDetails {
            override fun getStandalone() =
                DataRedisConnectionDetails.Standalone.of(
                    RedisTestcontainersConfiguration.redis.host,
                    RedisTestcontainersConfiguration.redis.getMappedPort(RedisTestcontainersConfiguration.REDIS_PORT),
                )
        }

    private fun newClient(): RedisClient = RateLimitRedis.client(resources, details(), properties.timeout)

    private fun limiter(client: RedisClient = newClient()): RedisRateLimiter {
        val connection = LazyConnection({ RateLimitRedis.proxyManager(client, properties.timeout) }, Duration.ofSeconds(5))
        return RedisRateLimiter(connection::getOrNull, properties)
    }

    /** Unique per call, so tests sharing one Redis never meet. */
    private fun uniqueKey() = ClientKey("test:" + UUID.randomUUID(), ClientKeyType.IP4)

    @Test
    fun `requests within capacity are allowed and report the remaining tokens`() {
        val limiter = limiter()
        val key = uniqueKey()
        assertEquals(RateLimitDecision.Allowed(2), limiter.tryConsume(key, 1))
        assertEquals(RateLimitDecision.Allowed(1), limiter.tryConsume(key, 1))
        assertEquals(RateLimitDecision.Allowed(0), limiter.tryConsume(key, 1))
    }

    @Test
    fun `the request that exceeds the bucket is rejected with a retry delay`() {
        val limiter = limiter()
        val key = uniqueKey()
        assertEquals(RateLimitDecision.Allowed(1), limiter.tryConsume(key, 2))
        val rejected = assertIs<RateLimitDecision.Rejected>(limiter.tryConsume(key, 2))
        assertEquals(1, rejected.remaining)
        assertTrue(rejected.retryAfterSeconds in 1..3600, "retry after: ${rejected.retryAfterSeconds}")
    }

    @Test
    fun `different client keys have independent buckets`() {
        val limiter = limiter()
        val a = uniqueKey()
        val b = uniqueKey()
        assertEquals(RateLimitDecision.Allowed(0), limiter.tryConsume(a, 3))
        assertIs<RateLimitDecision.Rejected>(limiter.tryConsume(a, 1))
        assertEquals(RateLimitDecision.Allowed(2), limiter.tryConsume(b, 1))
    }

    @Test
    fun `two limiter instances share bucket state through Redis`() {
        val first = limiter()
        val second = limiter()
        val key = uniqueKey()
        assertEquals(RateLimitDecision.Allowed(1), first.tryConsume(key, 2))
        assertIs<RateLimitDecision.Rejected>(second.tryConsume(key, 2))
        assertEquals(RateLimitDecision.Allowed(0), second.tryConsume(key, 1))
    }

    @Test
    fun `bucket keys expire when idle`() {
        val key = uniqueKey()
        limiter().tryConsume(key, 1)
        val raw = RedisClient.create(resources, uri())
        try {
            raw.connect().use { connection ->
                val redisKey = bucketKeyPrefix(properties) + key.value
                val ttl = connection.sync().pttl(redisKey)
                assertTrue(ttl > 0, "pttl: $ttl")
            }
        } finally {
            raw.shutdown()
        }
    }

    @Test
    fun `the Lettuce client fails fast and is time-bounded`() {
        val client = newClient()
        assertEquals(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS, client.options.disconnectedBehavior)
        assertTrue(client.options.timeoutOptions.isTimeoutCommands)
        assertEquals(properties.timeout, client.options.socketOptions.connectTimeout)
    }

    @Test
    fun `retry-after rounds up to whole seconds, at least one`() {
        assertEquals(1, retryAfterSeconds(0))
        assertEquals(1, retryAfterSeconds(1))
        assertEquals(1, retryAfterSeconds(1_000_000_000L))
        assertEquals(2, retryAfterSeconds(1_000_000_001L))
    }

    private fun uri(): RedisURI =
        RedisURI.create(
            RedisTestcontainersConfiguration.redis.host,
            RedisTestcontainersConfiguration.redis.getMappedPort(RedisTestcontainersConfiguration.REDIS_PORT),
        )

    companion object {
        private lateinit var resources: ClientResources

        @JvmStatic
        @BeforeAll
        fun startResources() {
            resources = DefaultClientResources.create()
        }

        @JvmStatic
        @AfterAll
        fun stopResources() {
            resources.shutdown().get()
        }
    }
}
