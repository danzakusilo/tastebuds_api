package dev.danya.tastebuds.core.platform.web

import dev.danya.tastebuds.core.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class, ErrorResponsesIntegrationTest.ThrowingController::class)
@ExtendWith(OutputCaptureExtension::class)
class ErrorResponsesIntegrationTest(
    @Autowired private val context: WebApplicationContext,
    /** Boot's MockMvc: every Filter/FilterRegistrationBean in production order (wide event, body limit, security). */
    @Autowired private val productionMockMvc: MockMvc,
) {
    private val json = JsonMapper.builder().build()

    // Only the Spring Security test support (so @WithMockUser reaches the stateless chain); no other servlet filters.
    private val mockMvc: MockMvc =
        MockMvcBuilders
            .webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    @RestController
    class ThrowingController {
        @GetMapping("/v1/test/access-denied")
        fun accessDenied(): String = throw AccessDeniedException("x")

        @GetMapping("/v1/test/authentication-failure")
        fun authenticationFailure(): String = throw BadCredentialsException("x")

        @GetMapping("/v1/test/boom")
        fun boom(): String = throw IllegalStateException("secret detail")
    }

    @Test
    fun `unauthenticated requests get a 401 problem with a bearer challenge`() {
        productionMockMvc.get("/v1/anything").andExpect {
            status { isUnauthorized() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            header { string("WWW-Authenticate", "Bearer") }
            jsonPath("$.status") { value(401) }
            jsonPath("$.title") { value("Unauthorized") }
            jsonPath("$.instance") { value("/v1/anything") }
        }
    }

    @Test
    @WithMockUser
    fun `access denied inside a controller becomes a 403 problem, not a 500`() {
        mockMvc.get("/v1/test/access-denied").andExpect {
            status { isForbidden() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.title") { value("Forbidden") }
            jsonPath("$.instance") { value("/v1/test/access-denied") }
        }
    }

    @Test
    @WithMockUser
    fun `authentication failures inside a controller become a 401 problem, not a 500`() {
        mockMvc.get("/v1/test/authentication-failure").andExpect {
            status { isUnauthorized() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.title") { value("Unauthorized") }
        }
    }

    @Test
    @WithMockUser
    fun `unexpected errors stay a generic 500 problem without the message`() {
        val body =
            mockMvc
                .get("/v1/test/boom")
                .andExpect {
                    status { isInternalServerError() }
                    content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
                    jsonPath("$.detail") { value("An unexpected error occurred.") }
                }.andReturn()
                .response
                .contentAsString

        assertFalse(body.contains("secret detail"))
    }

    @Test
    fun `oversized bodies are rejected with 413 before authentication`(output: CapturedOutput) {
        productionMockMvc
            .post("/v1/meta") {
                contentType = MediaType.APPLICATION_OCTET_STREAM
                content = ByteArray(1024 * 1024 + 1)
            }.andExpect {
                status { isContentTooLarge() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.title") { value("Content Too Large") }
            }

        val events =
            output.out
                .lines()
                .filter { it.startsWith("{") }
                .map { json.readTree(it) }
                .filter { it.path("message").asString() == "wide_event" && it.at("/http/request/method").asString() == "POST" }
        val event = events.single()
        assertEquals(true, event.at("/http/request/body_too_large").asBoolean())
        assertEquals(413, event.at("/http/response/status_code").asInt())
    }
}
