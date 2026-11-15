package dev.danya.tastebuds.core

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatusCode
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real HTTP against the separate management port, as Kubernetes probes will call it. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
class ManagementEndpointsIntegrationTest(
    @LocalManagementPort private val managementPort: Int,
) {
    private val client = RestClient.builder().baseUrl("http://localhost:$managementPort").build()

    private fun statusOf(path: String): HttpStatusCode =
        client
            .get()
            .uri(path)
            .exchange { _, response -> response.statusCode }

    @Test
    fun `liveness and readiness probes are reachable without credentials`() {
        assertEquals(200, statusOf("/actuator/health/liveness").value())
        assertEquals(200, statusOf("/actuator/health/readiness").value())
    }

    @Test
    fun `sensitive actuator endpoints are not exposed`() {
        // Deny-by-default answers 401 before routing can answer 404; either way nothing leaks.
        listOf("/actuator/env", "/actuator/heapdump", "/actuator/configprops").forEach { path ->
            assertTrue(statusOf(path).value() in setOf(401, 404), "$path must not be reachable")
        }
    }
}
