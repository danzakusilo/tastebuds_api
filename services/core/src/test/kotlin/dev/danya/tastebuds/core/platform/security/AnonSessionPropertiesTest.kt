package dev.danya.tastebuds.core.platform.security

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnonSessionPropertiesTest {
    private val secret = AnonSessionTestKeys.base64(AnonSessionTestKeys.randomSecret())

    private fun key(
        id: String = "k1",
        secret: String = this.secret,
    ) = AnonSessionProperties.SigningKey(id, secret)

    private fun assertRejected(
        expectedPrefix: String,
        secretToHide: String? = null,
        build: () -> AnonSessionProperties,
    ) {
        val message = assertFailsWith<IllegalArgumentException> { build() }.message.orEmpty()
        assertTrue(message.startsWith(expectedPrefix), "message: $message")
        if (secretToHide != null) assertFalse(message.contains(secretToHide))
    }

    @Test
    fun `defaults are valid`() {
        val p = AnonSessionProperties()
        assertEquals(Duration.ofDays(30), p.ttl)
        assertTrue(p.signingKeys.isEmpty())
        assertFalse(p.ephemeralSigningKey)
    }

    @Test
    fun `boundary values are valid`() {
        AnonSessionProperties(ttl = Duration.ofHours(1))
        AnonSessionProperties(ttl = Duration.ofDays(90), signingKeys = listOf(key("a".repeat(16)), key("k2")))
    }

    @Test
    fun `invalid values are rejected`() {
        val ttl = "tastebuds.anon-session.ttl"
        assertRejected(ttl) { AnonSessionProperties(ttl = Duration.ofMinutes(59)) }
        assertRejected(ttl) { AnonSessionProperties(ttl = Duration.ofDays(90).plusSeconds(1)) }
        listOf("", "a".repeat(17), "K1", "k.1").forEach { id ->
            assertRejected("tastebuds.anon-session.signing-keys[0].id must match") {
                AnonSessionProperties(signingKeys = listOf(key(id)))
            }
        }
        assertRejected("tastebuds.anon-session.signing-keys[1].id must be unique") {
            AnonSessionProperties(signingKeys = listOf(key("k1"), key("k1")))
        }
        val secretPrefix = "tastebuds.anon-session.signing-keys[0].secret must be Base64 of at least 32 bytes"
        assertRejected(secretPrefix, "not base64!") { AnonSessionProperties(signingKeys = listOf(key(secret = "not base64!"))) }
        val short = AnonSessionTestKeys.base64(ByteArray(31) { 7 })
        assertRejected(secretPrefix, short) { AnonSessionProperties(signingKeys = listOf(key(secret = short))) }
    }

    @Test
    fun `toString never shows the secret`() {
        val properties = AnonSessionProperties(signingKeys = listOf(key()))
        assertFalse(properties.toString().contains(secret))
        assertFalse(key().toString().contains(secret))
    }
}
