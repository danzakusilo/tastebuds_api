package dev.danya.tastebuds.core.platform.security

import dev.danya.tastebuds.observability.WideEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnonSessionAuthenticationFilterTest {
    private val secret = AnonSessionTestKeys.randomSecret()
    private val tokens = AnonSessionTestKeys.tokens("k1", secret)
    private val filter = AnonSessionAuthenticationFilter(tokens)
    private val json = JsonMapper.builder().build()

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    private class Run(
        val event: WideEvent,
        val response: MockHttpServletResponse,
        val chainCalled: Boolean,
        val authentication: Authentication?,
    )

    private fun run(authorization: String?): Run {
        val request = MockHttpServletRequest("GET", "/v1/meta")
        if (authorization != null) request.addHeader("Authorization", authorization)
        val response = MockHttpServletResponse()
        val event = WideEvent("http")
        var called = false
        var captured: Authentication? = null
        val chain =
            jakarta.servlet.FilterChain { _, _ ->
                called = true
                captured = SecurityContextHolder.getContext().authentication
            }
        WideEvent.within(event) { filter.doFilter(request, response, chain) }
        return Run(event, response, called, captured)
    }

    @Test
    fun `a valid token authenticates the request as an anonymous session`() {
        val issued = tokens.issue()
        val run = run("Bearer ${issued.token}")
        assertTrue(run.chainCalled)
        val authentication = assertIs<AnonSessionAuthentication>(assertNotNull(run.authentication))
        assertTrue(authentication.isAuthenticated)
        assertEquals(listOf("ANON_SESSION"), authentication.authorities.map { it.authority })
        assertEquals(issued.principal, authentication.principal)
        assertNull(authentication.credentials)
        assertEquals("anon", run.event["principal.tier"])
        assertFalse(
            run.event
                .snapshot()
                .values
                .any { it.toString().contains(issued.token) || it.toString().contains(issued.principal.sessionId.toString()) },
        )
    }

    @Test
    fun `the authentication never reveals the session id`() {
        val issued = tokens.issue()
        val authentication = assertNotNull(run("Bearer ${issued.token}").authentication)
        assertEquals("anon-session", authentication.name)
        assertFalse(authentication.toString().contains(issued.principal.sessionId.toString()))
    }

    @Test
    fun `requests without credentials continue unauthenticated`() {
        listOf(null, "Basic dXNlcjpwYXNz").forEach { header ->
            val run = run(header)
            assertTrue(run.chainCalled)
            assertNull(run.authentication)
            assertTrue(
                run.event
                    .snapshot()
                    .keys
                    .none { it.startsWith("auth.") || it.startsWith("principal.") },
            )
        }
    }

    @Test
    fun `invalid tokens stop the request with a uniform 401 problem`() {
        val valid = tokens.issue().token
        val cases =
            mapOf(
                "malformed" to "garbage",
                "unknown_key" to AnonSessionTestKeys.tokens("other", secret).issue().token,
                "bad_signature" to valid.dropLast(1) + (if (valid.last() == 'A') 'B' else 'A'),
                "expired" to
                    AnonSessionTestKeys
                        .tokens("k1", secret, Duration.ofHours(1), Instant.now().minus(Duration.ofHours(2)))
                        .issue()
                        .token,
            )
        val bodies = HashSet<String>()
        cases.forEach { (reason, token) ->
            val run = run("Bearer $token")
            assertFalse(run.chainCalled, reason)
            assertEquals(401, run.response.status, reason)
            assertEquals("Bearer error=\"invalid_token\"", run.response.getHeader("WWW-Authenticate"), reason)
            assertTrue(
                run.response.contentType
                    .orEmpty()
                    .startsWith("application/problem+json"),
                reason,
            )
            val body = run.response.contentAsString
            bodies += body
            val parsed = json.readTree(body)
            assertEquals("Unauthorized", parsed.path("title").asString())
            assertEquals("The bearer token is invalid or expired.", parsed.path("detail").asString())
            assertEquals("/v1/meta", parsed.path("instance").asString())
            assertEquals("invalid_token", run.event["auth.failure"])
            assertEquals(reason, run.event["auth.failure_reason"])
            assertFalse(body.contains(token))
            assertFalse(
                run.event
                    .snapshot()
                    .values
                    .any { it.toString().contains(token) },
            )
            assertNull(SecurityContextHolder.getContext().authentication)
        }
        assertEquals(1, bodies.size)
    }

    @Test
    fun `a malformed bearer header is rejected like an invalid token`() {
        val run = run("Bearer")
        assertFalse(run.chainCalled)
        assertEquals(401, run.response.status)
        assertEquals("malformed", run.event["auth.failure_reason"])
    }
}
