package dev.danya.tastebuds.observability

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.HandlerMapping
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WideEventFilterTest {
    private val emitted = mutableListOf<WideEvent>()
    private val filter =
        WideEventFilter(
            sink = { emitted += it },
            enrichers = listOf(WideEventEnricher { it.put("app.role", "api") }),
            excludePaths = listOf("/actuator/**"),
        )

    private fun request(path: String) = MockHttpServletRequest("GET", path)

    @Test
    fun `emits one event per request with route, status, duration and enrichment`() {
        val request = request("/v1/media/42")
        val chain =
            FilterChain { req, res ->
                req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/v1/media/{id}")
                WideEvent.current()!!.put("catalog.hit", true)
                (res as MockHttpServletResponse).status = 200
            }

        filter.doFilter(request, MockHttpServletResponse(), chain)

        val event = emitted.single()
        assertEquals("http", event["work.kind"])
        assertEquals("GET", event["http.request.method"])
        assertEquals("/v1/media/{id}", event["http.route"])
        assertEquals(200, event["http.response.status_code"])
        assertEquals("success", event["outcome"])
        assertEquals("api", event["app.role"])
        assertEquals(true, event["catalog.hit"])
        assertNotNull(event["duration_ms"])
    }

    @Test
    fun `classifies client errors and unmatched routes`() {
        val response = MockHttpServletResponse().apply { status = 404 }

        filter.doFilter(request("/nope"), response, MockFilterChain())

        val event = emitted.single()
        assertEquals("client_error", event["outcome"])
        assertEquals(WideEventFilter.UNMATCHED, event["http.route"])
    }

    @Test
    fun `records unhandled exceptions as errors and rethrows them`() {
        val boom = IllegalStateException("boom")

        val thrown =
            assertThrows<IllegalStateException> { filter.doFilter(request("/v1/x"), MockHttpServletResponse()) { _, _ -> throw boom } }

        assertSame(boom, thrown)
        val event = emitted.single()
        assertEquals(500, event["http.response.status_code"])
        assertEquals("error", event["outcome"])
        assertEquals("java.lang.IllegalStateException", event["error.class"])
    }

    @Test
    fun `keeps an outcome set explicitly by the handler`() {
        filter.doFilter(request("/v1/x"), MockHttpServletResponse()) { _, _ -> WideEvent.current()!!.put("outcome", "degraded") }

        assertEquals("degraded", emitted.single()["outcome"])
    }

    @Test
    fun `skips excluded paths`() {
        filter.doFilter(request("/actuator/health/liveness"), MockHttpServletResponse(), MockFilterChain())

        assertTrue(emitted.isEmpty())
    }
}
