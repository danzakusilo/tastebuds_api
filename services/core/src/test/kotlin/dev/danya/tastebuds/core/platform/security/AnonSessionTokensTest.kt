package dev.danya.tastebuds.core.platform.security

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.Random
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@ExtendWith(OutputCaptureExtension::class)
class AnonSessionTokensTest {
    private val format = Regex("^tba1\\.[a-z0-9]{1,16}\\.[A-Za-z0-9_-]{32}\\.[A-Za-z0-9_-]{43}$")
    private val now = Instant.parse("2026-11-20T10:15:30.789Z")
    private val secret = AnonSessionTestKeys.randomSecret()
    private val ttl = Duration.ofDays(30)

    private fun tokens(
        id: String = "k1",
        secret: ByteArray = this.secret,
        at: Instant = now,
    ) = AnonSessionTestKeys.tokens(id, secret, ttl, at)

    private fun AnonSessionVerification.reason() = assertIs<AnonSessionVerification.Invalid>(this).reason

    @Test
    fun `issued tokens verify and carry the session id and expiry`() {
        val tokens = tokens()
        val issued = tokens.issue()
        assertTrue(format.matches(issued.token), issued.token)
        assertTrue(issued.token.length <= AnonSessionTokens.MAX_TOKEN_LENGTH)
        assertEquals(AnonSessionVerification.Valid(issued.principal), tokens.verify(issued.token))
        assertEquals(Instant.parse("2026-12-20T10:15:30Z"), issued.principal.expiresAt)
    }

    @Test
    fun `each session gets a new random id`() {
        val tokens = tokens()
        assertEquals(1000, (1..1000).map { tokens.issue().principal.sessionId }.toSet().size)
    }

    @Test
    fun `tokens expire at expiresAt`() {
        val issued = tokens().issue()
        val before = tokens(at = issued.principal.expiresAt.minusSeconds(1))
        assertIs<AnonSessionVerification.Valid>(before.verify(issued.token))
        val atExpiry = tokens(at = issued.principal.expiresAt)
        assertEquals(InvalidTokenReason.EXPIRED, atExpiry.verify(issued.token).reason())
    }

    @Test
    fun `tampered tokens are rejected`() {
        val tokens = tokens()
        val token = tokens.issue().token
        val parts = token.split(".")
        val payloadStart = parts[0].length + parts[1].length + 2
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"
        for (i in payloadStart until payloadStart + 32) {
            val replacement = alphabet.first { it != token[i] }
            val tampered = token.replaceRange(i, i + 1, replacement.toString())
            val reason = tokens.verify(tampered).reason()
            assertTrue(reason == InvalidTokenReason.BAD_SIGNATURE || reason == InvalidTokenReason.MALFORMED, "index $i")
        }
        val last = token.last()
        val changed = token.dropLast(1) + alphabet.first { it != last }
        assertEquals(InvalidTokenReason.BAD_SIGNATURE, tokens.verify(changed).reason())

        val other = tokens.issue().token.split(".")
        val spliced = "${parts[0]}.${parts[1]}.${other[2]}.${parts[3]}"
        assertEquals(InvalidTokenReason.BAD_SIGNATURE, tokens.verify(spliced).reason())
    }

    @Test
    fun `the signature is checked before the expiry`() {
        val issued = tokens().issue()
        val later = tokens(at = now.plus(Duration.ofDays(60)))
        val parts = issued.token.split(".")
        val payload = parts[2]
        val changedPayload = (if (payload[0] == 'A') 'B' else 'A') + payload.drop(1)
        val tampered = "${parts[0]}.${parts[1]}.$changedPayload.${parts[3]}"
        assertEquals(InvalidTokenReason.BAD_SIGNATURE, later.verify(tampered).reason())
        assertEquals(InvalidTokenReason.EXPIRED, later.verify(issued.token).reason())
    }

    @Test
    fun `a token signed with another secret under the same key id is rejected`() {
        val token = tokens(secret = AnonSessionTestKeys.randomSecret()).issue().token
        assertEquals(InvalidTokenReason.BAD_SIGNATURE, tokens().verify(token).reason())
    }

