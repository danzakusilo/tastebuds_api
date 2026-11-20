package dev.danya.tastebuds.core.sessions

import dev.danya.tastebuds.core.platform.AnonSessionIssuer
import dev.danya.tastebuds.core.platform.ConditionalOnRole
import dev.danya.tastebuds.core.platform.Role
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/** Response of `POST /v1/sessions/anonymous`. */
data class AnonSessionResponse(
    val token: String,
    val tokenType: String,
    val expiresAt: Instant,
) {
    override fun toString(): String = "AnonSessionResponse(tokenType=$tokenType, expiresAt=$expiresAt, token=[REDACTED])"
}

/** Creates anonymous sessions for signed-out users. Public; sessions are signed tokens, nothing is stored. */
@RestController
@RequestMapping("/v1/sessions")
@ConditionalOnRole(Role.API)
class AnonSessionController(
    private val issuer: AnonSessionIssuer,
) {
    @PostMapping("/anonymous")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(): AnonSessionResponse {
        val issued = issuer.issue()
        return AnonSessionResponse(issued.token, "Bearer", issued.principal.expiresAt)
    }
}
