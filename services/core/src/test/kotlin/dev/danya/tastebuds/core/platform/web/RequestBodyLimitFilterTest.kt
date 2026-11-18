package dev.danya.tastebuds.core.platform.web

import dev.danya.tastebuds.observability.WideEvent
import jakarta.servlet.Filter
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.filter.FormContentFilter
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RequestBodyLimitFilterTest {
    private val limit = 16L
    private val filter = RequestBodyLimitFilter(limit)

    private class MockServlet : HttpServlet()

    private class UnknownLength(
        request: HttpServletRequest,
    ) : HttpServletRequestWrapper(request) {
        override fun getContentLengthLong(): Long = -1

        override fun getContentLength(): Int = -1
    }

    private fun request(size: Int) = MockHttpServletRequest("POST", "/v1/x").apply { setContent(ByteArray(size) { 'a'.code.toByte() }) }

    @Test
    fun `declared length over the limit is rejected with a 413 problem without calling the chain`() {
        val event = WideEvent("http")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        WideEvent.within(event) { filter.doFilter(request(17), response, chain) }

        assertEquals(413, response.status)
        assertTrue(response.contentType!!.startsWith("application/problem+json"))
        val body = JsonMapper.builder().build().readTree(response.contentAsString)
        assertEquals("Content Too Large", body.path("title").asString())
        assertEquals("Request body exceeds 16 bytes.", body.path("detail").asString())
        assertEquals("/v1/x", body.path("instance").asString())
        assertNull(chain.request)
        assertEquals(true, event["http.request.body_too_large"])
    }

    @Test
    fun `streaming past the limit without a content length fails with RequestBodyTooLargeException`() {
        val chain = MockFilterChain()
        filter.doFilter(UnknownLength(request(17)), MockHttpServletResponse(), chain)

        val forwarded = chain.request as HttpServletRequest
        val error = assertFailsWith<RequestBodyTooLargeException> { forwarded.inputStream.readAllBytes() }
        assertEquals(limit, error.limitBytes)
    }

    @Test
    fun `a body of exactly the limit is passed through via stream and reader`() {
        val viaStream = MockFilterChain()
        filter.doFilter(UnknownLength(request(16)), MockHttpServletResponse(), viaStream)
        assertEquals(16, (viaStream.request as HttpServletRequest).inputStream.readAllBytes().size)

        val viaReader = MockFilterChain()
        filter.doFilter(UnknownLength(request(16)), MockHttpServletResponse(), viaReader)
        assertEquals("a".repeat(16), (viaReader.request as HttpServletRequest).reader.readText())
    }

    @Test
    fun `an oversized unknown-length form body read by an earlier filter becomes a 413 problem`() {
        val event = WideEvent("http")
        val response = MockHttpServletResponse()
        val formRequest =
            MockHttpServletRequest("PUT", "/v1/x").apply {
                contentType = "application/x-www-form-urlencoded"
                setContent("a=".plus("b".repeat(20)).toByteArray())
            }
        val chain = MockFilterChain(MockServlet(), FormContentFilter())

        WideEvent.within(event) { filter.doFilter(UnknownLength(formRequest), response, chain) }

        assertEquals(413, response.status)
        assertTrue(response.contentType!!.startsWith("application/problem+json"))
        assertEquals(true, event["http.request.body_too_large"])
    }

    @Test
    fun `unrelated exceptions from the chain pass through untouched`() {
        val event = WideEvent("http")
        val response = MockHttpServletResponse()
        val boom = IllegalStateException("x")
        val chain = MockFilterChain(MockServlet(), Filter { _, _, _ -> throw boom })

        val thrown = WideEvent.within(event) { assertFailsWith<IllegalStateException> { filter.doFilter(request(1), response, chain) } }

        assertSame(boom, thrown)
        assertEquals(200, response.status)
        assertEquals("", response.contentAsString)
        assertNull(event["http.request.body_too_large"])
    }

    @Test
    fun `a body-limit failure after the response is committed is rethrown`() {
        val response = MockHttpServletResponse()
        val chain =
            MockFilterChain(
                MockServlet(),
                Filter { req, res, _ ->
                    res.writer.write("partial")
                    res.flushBuffer()
                    req.inputStream.readAllBytes()
                },
            )

        assertFailsWith<RequestBodyTooLargeException> { filter.doFilter(UnknownLength(request(17)), response, chain) }
        assertEquals("partial", response.contentAsString)
    }

    @Test
    fun `reading the body through two getReader calls yields the whole body`() {
        val chain = MockFilterChain()
        filter.doFilter(UnknownLength(request(10)), MockHttpServletResponse(), chain)
        val forwarded = chain.request as HttpServletRequest

        assertSame(forwarded.reader, forwarded.reader)
        val first = CharArray(4)
        forwarded.reader.read(first)
        assertEquals("a".repeat(10), String(first) + forwarded.reader.readText())
    }

    @Test
    fun `single byte reads are counted too`() {
        val chain = MockFilterChain()
        filter.doFilter(UnknownLength(request(17)), MockHttpServletResponse(), chain)
        val stream = (chain.request as HttpServletRequest).inputStream

        repeat(16) { assertEquals('a'.code, stream.read()) }
        assertFailsWith<RequestBodyTooLargeException> { stream.read() }
    }

    @Test
    fun `the reader also enforces the limit`() {
        val chain = MockFilterChain()
        filter.doFilter(UnknownLength(request(17)), MockHttpServletResponse(), chain)

        assertFailsWith<RequestBodyTooLargeException> { (chain.request as HttpServletRequest).reader.readText() }
    }
}
