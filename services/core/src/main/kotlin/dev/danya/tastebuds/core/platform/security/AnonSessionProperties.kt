package dev.danya.tastebuds.core.platform.security

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import java.util.Base64

@ConfigurationProperties("tastebuds.anon-session")
data class AnonSessionProperties(
    /** Lifetime of a session from its creation. There is no renewal. */
    val ttl: Duration = Duration.ofDays(DEFAULT_TTL_DAYS),
    /** HMAC-SHA256 keys. Index 0 signs new tokens; every listed key verifies (rotation). */
    val signingKeys: List<SigningKey> = emptyList(),
    /** Use a random key per start when [signingKeys] is empty. Local development and tests only. */
    val ephemeralSigningKey: Boolean = false,
) {
    data class SigningKey(
        val id: String,
        /** Standard Base64 (with padding, one line) of at least 32 random bytes. */
        val secret: String,
    ) {
        override fun toString(): String = "SigningKey(id=$id, secret=[REDACTED])"
    }

    init {
        require(ttl >= MIN_TTL && ttl <= MAX_TTL) { "tastebuds.anon-session.ttl must be between 1h and 90d" }
        val seen = HashSet<String>()
        signingKeys.forEachIndexed { index, key ->
            val name = "tastebuds.anon-session.signing-keys[$index]"
            require(KEY_ID.matches(key.id)) { "$name.id must match [a-z0-9]{1,16}" }
            require(seen.add(key.id)) { "$name.id must be unique" }
            require(decodes(key.secret)) { "$name.secret must be Base64 of at least 32 bytes" }
        }
    }

    private companion object {
        const val DEFAULT_TTL_DAYS = 30L
        const val MIN_SECRET_BYTES = 32
        val MIN_TTL: Duration = Duration.ofHours(1)
        val MAX_TTL: Duration = Duration.ofDays(90)
        val KEY_ID = Regex("^[a-z0-9]{1,16}$")

        fun decodes(secret: String): Boolean =
            try {
                Base64.getDecoder().decode(secret).size >= MIN_SECRET_BYTES
            } catch (_: IllegalArgumentException) {
                false
            }
    }
}
