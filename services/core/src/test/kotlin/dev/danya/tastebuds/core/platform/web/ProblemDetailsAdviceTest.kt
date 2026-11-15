package dev.danya.tastebuds.core.platform.web

import dev.danya.tastebuds.observability.WideEvent
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
}
