package dev.danya.tastebuds.core.platform

import java.time.Instant
import java.util.UUID

/** The principal of a request authenticated with an anonymous session token (docs/PLAN.md §4 `AnonSession`). */
data class AnonSessionPrincipal(
    /** Internal identity. Never shown to other users; never in wide events, logs or responses. */
    val sessionId: UUID,
    /** Whole seconds; the token stops working at this instant. */
    val expiresAt: Instant,
) {
    /** Never the session id: Spring Security logs principals, and `getName()` would fall back to this. */
    override fun toString(): String = "AnonSessionPrincipal(sessionId=[REDACTED], expiresAt=$expiresAt)"
}

/** A newly created anonymous session and its bearer token. */
class IssuedAnonSession(
    val principal: AnonSessionPrincipal,
    val token: String,
) {
    override fun toString(): String = "IssuedAnonSession(expiresAt=${principal.expiresAt}, token=[REDACTED])"
}

/** Creates anonymous sessions. A bean only in the `api` role. */
fun interface AnonSessionIssuer {
    fun issue(): IssuedAnonSession
}
