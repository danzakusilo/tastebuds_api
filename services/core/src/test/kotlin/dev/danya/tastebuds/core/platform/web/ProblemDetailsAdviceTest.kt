package dev.danya.tastebuds.core.platform.web

import dev.danya.tastebuds.observability.WideEvent
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.mock.http.MockHttpInputMessage
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.web.context.request.ServletWebRequest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class ProblemDetailsAdviceTest {
    private val advice = ProblemDetailsAdvice()

    @Test
    fun `unexpected errors become a generic 500 with trace id and are recorded on the wide event`() {
        val event = WideEvent("http")
        MDC.put("traceId", "abc123")
        try {
            val problem =
                WideEvent.within(event) { advice.handleUnexpected(IllegalStateException("db password is hunter2")) }

            assertEquals(500, problem.status)
            assertEquals("An unexpected error occurred.", problem.detail)
            assertEquals("abc123", problem.properties?.get("traceId"))
            assertFalse(problem.toString().contains("hunter2"))
            assertEquals("java.lang.IllegalStateException", event["error.class"])
        } finally {
            MDC.clear()
        }
    }

    @Test
    fun `security exceptions are rethrown for Spring Security`() {
        val denied = AccessDeniedException("x")
        val unauthenticated = BadCredentialsException("x")

        assertSame(denied, assertFailsWith<AccessDeniedException> { advice.handleUnexpected(denied) })
        assertSame(unauthenticated, assertFailsWith<BadCredentialsException> { advice.handleUnexpected(unauthenticated) })
    }

    @Test
    fun `raw body-limit exception maps to 413`() {
        val event = WideEvent("http")

        val problem = WideEvent.within(event) { advice.handleUnexpected(RequestBodyTooLargeException(1024)) }

        assertEquals(413, problem.status)
        assertEquals("Content Too Large", problem.title)
        assertEquals("Request body exceeds 1024 bytes.", problem.detail)
        assertEquals(true, event["http.request.body_too_large"])
    }

    @Test
    fun `body-limit exception wrapped in HttpMessageNotReadableException maps to 413`() {
        val event = WideEvent("http")
        val wrapped = HttpMessageNotReadableException("unreadable", RequestBodyTooLargeException(1024), MockHttpInputMessage(ByteArray(0)))

        val response = WideEvent.within(event) { advice.handleException(wrapped, ServletWebRequest(MockHttpServletRequest())) }

        assertEquals(413, response?.statusCode?.value())
        assertEquals(true, event["http.request.body_too_large"])
        assertEquals(413, (response?.body as ProblemDetail).status)
    }
}
