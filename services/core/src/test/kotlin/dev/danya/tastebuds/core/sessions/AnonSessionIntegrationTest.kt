package dev.danya.tastebuds.core.sessions

import dev.danya.tastebuds.core.TestcontainersConfiguration
import dev.danya.tastebuds.core.platform.ratelimit.RateLimitProperties
import dev.danya.tastebuds.core.platform.security.AnonSessionAuthenticationFilter
import dev.danya.tastebuds.core.platform.security.AnonSessionTestKeys
import dev.danya.tastebuds.core.platform.security.AnonSessionVerification
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
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.options
import org.springframework.test.web.servlet.post
import org.springframework.web.filter.CorsFilter
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@SpringBootTest(properties = ["tastebuds.cors.allowed-origins=https://app.example"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class AnonSessionIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val context: ApplicationContext,
    @Autowired private val filterChainProxy: FilterChainProxy,
    @Autowired private val rateLimitProperties: RateLimitProperties,
) {
    private val json = JsonMapper.builder().build()
    private val tokenFormat = Regex("^tba1\\.it\\.[A-Za-z0-9_-]{32}\\.[A-Za-z0-9_-]{43}$")

    private fun create(origin: String? = null): ResultActionsDsl =
        mockMvc.post("/v1/sessions/anonymous") {
            if (origin != null) header(HttpHeaders.ORIGIN, origin)
        }

    private fun createToken(): String = json.readTree(create().andReturn().response.contentAsString).path("token").asString()

    private fun get(
        path: String,
        token: String? = null,
    ): ResultActionsDsl =
        mockMvc.get(path) {
            if (token != null) header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }

    private fun wideEvents(output: CapturedOutput): List<JsonNode> =
        output.out
            .lines()
            .filter { it.startsWith("{") }
            .map { json.readTree(it) }
            .filter { it.path("message").asString() == "wide_event" }

    @Test
    fun `creating a session returns a bearer token and its expiry`() {
        val before = Instant.now()
        val result =
            create()
                .andExpect {
                    status { isCreated() }
                    content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
                    header { string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")) }
                    header { doesNotExist(HttpHeaders.SET_COOKIE) }
                }.andReturn()
        val body = json.readTree(result.response.contentAsString)
        assertEquals(setOf("token", "tokenType", "expiresAt"), body.propertyNames().toSet())
        assertTrue(tokenFormat.matches(body.path("token").asString()), body.path("token").asString())
        assertEquals("Bearer", body.path("tokenType").asString())
        val expiresText = body.path("expiresAt").asString()
        assertTrue(Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$").matches(expiresText), expiresText)
        val expected = before.plus(Duration.ofDays(30))
        val expires = Instant.parse(expiresText)
        assertTrue(Duration.between(expected, expires).abs() < Duration.ofMinutes(1), expiresText)
    }

    @Test
    fun `the token authenticates later requests as an anonymous session`(output: CapturedOutput) {
        val token = createToken()
        get("/v1/meta", token).andExpect { status { isOk() } }
        val event = wideEvents(output).last { it.at("/http/route").asString() == "/v1/meta" }
        assertEquals("anon", event.at("/principal/tier").asString())
    }

    @Test
    fun `an anonymous session opens no endpoint without its own rule`() {
        val token = createToken()
        get("/v1/anything", token).andExpect {
            status { isForbidden() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.title") { value("Forbidden") }
        }
        get("/v1/anything").andExpect {
            status { isUnauthorized() }
            header { string(HttpHeaders.WWW_AUTHENTICATE, "Bearer") }
        }
    }

    @Test
    fun `invalid, forged and expired tokens get one uniform 401 problem`(output: CapturedOutput) {
        val real = createToken()
        val expired = AnonSessionTestKeys.tokens("it", secret, Duration.ofHours(1), Instant.now().minus(Duration.ofHours(2))).issue().token
        val cases =
            listOf(
                "garbage" to "malformed",
                (real.dropLast(1) + (if (real.last() == 'A') 'B' else 'A')) to "bad_signature",
                AnonSessionTestKeys.tokens("it", AnonSessionTestKeys.randomSecret()).issue().token to "bad_signature",
                AnonSessionTestKeys.tokens("other", secret).issue().token to "unknown_key",
                expired to "expired",
            )
        val bodies = HashSet<String>()
        cases.forEach { (token, reason) ->
            val response =
                get("/v1/meta", token)
                    .andExpect {
                        status { isUnauthorized() }
                        header { string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"") }
                        content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
                    }.andReturn()
                    .response
            bodies += response.contentAsString
            val event = wideEvents(output).last { it.at("/auth/failure").asString() == "invalid_token" }
            assertEquals(reason, event.at("/auth/failure_reason").asString(), token)
        }
        val empty =
            mockMvc
                .get("/v1/meta") { header(HttpHeaders.AUTHORIZATION, "Bearer") }
                .andExpect {
                    status { isUnauthorized() }
                    header { string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"") }
                }.andReturn()
                .response
        bodies += empty.contentAsString
        assertEquals(1, bodies.size, bodies.toString())
        assertEquals("malformed", wideEvents(output).last { it.has("auth") }.at("/auth/failure_reason").asString())
    }

    @Test
    fun `tokens are read only from the Authorization header`() {
        val token = createToken()
        mockMvc
            .get("/v1/anything?access_token=$token")
            .andExpect {
                status { isUnauthorized() }
                header { string(HttpHeaders.WWW_AUTHENTICATE, "Bearer") }
            }
        mockMvc
            .get("/v1/anything") { header(HttpHeaders.COOKIE, "token=$token") }
            .andExpect {
                status { isUnauthorized() }
                header { string(HttpHeaders.WWW_AUTHENTICATE, "Bearer") }
            }
    }

    @Test
    fun `a browser on an allowed origin can create a session`() {
        mockMvc
            .options("/v1/sessions/anonymous") {
                header(HttpHeaders.ORIGIN, "https://app.example")
                header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization")
            }.andExpect { status { isOk() } }
        create("https://app.example").andExpect {
            status { isCreated() }
            header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://app.example") }
        }
    }

    @Test
    fun `tokens and session ids never appear in logs`(output: CapturedOutput) {
        val token = createToken()
        get("/v1/meta", token).andExpect { status { isOk() } }
        val principal = assertIs<AnonSessionVerification.Valid>(AnonSessionTestKeys.tokens("it", secret).verify(token)).principal
        assertFalse(output.all.contains(token))
        assertFalse(output.all.contains(principal.sessionId.toString()))
    }

    @Test
    fun `the anonymous security chain has the filter once, before anonymous authentication`() {
        val filters = filterChainProxy.filterChains.flatMap { it.filters }
        val ours = filters.filter { it is AnonSessionAuthenticationFilter }
        assertEquals(1, ours.size)
        val index = filters.indexOf(ours.single())
        val corsIndex = filters.indexOfFirst { it is CorsFilter }
        assertTrue(corsIndex >= 0, "CorsFilter must be in the chain")
        assertTrue(index > corsIndex)
        assertTrue(index < filters.indexOfFirst { it is AnonymousAuthenticationFilter })
        assertTrue(context.getBeanNamesForType(AnonSessionAuthenticationFilter::class.java).isEmpty())
    }

    @Test
    fun `the session response never shows the token in toString`() {
        val token = AnonSessionTestKeys.tokens("it", secret).issue().token
        val text = AnonSessionResponse(token = token, tokenType = "Bearer", expiresAt = Instant.now()).toString()
        assertFalse(text.contains(token))
        assertTrue(text.contains("[REDACTED]"))
    }

    @Test
    fun `session creation has its own rate-limit cost`() {
        assertTrue(rateLimitProperties.rules.contains(RateLimitProperties.Rule("POST", "/v1/sessions/anonymous", 10)))
    }

    companion object {
        private val secret = AnonSessionTestKeys.randomSecret()

        @JvmStatic
        @DynamicPropertySource
        fun keys(registry: DynamicPropertyRegistry) {
            registry.add("tastebuds.anon-session.signing-keys[0].id") { "it" }
            registry.add("tastebuds.anon-session.signing-keys[0].secret") { AnonSessionTestKeys.base64(secret) }
        }
    }
}
