package dev.danya.tastebuds.core.platform.security

import dev.danya.tastebuds.observability.WideEvent
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProblemDetailsSecurityHandlersTest {
    private val json = JsonMapper.builder().build()

    @Test
    fun `entry point writes a 401 problem with a bearer challenge and records the failure`() {
        val event = WideEvent("http")
        val response = MockHttpServletResponse()

        WideEvent.within(event) {
            ProblemDetailsAuthenticationEntryPoint()
                .commence(MockHttpServletRequest("GET", "/v1/x"), response, BadCredentialsException("hunter2"))
        }

        assertEquals(401, response.status)
        assertTrue(response.contentType!!.startsWith("application/problem+json"))
        assertEquals("Bearer", response.getHeader("WWW-Authenticate"))
        val body = json.readTree(response.contentAsString)
        assertEquals("about:blank", body.path("type").asString())
        assertEquals("Unauthorized", body.path("title").asString())
        assertEquals(401, body.path("status").asInt())
        assertEquals("Authentication is required.", body.path("detail").asString())
        assertEquals("/v1/x", body.path("instance").asString())
        assertFalse(response.contentAsString.contains("hunter2"))
        assertEquals("unauthenticated", event["auth.failure"])
    }

    @Test
    fun `access denied handler writes a 403 problem and records the failure`() {
        val event = WideEvent("http")
        val response = MockHttpServletResponse()

        WideEvent.within(event) {
            ProblemDetailsAccessDeniedHandler()
                .handle(MockHttpServletRequest("GET", "/v1/y"), response, AccessDeniedException("hunter2"))
        }

        assertEquals(403, response.status)
        assertTrue(response.contentType!!.startsWith("application/problem+json"))
        assertNull(response.getHeader("WWW-Authenticate"))
        val body = json.readTree(response.contentAsString)
        assertEquals("Forbidden", body.path("title").asString())
        assertEquals(403, body.path("status").asInt())
        assertEquals("Access is denied.", body.path("detail").asString())
        assertEquals("/v1/y", body.path("instance").asString())
        assertFalse(response.contentAsString.contains("hunter2"))
        assertEquals("access_denied", event["auth.failure"])
    }
}
