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
    ) : RateLimiter {
        var calls = 0

        override fun tryConsume(
            key: ClientKey,
            cost: Long,
        ): RateLimitDecision {
            calls++
            return decision
        }
    }

    private class Run(
        val limiter: FakeLimiter,
        val event: WideEvent,
        val response: MockHttpServletResponse,
        val chain: MockFilterChain,
    )

    private fun run(
        decision: RateLimitDecision,
        path: String = "/v1/meta",
    ): Run {
        val limiter = FakeLimiter(decision)
        val filter = RateLimitFilter(IpClientKeyResolver(), RequestCostResolver(properties), limiter)
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
        val run = run(RateLimitDecision.Rejected(1, 42))
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
        assertFalse(body.contains(clientAddress))
        assertFalse(
            run.event
                .snapshot()
                .values
                .any { it.toString().contains(clientAddress) },
        )
    }

    @Test
    fun `when the store is unavailable requests continue and the outcome says so`() {
        val run = run(RateLimitDecision.Unavailable)
        assertNotNull(run.chain.request)
        assertEquals("unavailable", run.event["rate_limit.outcome"])
        assertNull(run.event["rate_limit.remaining"])
    }

    @Test
    fun `paths outside v1 are never limited`() {
        listOf("/actuator/health/liveness", "/error", "/").forEach { path ->
            val run = run(RateLimitDecision.Rejected(0, 1), path)
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
