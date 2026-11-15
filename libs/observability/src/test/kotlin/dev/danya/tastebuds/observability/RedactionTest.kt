package dev.danya.tastebuds.observability

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactionTest {
    @Test
    fun `redacts keys containing a sensitive word`() {
        assertAllSensitive(
            "user.email",
            "auth.refresh_token",
            "db.password_hash",
            "http.request.header.authorization",
            "client.api_key",
            "client.apikey",
            "session.cookie",
            "oauth.client_secret",
            "db.passwd",
            "user.phone",
            "user.ssn",
            "auth.token",
        )
    }

    @Test
    fun `redacts plurals and run-together names`() {
        assertAllSensitive(
            "auth.tokens",
            "user.emails",
            "db.passwords",
            "http.cookies",
            "client.api_keys",
            "auth.accesstoken",
            "auth.refreshtoken",
            "user.userpassword",
        )
    }

    @Test
    fun `exempts only the exact reserved llm token counters`() {
        listOf("llm.tokens_in", "llm.tokens_out").forEach { assertFalse(Redaction.isSensitive(it), "$it should not be sensitive") }
        assertAllSensitive("llm.tokens_in.raw", "x.llm.tokens_in", "llm.tokens_total")
    }

    @Test
    fun `keeps ordinary keys`() {
        listOf("llm.cost_usd", "http.route", "api.keyboard_layout", "db.query_count")
            .forEach { assertFalse(Redaction.isSensitive(it), "$it should not be sensitive") }
    }

    private fun assertAllSensitive(vararg keys: String) = keys.forEach { assertTrue(Redaction.isSensitive(it), "$it should be sensitive") }
}