    @Test
    fun `tokens with an unknown key id are rejected`() {
        val token = tokens(id = "other").issue().token
        assertEquals(InvalidTokenReason.UNKNOWN_KEY, tokens().verify(token).reason())
    }

    @Test
    fun `older keys still verify after rotation and new tokens use the first key`() {
        val old = AnonSessionTokens.Key("k1", secret)
        val new = AnonSessionTokens.Key("k2", AnonSessionTestKeys.randomSecret())
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val oldToken = AnonSessionTokens(listOf(old), ttl, clock).issue()
        val rotated = AnonSessionTokens(listOf(new, old), ttl, clock)
        assertEquals(AnonSessionVerification.Valid(oldToken.principal), rotated.verify(oldToken.token))
        val fresh = rotated.issue()
        assertTrue(fresh.token.startsWith("tba1.k2."))
        assertEquals(AnonSessionVerification.Valid(fresh.principal), rotated.verify(fresh.token))
    }

    @Test
    fun `the three-step rotation never rejects a token during a rolling deploy`() {
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val k1 = AnonSessionTokens.Key("k1", secret)
        val k2 = AnonSessionTokens.Key("k2", AnonSessionTestKeys.randomSecret())

        fun with(vararg keys: AnonSessionTokens.Key) = AnonSessionTokens(keys.toList(), ttl, clock)
        val steps = listOf(with(k1), with(k1, k2), with(k2, k1), with(k2))
        steps.zipWithNext().forEach { (before, after) ->
            val fromBefore = before.issue()
            val fromAfter = after.issue()
            assertEquals(AnonSessionVerification.Valid(fromBefore.principal), after.verify(fromBefore.token))
            assertEquals(AnonSessionVerification.Valid(fromAfter.principal), before.verify(fromAfter.token))
        }
        // The one-step order of the old text: the new key at index 0 at once.
        val oneStep = with(k2, k1).issue()
        assertEquals(InvalidTokenReason.UNKNOWN_KEY, with(k1).verify(oneStep.token).reason())
    }

    @Test
    fun `a correctly signed token with an expiry outside the Instant range is malformed`() {
        val key = AnonSessionTokens.Key("k1", secret)
        val tokens = AnonSessionTestKeys.tokens("k1", secret, ttl, now)

        fun signed(expirySeconds: Long): String {
            val payload =
                ByteBuffer
                    .allocate(24)
                    .putLong(1L)
                    .putLong(2L)
                    .putLong(expirySeconds)
                    .array()
            val text = "tba1.${key.id}.${Base64.getUrlEncoder().withoutPadding().encodeToString(payload)}"
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
            return "$text.${Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(text.toByteArray(Charsets.US_ASCII)))}"
        }
        listOf(Long.MAX_VALUE, Long.MIN_VALUE, Instant.MAX.epochSecond + 1).forEach {
            assertEquals(InvalidTokenReason.MALFORMED, tokens.verify(signed(it)).reason(), "expiry $it")
        }
        val farFuture = assertIs<AnonSessionVerification.Valid>(tokens.verify(signed(Instant.MAX.epochSecond)))
        assertEquals(Instant.ofEpochSecond(Instant.MAX.epochSecond), farFuture.principal.expiresAt)
    }

    @Test
    fun `the ephemeral key logs exactly one warning`(output: CapturedOutput) {
        val tokens = AnonSessionTokens.fromProperties(AnonSessionProperties(ephemeralSigningKey = true))
        repeat(3) { assertIs<AnonSessionVerification.Valid>(tokens.verify(tokens.issue().token)) }
        val text = "Anonymous sessions use an ephemeral signing key: tokens stop working on restart and are not shared"
        assertEquals(1, output.all.windowed(text.length).count { it == text })
    }

