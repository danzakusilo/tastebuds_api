package dev.danya.tastebuds.core.platform.ratelimit

import dev.danya.tastebuds.core.RedisTestcontainersConfiguration
import dev.danya.tastebuds.core.TestcontainersConfiguration
import dev.danya.tastebuds.core.platform.AnonSessionIssuer
import dev.danya.tastebuds.core.platform.IssuedAnonSession
import dev.danya.tastebuds.core.platform.security.AnonSessionAuthenticationFilter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.security.web.FilterChainProxy
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(
    properties = [
        "tastebuds.rate-limit.enabled=true",
        "tastebuds.rate-limit.capacity=4",
        "tastebuds.rate-limit.refill-tokens=4",
        "tastebuds.rate-limit.refill-period=1h",
        "tastebuds.rate-limit.session.capacity=2",
        "tastebuds.rate-limit.session.refill-tokens=2",
        "tastebuds.rate-limit.session.refill-period=1h",
        "tastebuds.rate-limit.rules[0].method=GET",
        "tastebuds.rate-limit.rules[0].pattern=/v1/meta",
        "tastebuds.rate-limit.rules[0].cost=1",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class, RedisTestcontainersConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class AnonSessionRateLimitIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val issuer: AnonSessionIssuer,
    @Autowired private val filterChainProxy: FilterChainProxy,
) {
    private val json = JsonMapper.builder().build()
    private val issued = CopyOnWriteArrayList<IssuedAnonSession>()

    private fun newSession(): String = issuer.issue().also { issued += it }.token

    private fun status(
        client: String,
        token: String? = null,
    ): Int =
        mockMvc
            .get("/v1/meta") {
                with {
                    it.remoteAddr = client
                    it
                }
                if (token != null) header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            }.andReturn()
            .response.status

    private fun lastRateLimitEvent(output: CapturedOutput): JsonNode =
        output.out
            .lines()
            .filter { it.startsWith("{") }
            .map { json.readTree(it) }
            .filter { it.path("message").asString() == "wide_event" && it.has("rate_limit") }
            .last()
            .path("rate_limit")

    @Test
    fun `a session draws from its own bucket and from the IP bucket`(output: CapturedOutput) {
        val client = "203.0.113.31"
        val token = newSession()
        assertEquals(200, status(client, token))
        assertEquals(200, status(client, token))
        assertEquals(429, status(client, token))
        assertEquals("session", lastRateLimitEvent(output).path("limited_by").asString())
        assertEquals(200, status(client))
        assertEquals(1, lastRateLimitEvent(output).path("remaining").asInt())
    }

    @Test
    fun `a session keeps its bucket when its address changes`(output: CapturedOutput) {
        val token = newSession()
        assertEquals(200, status("203.0.113.32", token))
        assertEquals(200, status("203.0.113.32", token))
        assertEquals(429, status("203.0.113.33", token))
        assertEquals("session", lastRateLimitEvent(output).path("limited_by").asString())
    }

    @Test
    fun `new sessions do not multiply the IP bucket`(output: CapturedOutput) {
        val client = "203.0.113.34"
        val statuses = (1..5).map { status(client, newSession()) }
        assertEquals(listOf(200, 200, 200, 200, 429), statuses)
        assertEquals("ip", lastRateLimitEvent(output).path("limited_by").asString())
    }

    @Test
    fun `a session over its limit does not drain its neighbours' IP bucket`() {
        val client = "203.0.113.35"
        val x = newSession()
        assertEquals(listOf(200, 200, 429, 429, 429), (1..5).map { status(client, x) })
        val y = newSession()
        assertEquals(listOf(200, 200), (1..2).map { status(client, y) })
    }

    @Test
    fun `invalid tokens are limited by IP only`(output: CapturedOutput) {
        val client = "198.51.100.36"
        assertEquals(listOf(401, 401, 401, 401), (1..4).map { status(client, "tba1.x.y.z") })
        assertEquals(429, status(client, "tba1.x.y.z"))
        assertEquals("ip", lastRateLimitEvent(output).path("limited_by").asString())
    }

    @Test
    fun `the limiter runs before anonymous-session authentication`() {
        val filters = filterChainProxy.filterChains.flatMap { it.filters }
        val limiter = filters.single { it is RateLimitFilter }
        val authentication = filters.single { it is AnonSessionAuthenticationFilter }
        assertTrue(filters.indexOf(limiter) < filters.indexOf(authentication))
    }

    @Test
    fun `session ids and tokens never appear in wide events`(output: CapturedOutput) {
        val client = "198.51.100.37"
        val token = newSession()
        // Allowed twice, then rejected by the session bucket.
        repeat(3) { status(client, token) }
        // New valid sessions from one IP until the IP bucket rejects (two requests above already used its tokens).
        val statuses = (1..3).map { status(client, newSession()) }
        assertEquals(429, statuses.last())
        assertEquals("ip", lastRateLimitEvent(output).path("limited_by").asString())
        // An invalid token, answered from the IP bucket alone.
        status("198.51.100.38", "tba1.x.y.z")
        val secrets = issued.flatMap { listOf(it.token, it.principal.sessionId.toString()) }
        val lines = output.out.lines().filter { it.contains("wide_event") }
        assertTrue(lines.isNotEmpty())
        assertFalse(lines.any { line -> secrets.any { line.contains(it) } })
    }
}
