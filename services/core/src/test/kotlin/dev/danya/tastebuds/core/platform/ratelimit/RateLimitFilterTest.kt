package dev.danya.tastebuds.core.platform.ratelimit

import dev.danya.tastebuds.observability.WideEvent
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RateLimitFilterTest {
    private val clientAddress = "198.51.100.23"
    private val properties = RateLimitProperties(rules = listOf(RateLimitProperties.Rule("GET", "/v1/meta", 2)))

    private class FakeLimiter(
        private val decision: RateLimitDecision,
        private val sessionDecision: RateLimitDecision = decision,
    ) : RateLimiter {
        val keys = mutableListOf<ClientKey>()
        val calls get() = keys.size

        override fun tryConsume(
            key: ClientKey,
            cost: Long,
        ): RateLimitDecision {
            keys += key
            return if (key.type == ClientKeyType.SESSION) sessionDecision else decision
        }
    }

    private val sessionKey = ClientKey("as:00000000-0000-4000-8000-000000000000", ClientKeyType.SESSION)
    private val sessionResolver = ClientKeyResolver { sessionKey }

    private class Run(
        val limiter: FakeLimiter,
        val event: WideEvent,
        val response: MockHttpServletResponse,
        val chain: MockFilterChain,
    )

    private fun run(
        decision: RateLimitDecision,
        path: String = "/v1/meta",
        sessionDecision: RateLimitDecision? = null,
    ): Run {
        val limiter = if (sessionDecision == null) FakeLimiter(decision) else FakeLimiter(decision, sessionDecision)
        val resolvers = if (sessionDecision == null) listOf(IpClientKeyResolver()) else listOf(sessionResolver, IpClientKeyResolver())
        val filter = RateLimitFilter(resolvers, RequestCostResolver(properties), limiter)
        val request = MockHttpServletRequest("GET", path).apply { remoteAddr = clientAddress }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        val event = WideEvent("http")
        WideEvent.within(event) { filter.doFilter(request, response, chain) }
        return Run(limiter, event, response, chain)
    }

    @Test
    fun `allowed requests continue and record the outcome`() {
        val run = run(RateLimitDecision.Allowed(7))
        assertNotNull(run.chain.request)
        assertEquals(200, run.response.status)
        assertEquals("allowed", run.event["rate_limit.outcome"])
        assertEquals(2L, run.event["rate_limit.cost"])
        assertEquals(7L, run.event["rate_limit.remaining"])
        assertEquals("GET /v1/meta", run.event["rate_limit.rule"])
        assertEquals("ip4", run.event["rate_limit.key_type"])
    }

    @Test
    fun `rejected requests get a 429 problem with Retry-After and stop`() {
        val run = run(RateLimitDecision.Rejected(1, 42, RejectReason.TOKENS))
        assertNull(run.chain.request)
        assertEquals(429, run.response.status)
        assertEquals("42", run.response.getHeader("Retry-After"))
        assertTrue(
            run.response.contentType
                .orEmpty()
                .startsWith("application/problem+json"),
        )
        val body = run.response.contentAsString
        val json = JsonMapper.builder().build().readTree(body)
        assertEquals("about:blank", json.path("type").asString())
        assertEquals("Too Many Requests", json.path("title").asString())
        assertEquals(429, json.path("status").asInt())
        assertEquals("Rate limit exceeded.", json.path("detail").asString())
        assertEquals("/v1/meta", json.path("instance").asString())
        assertEquals("rejected", run.event["rate_limit.outcome"])
        assertEquals("tokens", run.event["rate_limit.reject_reason"])
        assertFalse(body.contains(clientAddress))
        assertFalse(
            run.event
                .snapshot()
                .values
                .any { it.toString().contains(clientAddress) },
        )
    }

    private val contended = RateLimitDecision.Rejected(null, 1, RejectReason.CONTENTION)

    @Test
    fun `contention on the IP bucket gets a 429 with Retry-After 1 and no remaining`() {
        val run = run(contended)
        assertNull(run.chain.request)
        assertEquals(429, run.response.status)
        assertEquals("1", run.response.getHeader("Retry-After"))
        assertTrue(
            run.response.contentType
                .orEmpty()
                .startsWith("application/problem+json"),
        )
        val json = JsonMapper.builder().build().readTree(run.response.contentAsString)
        assertEquals("about:blank", json.path("type").asString())
        assertEquals("Too Many Requests", json.path("title").asString())
        assertEquals(429, json.path("status").asInt())
        assertEquals("Rate limit exceeded.", json.path("detail").asString())
        assertEquals("/v1/meta", json.path("instance").asString())
        assertEquals("rejected", run.event["rate_limit.outcome"])
        assertEquals("contention", run.event["rate_limit.reject_reason"])
        assertEquals("ip", run.event["rate_limit.limited_by"])
        assertNull(run.event["rate_limit.remaining"])
    }

    @Test
    fun `contention on the session bucket gets a 429 without asking the IP bucket`() {
        val run = run(RateLimitDecision.Allowed(9), sessionDecision = contended)
        assertNull(run.chain.request)
        assertEquals(429, run.response.status)
        assertEquals("1", run.response.getHeader("Retry-After"))
        assertEquals("session", run.event["rate_limit.limited_by"])
        assertEquals("contention", run.event["rate_limit.reject_reason"])
        assertNull(run.event["rate_limit.remaining"])
        assertEquals(listOf(ClientKeyType.SESSION), run.limiter.keys.map { it.type })
    }

    @Test
    fun `when the store is unavailable requests continue and the outcome says so`() {
        val run = run(RateLimitDecision.Unavailable)
        assertNotNull(run.chain.request)
        assertEquals("unavailable", run.event["rate_limit.outcome"])
        assertNull(run.event["rate_limit.remaining"])
    }

    @Test
    fun `the session bucket is checked before the IP bucket`() {
        val run = run(RateLimitDecision.Allowed(9), sessionDecision = RateLimitDecision.Allowed(5))
        assertEquals(listOf(ClientKeyType.SESSION, ClientKeyType.IP4), run.limiter.keys.map { it.type })
        assertEquals("ip4", run.event["rate_limit.key_type"])
    }

    @Test
    fun `a session over its limit is rejected without charging the IP bucket`() {
        val run = run(RateLimitDecision.Allowed(9), sessionDecision = RateLimitDecision.Rejected(0, 7, RejectReason.TOKENS))
        assertEquals(429, run.response.status)
        assertEquals("7", run.response.getHeader("Retry-After"))
        assertEquals("session", run.event["rate_limit.limited_by"])
        assertEquals("rejected", run.event["rate_limit.outcome"])
        assertEquals("tokens", run.event["rate_limit.reject_reason"])
        assertEquals(listOf(ClientKeyType.SESSION), run.limiter.keys.map { it.type })
        assertNull(run.chain.request)
    }

    @Test
    fun `the IP bucket still limits requests with a session`() {
        val run = run(RateLimitDecision.Rejected(0, 3, RejectReason.TOKENS), sessionDecision = RateLimitDecision.Allowed(5))
        assertEquals(429, run.response.status)
        assertEquals("3", run.response.getHeader("Retry-After"))
        assertEquals("ip", run.event["rate_limit.limited_by"])
        assertNull(run.chain.request)
    }

    @Test
    fun `remaining is the smaller of both buckets`() {
        val run = run(RateLimitDecision.Allowed(9), sessionDecision = RateLimitDecision.Allowed(5))
        assertEquals("allowed", run.event["rate_limit.outcome"])
        assertEquals(5L, run.event["rate_limit.remaining"])
        assertNull(run.event["rate_limit.limited_by"])
        assertNotNull(run.chain.request)
        val ipSmaller = run(RateLimitDecision.Allowed(5), sessionDecision = RateLimitDecision.Allowed(9))
        assertEquals(5L, ipSmaller.event["rate_limit.remaining"])
    }

    @Test
    fun `an unavailable store is asked once and the request continues`() {
        val run = run(RateLimitDecision.Allowed(9), sessionDecision = RateLimitDecision.Unavailable)
        assertEquals(1, run.limiter.calls)
        assertNotNull(run.chain.request)
        assertEquals("unavailable", run.event["rate_limit.outcome"])
        assertNull(run.event["rate_limit.remaining"])
    }

    @Test
    fun `paths outside v1 are never limited`() {
        listOf("/actuator/health/liveness", "/error", "/").forEach { path ->
            val run = run(RateLimitDecision.Rejected(0, 1, RejectReason.TOKENS), path)
            assertEquals(0, run.limiter.calls, path)
            assertNotNull(run.chain.request, path)
            assertTrue(
                run.event
                    .snapshot()
                    .keys
                    .none { it.startsWith("rate_limit.") },
                path,
            )
        }
    }
}