    @Test
    fun `malformed tokens are rejected`() {
        val tokens = tokens()
        val valid = tokens.issue().token
        val parts = valid.split(".")
        val (kid, payload, mac) = parts.drop(1)
        val malformed =
            listOf(
                "",
                "tba1",
                "tba1...",
                "tba2.$kid.$payload.$mac",
                "eyJhbGciOiJub25lIn0.e30.",
                "tba1.K1.$payload.$mac",
                "tba1.$kid.${payload.dropLast(1)}+.$mac",
                "tba1.$kid.${payload.dropLast(1)}/.$mac",
                "tba1.$kid.${payload.dropLast(1)}=.$mac",
                "tba1.$kid.${payload.dropLast(1)}.$mac",
                "tba1.$kid.${payload}A.$mac",
                "tba1.$kid.$payload.${mac.dropLast(1)}",
                " $valid",
                "$valid ",
                "$valid\n",
                "\n$valid",
                "tba1.$kid.$payload.${mac.dropLast(1)}１",
                "tba1.$kid.$payload.$mac" + "A".repeat(129 - valid.length),
                "A".repeat(10_000),
            )
        malformed.forEach { assertEquals(InvalidTokenReason.MALFORMED, tokens.verify(it).reason(), it.take(40)) }
    }

    @Test
    fun `random strings never verify and never throw`() {
        val tokens = tokens()
        val random = Random(42)
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-+/= é"

        fun text(length: Int) = String(CharArray(length) { alphabet[random.nextInt(alphabet.length)] })
        val base64url = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"

        fun b64(length: Int) = String(CharArray(length) { base64url[random.nextInt(base64url.length)] })
        repeat(10_000) { assertIs<AnonSessionVerification.Invalid>(tokens.verify(text(random.nextInt(141)))) }
        repeat(1_000) { assertIs<AnonSessionVerification.Invalid>(tokens.verify("tba1.k1.${b64(32)}.${b64(43)}")) }
    }

    @Test
    fun `missing keys fail unless the ephemeral key is enabled`() {
        val failure = assertFailsWith<IllegalStateException> { AnonSessionTokens.fromProperties(AnonSessionProperties()) }
        assertTrue(failure.message.orEmpty().startsWith("tastebuds.anon-session.signing-keys"))

        val ephemeral = AnonSessionProperties(ephemeralSigningKey = true)
        val a = AnonSessionTokens.fromProperties(ephemeral)
        val b = AnonSessionTokens.fromProperties(ephemeral)
        val issued = a.issue()
        assertEquals(AnonSessionVerification.Valid(issued.principal), a.verify(issued.token))
        assertEquals(InvalidTokenReason.BAD_SIGNATURE, b.verify(issued.token).reason())
    }

    @Test
    fun `configured keys sign with the first and verify with every key`() {
        val first = AnonSessionTestKeys.randomSecret()
        val properties =
            AnonSessionProperties(
                signingKeys =
                    listOf(
                        AnonSessionProperties.SigningKey("a", AnonSessionTestKeys.base64(first)),
                        AnonSessionProperties.SigningKey("b", AnonSessionTestKeys.base64(secret)),
                    ),
            )
        val tokens = AnonSessionTokens.fromProperties(properties)
        assertTrue(tokens.issue().token.startsWith("tba1.a."))
        val signedWithSecond = AnonSessionTestKeys.tokens("b", secret).issue().token
        assertIs<AnonSessionVerification.Valid>(tokens.verify(signedWithSecond))
    }

    @Test
    fun `invalid key sets are rejected`() {
        assertFailsWith<IllegalArgumentException> { AnonSessionTokens(emptyList(), ttl) }
        val key = AnonSessionTokens.Key("k1", secret)
        assertFailsWith<IllegalArgumentException> { AnonSessionTokens(listOf(key, key), ttl) }
        assertFailsWith<IllegalArgumentException> { AnonSessionTokens.Key("K1", secret) }
        assertFailsWith<IllegalArgumentException> { AnonSessionTokens.Key("k1", ByteArray(31)) }
    }

    @Test
    fun `toString never shows tokens or secrets`() {
        val issued = tokens().issue()
        assertFalse(issued.toString().contains(issued.token))
        assertFalse(issued.toString().contains(issued.principal.sessionId.toString()))
        assertFalse(issued.principal.toString().contains(issued.principal.sessionId.toString()))
        val key = AnonSessionTokens.Key("k1", secret)
        assertEquals("Key(id=k1)", key.toString())
        val present = BearerCredential.Present(issued.token)
        assertFalse(present.toString().contains(issued.token))
        assertNotEquals("", present.toString())
    }
}
