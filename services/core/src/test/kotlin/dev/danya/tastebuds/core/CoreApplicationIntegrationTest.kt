package dev.danya.tastebuds.core

import ch.qos.logback.classic.Logger
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class CoreApplicationIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
) {
    private val json = JsonMapper.builder().build()

    @Test
    fun `meta endpoint is public and returns service metadata`() {
        mockMvc.get("/v1/meta").andExpect {
            status { isOk() }
            jsonPath("$.service") { value("tastebuds-core") }
            jsonPath("$.role") { value("ALL") }
        }
    }

    @Test
    fun `responses carry hardened security headers`() {
        mockMvc.get("/v1/meta") { secure = true }.andExpect {
            header { string("X-Content-Type-Options", "nosniff") }
            header { string("X-Frame-Options", "DENY") }
            header { string("Referrer-Policy", "no-referrer") }
            header { string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'") }
            header { exists("Strict-Transport-Security") }
            header { doesNotExist("Set-Cookie") }
        }
    }

    @Test
    fun `everything else is denied by default`() {
        mockMvc.get("/v1/anything").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `cross-origin requests from unknown origins are rejected`() {
        mockMvc.get("/v1/meta") { header("Origin", "https://evil.example") }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `each request emits exactly one wide event`(output: CapturedOutput) {
        mockMvc.get("/v1/meta").andExpect { status { isOk() } }

        val events =
            output.out
                .lines()
                .filter { it.startsWith("{") }
                .map { json.readTree(it) }
                .filter { it.path("message").asString() == "wide_event" && it.at("/http/route").asString() == "/v1/meta" }
        assertEquals(1, events.size, "expected one wide event, got: $events")
        val event = events.single()
        assertEquals("http", event.at("/work/kind").asString())
        assertEquals("GET", event.at("/http/request/method").asString())
        assertEquals(200, event.at("/http/response/status_code").asInt())
        assertEquals("success", event.path("outcome").asString())
        assertEquals("all", event.at("/app/role").asString())
        assertTrue(event.path("traceId").asString().isNotBlank(), "wide event must be correlated with its trace")
    }

    @Test
    fun `application logs are bridged to OpenTelemetry`() {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger

        assertNotNull(root.getAppender("OTEL"), "OpenTelemetry log appender must be attached to the root logger")
    }

    @Test
    fun `database migrations ran and pgvector is available`() {
        val extension = jdbc.sql("select extname from pg_extension where extname = 'vector'").query(String::class.java).optional()

        assertTrue(extension.isPresent)
    }
}
