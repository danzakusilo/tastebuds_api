package dev.danya.tastebuds.observability

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WideEventTest {
    @Test
    fun `keeps scalar values and records kind`() {
        val event =
            WideEvent("job")
                .put("count", 3)
                .put("ok", true)
                .put("ratio", 0.5)
                .put("name", "x")

        assertEquals(mapOf("work.kind" to "job", "count" to 3, "ok" to true, "ratio" to 0.5, "name" to "x"), event.snapshot())
    }

    @Test
    fun `redacts sensitive keys regardless of value`() {
        val event = WideEvent("http").put("user.email", "a@b.c").put("auth.refresh_token", "abc").put("db.password_hash", "x")

        assertEquals(Redaction.REDACTED, event["user.email"])
        assertEquals(Redaction.REDACTED, event["auth.refresh_token"])
        assertEquals(Redaction.REDACTED, event["db.password_hash"])
    }

    @Test
    fun `truncates long strings and refuses complex objects`() {
        val event = WideEvent("http").put("long", "a".repeat(1_000)).put("obj", listOf(1, 2)).put("tier", Outcome.CLIENT_ERROR)

        assertEquals(Redaction.MAX_STRING_LENGTH + 1, (event["long"] as String).length)
        assertTrue((event["obj"] as String).startsWith("unsupported:"))
        assertEquals("client_error", event["tier"])
    }

    @Test
    fun `rejects keys outside the naming convention`() {
        assertThrows<IllegalArgumentException> { WideEvent("http").put("Bad Key", 1) }
        assertThrows<IllegalArgumentException> { WideEvent("http").put("trailing.", 1) }
    }

    @Test
    fun `increments counters`() {
        val event = WideEvent("http").increment("db.query_count").increment("db.query_count", 2)

        assertEquals(3L, event["db.query_count"])
    }

    @Test
    fun `binds the current event only within the block and restores the outer one`() {
        val outer = WideEvent("outer")
        val inner = WideEvent("inner")

        assertNull(WideEvent.current())
        WideEvent.within(outer) {
            WideEvent.within(inner) { assertSame(inner, WideEvent.current()) }
            assertSame(outer, WideEvent.current())
        }
        assertNull(WideEvent.current())
    }

    @Test
    fun `records error identity without the message`() {
        val event = WideEvent("http").recordError(IllegalStateException("user secret in message"))

        assertEquals("java.lang.IllegalStateException", event["error.class"])
        assertEquals(12, (event["error.fingerprint"] as String).length)
        assertTrue(event.snapshot().values.none { it.toString().contains("user secret") })
    }
}
