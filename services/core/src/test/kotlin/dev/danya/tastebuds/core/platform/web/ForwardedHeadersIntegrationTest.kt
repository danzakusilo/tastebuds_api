package dev.danya.tastebuds.core.platform.web

import dev.danya.tastebuds.core.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class, RequestInfoCapture::class)
class ForwardedHeadersIntegrationTest(
    @Autowired private val capture: RequestInfoCapture.CapturingFilter,
    @LocalServerPort private val port: Int,
) {
    private val client = HttpClient.newHttpClient()

    private fun get(vararg headers: Pair<String, String>): HttpResponse<String> {
        capture.reset()
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port/v1/meta")).GET()
        headers.forEach { (name, value) -> builder.header(name, value) }
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        // A 200 proves the request went through the filter chain, so the capture is from this request.
        assertEquals(200, response.statusCode())
        return response
    }

    private fun isLoopback(address: String) = address == "127.0.0.1" || address == "0:0:0:0:0:0:0:1" || address == "::1"

    @Test
    fun `forwarded client address and scheme are honoured from a trusted proxy`() {
        val response = get("X-Forwarded-For" to "203.0.113.7", "X-Forwarded-Proto" to "https")

        val captured = capture.last()
        assertEquals("203.0.113.7", captured.remoteAddr)
        assertEquals("https", captured.scheme)
        assertTrue(captured.secure)
        assertNotNull(response.headers().firstValue("Strict-Transport-Security").orElse(null))
    }

    @Test
    fun `direct requests keep the peer address and are not secure`() {
        val response = get()

        val captured = capture.last()
        assertTrue(isLoopback(captured.remoteAddr), "expected loopback but was ${captured.remoteAddr}")
        assertFalse(captured.secure)
        assertEquals("http", captured.scheme)
        assertNull(response.headers().firstValue("Strict-Transport-Security").orElse(null))
    }

    @Test
    fun `a client-supplied X-Forwarded-For prefix cannot choose the address`() {
        get("X-Forwarded-For" to "198.51.100.9, 203.0.113.7, 10.0.0.5", "X-Forwarded-Proto" to "https")

        assertEquals("203.0.113.7", capture.last().remoteAddr)
    }
}
