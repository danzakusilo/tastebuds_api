package dev.danya.tastebuds.core.platform.security

import dev.danya.tastebuds.core.platform.AnonSessionIssuer
import dev.danya.tastebuds.core.platform.AnonSessionPrincipal
import dev.danya.tastebuds.core.platform.IssuedAnonSession
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal enum class InvalidTokenReason { MALFORMED, UNKNOWN_KEY, BAD_SIGNATURE, EXPIRED }

internal sealed interface AnonSessionVerification {
    data class Valid(
        val principal: AnonSessionPrincipal,
    ) : AnonSessionVerification

    data class Invalid(
        val reason: InvalidTokenReason,
    ) : AnonSessionVerification
}

/**
 * Anonymous session tokens, format `tba1.<kid>.<payload>.<mac>` (docs/security/ANON_SESSIONS.md).
 * Stateless: [verify] needs no I/O.
 */
internal class AnonSessionTokens(
    keys: List<Key>,
    private val ttl: Duration,
    private val clock: Clock = Clock.systemUTC(),
) : AnonSessionIssuer {
    /** [secret] is raw key material, at least 32 bytes. */
    class Key(
        val id: String,
        secret: ByteArray,
    ) {
        internal val spec = SecretKeySpec(secret.copyOf(), HMAC)

        init {
            require(KEY_ID.matches(id)) { "key id must match [a-z0-9]{1,16}" }
            require(secret.size >= MIN_SECRET_BYTES) { "key secret must have at least $MIN_SECRET_BYTES bytes" }
        }

        override fun toString(): String = "Key(id=$id)"
    }

    private val keysById: Map<String, Key>
    private val signingKey: Key

    init {
        require(keys.isNotEmpty()) { "at least one signing key is required" }
        keysById = keys.associateBy { it.id }
        require(keysById.size == keys.size) { "key ids must be unique" }
        signingKey = keys.first()
    }

    override fun issue(): IssuedAnonSession {
        // 122 random bits from SecureRandom; the id is internal, so no UUIDv7.
        val sessionId = UUID.randomUUID()
        val expiresAt = Instant.ofEpochSecond(clock.instant().epochSecond + ttl.seconds)
        val payload =
            ByteBuffer
                .allocate(PAYLOAD_BYTES)
                .putLong(sessionId.mostSignificantBits)
                .putLong(sessionId.leastSignificantBits)
                .putLong(expiresAt.epochSecond)
                .array()
        val signed = "$PREFIX.${signingKey.id}.${ENCODER.encodeToString(payload)}"
        return IssuedAnonSession(AnonSessionPrincipal(sessionId, expiresAt), "$signed.${mac(signingKey, signed)}")
    }

    fun verify(token: String): AnonSessionVerification {
        val match = if (token.length > MAX_TOKEN_LENGTH) null else FORMAT.matchEntire(token)
        if (match == null) return invalid(InvalidTokenReason.MALFORMED)
        val (kid, payload, signature) = match.destructured
        val key = keysById[kid]
        return when {
            key == null -> invalid(InvalidTokenReason.UNKNOWN_KEY)
            !signatureMatches(key, "$PREFIX.$kid.$payload", signature) -> invalid(InvalidTokenReason.BAD_SIGNATURE)
            else -> checkExpiry(decode(payload))
        }
    }

    private fun checkExpiry(decoded: DecodedPayload): AnonSessionVerification {
        // Instant.ofEpochSecond throws outside this range; only a key holder can sign such a token.
        if (decoded.expiresAtSeconds !in Instant.MIN.epochSecond..Instant.MAX.epochSecond) {
            return invalid(InvalidTokenReason.MALFORMED)
        }
        val principal = AnonSessionPrincipal(decoded.sessionId, Instant.ofEpochSecond(decoded.expiresAtSeconds))
        return if (clock.instant().isBefore(principal.expiresAt)) valid(principal) else invalid(InvalidTokenReason.EXPIRED)
    }

    private fun valid(principal: AnonSessionPrincipal): AnonSessionVerification = AnonSessionVerification.Valid(principal)

    // Constant time; comparing the encoded strings also rejects non-canonical Base64.
    private fun signatureMatches(
        key: Key,
        signed: String,
        signature: String,
    ): Boolean =
        MessageDigest.isEqual(
            mac(key, signed).toByteArray(StandardCharsets.US_ASCII),
            signature.toByteArray(StandardCharsets.US_ASCII),
        )

    private class DecodedPayload(
        val sessionId: UUID,
        val expiresAtSeconds: Long,
    )

    private fun decode(payload: String): DecodedPayload {
        val buffer = ByteBuffer.wrap(Base64.getUrlDecoder().decode(payload))
        return DecodedPayload(UUID(buffer.getLong(), buffer.getLong()), buffer.getLong())
    }

    private fun invalid(reason: InvalidTokenReason) = AnonSessionVerification.Invalid(reason)

    private fun mac(
        key: Key,
        text: String,
    ): String {
        // A new Mac per call: Mac is not thread-safe, and a ThreadLocal wastes memory with virtual threads.
        val mac = Mac.getInstance(HMAC).apply { init(key.spec) }
        return ENCODER.encodeToString(mac.doFinal(text.toByteArray(StandardCharsets.US_ASCII)))
    }

    companion object {
        const val PREFIX = "tba1"
        const val MAX_TOKEN_LENGTH = 128

        private const val HMAC = "HmacSHA256"
        private const val MIN_SECRET_BYTES = 32
        private const val PAYLOAD_BYTES = 24
        private val KEY_ID = Regex("^[a-z0-9]{1,16}$")
        private val FORMAT = Regex("^tba1\\.([a-z0-9]{1,16})\\.([A-Za-z0-9_-]{32})\\.([A-Za-z0-9_-]{43})$")
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val log = LoggerFactory.getLogger(AnonSessionTokens::class.java)

        fun fromProperties(
            properties: AnonSessionProperties,
            clock: Clock = Clock.systemUTC(),
        ): AnonSessionTokens {
            val keys =
                when {
                    properties.signingKeys.isNotEmpty() ->
                        properties.signingKeys.map { Key(it.id, Base64.getDecoder().decode(it.secret)) }
                    properties.ephemeralSigningKey -> {
                        log.warn(
                            "Anonymous sessions use an ephemeral signing key: tokens stop working on restart and are not " +
                                "shared between replicas. Local development and tests only.",
                        )
                        listOf(Key("ephemeral", ByteArray(MIN_SECRET_BYTES).also { SecureRandom().nextBytes(it) }))
                    }
                    else -> throw IllegalStateException(
                        "tastebuds.anon-session.signing-keys must contain at least one key " +
                            "(set tastebuds.anon-session.ephemeral-signing-key=true for local development only)",
                    )
                }
            return AnonSessionTokens(keys, properties.ttl, clock)
        }
    }
}
