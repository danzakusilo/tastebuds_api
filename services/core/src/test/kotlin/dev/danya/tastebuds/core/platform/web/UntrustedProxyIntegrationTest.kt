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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["server.tomcat.remoteip.internal-proxies=10.255.255.255/32"],
)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class, RequestInfoCapture::class)
class UntrustedProxyIntegrationTest(
    @Autowired private val capture: RequestInfoCapture.CapturingFilter,
    @LocalServerPort private val port: Int,
) {
    private val client = HttpClient.newHttpClient()

    @Test
    fun `forwarded headers from an untrusted peer are ignored`() {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/v1/meta"))
                .header("X-Forwarded-For", "203.0.113.7")
                .header("X-Forwarded-Proto", "https")
                .GET()
                .build()

        capture.reset()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode())

        val captured = capture.last()
        assertNotEquals("203.0.113.7", captured.remoteAddr)
        assertTrue(
            captured.remoteAddr == "127.0.0.1" || captured.remoteAddr == "0:0:0:0:0:0:0:1",
            "expected loopback but was ${captured.remoteAddr}",
        )
        assertFalse(captured.secure)
        assertNull(response.headers().firstValue("Strict-Transport-Security").orElse(null))
    }
}
