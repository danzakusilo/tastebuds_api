package dev.danya.tastebuds.core.platform.ratelimit

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RateLimitPropertiesTest {
    private fun rule(
        method: String? = null,
        pattern: String = "/v1/x",
        cost: Long = 1,
    ) = RateLimitProperties.Rule(method, pattern, cost)

    private fun assertRejected(
        expectedPrefix: String,
        build: () -> RateLimitProperties,
    ) {
        val e = assertFailsWith<IllegalArgumentException> { build() }
        assertTrue(e.message.orEmpty().startsWith(expectedPrefix), "message: ${e.message}")
    }

    @Test
    fun `defaults are valid`() {
        val p = RateLimitProperties()
        assertEquals(true, p.enabled)
        assertEquals(120, p.capacity)
        assertEquals(120, p.refillTokens)
        assertEquals(Duration.ofMinutes(1), p.refillPeriod)
        assertEquals(1, p.defaultCost)
        assertEquals(Duration.ofMillis(250), p.timeout)
        assertEquals(Duration.ofSeconds(5), p.reconnectBackoff)
        assertEquals(emptyList(), p.rules)
        assertEquals("rl:120-120-60000:", bucketKeyPrefix(p))
    }

    @Test
    fun `invalid values are rejected`() {
        val base = "tastebuds.rate-limit"
        assertRejected("$base.capacity") { RateLimitProperties(capacity = 0) }
        assertRejected("$base.capacity") { RateLimitProperties(capacity = 1_000_001) }
        assertRejected("$base.refill-tokens") { RateLimitProperties(refillTokens = 0) }
        assertRejected("$base.refill-tokens") { RateLimitProperties(refillTokens = 1_000_001) }
        assertRejected("$base.refill-period") { RateLimitProperties(refillPeriod = Duration.ofMillis(999)) }
        assertRejected("$base.default-cost") { RateLimitProperties(defaultCost = 0) }
        assertRejected("$base.default-cost") { RateLimitProperties(capacity = 10, defaultCost = 11) }
        assertRejected("$base.timeout") { RateLimitProperties(timeout = Duration.ZERO) }
        assertRejected("$base.timeout") { RateLimitProperties(timeout = Duration.ofMillis(5001)) }
        assertRejected("$base.reconnect-backoff") { RateLimitProperties(reconnectBackoff = Duration.ZERO) }
        assertRejected("$base.rules[0].cost") { RateLimitProperties(rules = listOf(rule(cost = 0))) }
        assertRejected("$base.rules[0].cost") { RateLimitProperties(capacity = 10, rules = listOf(rule(cost = 11))) }
        assertRejected("$base.rules[1].pattern") { RateLimitProperties(rules = listOf(rule(), rule(pattern = "/actuator/health"))) }
        assertRejected("$base.rules[0].method") { RateLimitProperties(rules = listOf(rule(method = "get"))) }
        assertRejected("$base.rules[0].method") { RateLimitProperties(rules = listOf(rule(method = "FOO"))) }
    }

    @Test
    fun `the bucket key prefix changes with the bucket configuration`() {
        val base = RateLimitProperties()
        val prefixes =
            setOf(
                bucketKeyPrefix(base),
                bucketKeyPrefix(base.copy(capacity = 100)),
                bucketKeyPrefix(base.copy(refillTokens = 60)),
                bucketKeyPrefix(base.copy(refillPeriod = Duration.ofSeconds(30))),
            )
        assertEquals(4, prefixes.size)
        assertNotEquals(bucketKeyPrefix(base), bucketKeyPrefix(base.copy(capacity = 100)))
    }
}
