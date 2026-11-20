package dev.danya.tastebuds.core.platform.ratelimit

import dev.danya.tastebuds.core.RedisTestcontainersConfiguration
import dev.danya.tastebuds.core.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.web.FilterChainProxy
import org.springframework.security.web.access.intercept.AuthorizationFilter
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.options
import org.springframework.web.filter.CorsFilter
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(
    properties = [
        "tastebuds.rate-limit.enabled=true",
        "tastebuds.rate-limit.capacity=3",
        "tastebuds.rate-limit.refill-tokens=3",
        "tastebuds.rate-limit.refill-period=1h",
        "tastebuds.rate-limit.rules[0].method=GET",
        "tastebuds.rate-limit.rules[0].pattern=/v1/meta",
        "tastebuds.rate-limit.rules[0].cost=2",
        "tastebuds.cors.allowed-origins=https://app.example",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class, RedisTestcontainersConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class RateLimitIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val context: ApplicationContext,
    @Autowired private val filterChainProxy: FilterChainProxy,
) {
    private val json = JsonMapper.builder().build()

    private fun get(
        path: String,
        client: String,
        origin: String? = null,
    ): ResultActionsDsl =
        mockMvc.get(path) {
            with {
                it.remoteAddr = client
                it
            }
            if (origin != null) header(HttpHeaders.ORIGIN, origin)
        }

    private fun wideEvents(output: CapturedOutput): List<JsonNode> =
        output.out
            .lines()
            .filter { it.startsWith("{") }
            .map { json.readTree(it) }
            .filter { it.path("message").asString() == "wide_event" }

    @Test
    fun `rule costs apply and the request over the limit gets a 429 problem`(output: CapturedOutput) {
        val client = "203.0.113.11"
        get("/v1/meta", client).andExpect { status { isOk() } }
        get("/v1/meta", client).andExpect {
            status { isTooManyRequests() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            header { exists(HttpHeaders.RETRY_AFTER) }
            jsonPath("$.type") { value("about:blank") }
            jsonPath("$.title") { value("Too Many Requests") }
            jsonPath("$.status") { value(429) }
            jsonPath("$.detail") { value("Rate limit exceeded.") }
            jsonPath("$.instance") { value("/v1/meta") }
        }
        val retryAfter =
            get("/v1/meta", client)
                .andReturn()
                .response
                .getHeader(HttpHeaders.RETRY_AFTER)
        assertTrue(assertNotNull(retryAfter).toLong() >= 1)

        val events = wideEvents(output).filter { it.has("rate_limit") }
        assertEquals("allowed", events[0].at("/rate_limit/outcome").asString())
        assertEquals(2, events[0].at("/rate_limit/cost").asInt())
        assertEquals(1, events[0].at("/rate_limit/remaining").asInt())
        assertEquals("GET /v1/meta", events[0].at("/rate_limit/rule").asString())
        assertEquals("ip4", events[0].at("/rate_limit/key_type").asString())
        assertEquals("rejected", events[1].at("/rate_limit/outcome").asString())
        assertEquals(429, events[1].at("/http/response/status_code").asInt())
        assertFalse(
            output.out
                .lines()
                .filter { it.contains("wide_event") }
                .any { it.contains(client) },
        )
    }

    @Test
    fun `unauthenticated requests are limited before authentication`(output: CapturedOutput) {
        val client = "203.0.113.12"
        repeat(3) { get("/v1/anything", client).andExpect { status { isUnauthorized() } } }
        get("/v1/anything", client).andExpect { status { isTooManyRequests() } }
        val last = wideEvents(output).filter { it.has("rate_limit") }.last()
        assertEquals("default", last.at("/rate_limit/rule").asString())
    }

    @Test
    fun `clients have independent buckets`() {
        val a = "203.0.113.13"
        repeat(3) { get("/v1/anything", a) }
        get("/v1/anything", a).andExpect { status { isTooManyRequests() } }
        get("/v1/meta", "203.0.113.14").andExpect { status { isOk() } }
    }

    @Test
    fun `unparsable client addresses share one bucket`() {
        repeat(3) { get("/v1/anything", "not-an-ip") }
        get("/v1/anything", "999.1.1.1").andExpect { status { isTooManyRequests() } }
    }

    @Test
    fun `a 429 is readable by allowed browser origins and carries the hardened headers`() {
        val client = "203.0.113.15"
        repeat(3) { get("/v1/anything", client, "https://app.example") }
        get("/v1/anything", client, "https://app.example")
            .andExpect {
                status { isTooManyRequests() }
                header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://app.example") }
                header { string("X-Content-Type-Options", "nosniff") }
            }.andReturn()
            .response
            .let {
                assertTrue(it.getHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS).orEmpty().contains("Retry-After"))
                assertTrue(it.getHeader(HttpHeaders.CACHE_CONTROL).orEmpty().contains("no-store"))
            }
    }

    @Test
    fun `preflights and rejected cross-origin requests cost nothing`(output: CapturedOutput) {
        val client = "203.0.113.16"
        repeat(5) {
            mockMvc.options("/v1/meta") {
                with {
                    it.remoteAddr = client
                    it
                }
                header(HttpHeaders.ORIGIN, "https://app.example")
                header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
            }
        }
        repeat(5) { get("/v1/meta", client, "https://evil.example").andExpect { status { isForbidden() } } }
        get("/v1/meta", client).andExpect { status { isOk() } }

        val events = wideEvents(output)
        assertEquals(1, events.count { it.has("rate_limit") })
        assertEquals(1, events.single { it.has("rate_limit") }.at("/rate_limit/remaining").asInt())
        assertTrue(events.size >= 11)
    }

    @Test
    fun `the limiter runs in the security chain after CORS and before authentication`() {
        val filters = filterChainProxy.filterChains.flatMap { it.filters }
        val limiters = filters.filter { it is RateLimitFilter }
        assertEquals(1, limiters.size)
        val index = filters.indexOf(limiters.single())
        assertTrue(index > filters.indexOfFirst { it is CorsFilter })
        assertTrue(index < filters.indexOfFirst { it is AnonymousAuthenticationFilter })
        assertTrue(index < filters.indexOfFirst { it is AuthorizationFilter })
        assertTrue(context.getBeanNamesForType(RateLimitFilter::class.java).isEmpty())
    }
}
