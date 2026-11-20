package dev.danya.tastebuds.core.platform.security

import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

/** Key material and token minting for tests. Never a key literal: gitleaks scans the repo. */
internal object AnonSessionTestKeys {
    /** 32 bytes from `SecureRandom`. */
    fun randomSecret(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    /** Standard Base64, the form `tastebuds.anon-session.signing-keys[n].secret` expects. */
    fun base64(secret: ByteArray): String = Base64.getEncoder().encodeToString(secret)

    /** Tokens signed with [secret] under [id], expiring [ttl] after [now] (`Clock.fixed(now, UTC)`). */
    fun tokens(
        id: String,
        secret: ByteArray,
        ttl: Duration = Duration.ofDays(30),
        now: Instant = Instant.now(),
    ): AnonSessionTokens = AnonSessionTokens(listOf(AnonSessionTokens.Key(id, secret)), ttl, Clock.fixed(now, ZoneOffset.UTC))
}
