package dev.danya.tastebuds.core.platform.security

import dev.danya.tastebuds.core.platform.AnonSessionPrincipal
import dev.danya.tastebuds.core.platform.web.ProblemResponses
import dev.danya.tastebuds.observability.WideEvent
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

internal const val ANON_SESSION_AUTHORITY = "ANON_SESSION"

internal class AnonSessionAuthentication(
    private val principal: AnonSessionPrincipal,
) : AbstractAuthenticationToken(listOf(SimpleGrantedAuthority(ANON_SESSION_AUTHORITY))) {
    init {
        isAuthenticated = true
    }

    override fun getPrincipal(): AnonSessionPrincipal = principal

    // The token is not kept after verification.
    override fun getCredentials(): Any? = null

    /** Fixed and non-identifying: `getName()` feeds `request.remoteUser` and Spring Security logs. */
    override fun getName(): String = ANON_SESSION_NAME
}

internal const val ANON_SESSION_NAME = "anon-session"

/** Not a Spring bean: it lives in the security chain only (same reason as RateLimitFilter). */
internal class AnonSessionAuthenticationFilter(
    private val tokens: AnonSessionTokens,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val reason =
            when (val credential = BearerCredentials.from(request)) {
                BearerCredential.Absent -> {
                    filterChain.doFilter(request, response)
                    return
                }
                BearerCredential.Malformed -> InvalidTokenReason.MALFORMED
                is BearerCredential.Present ->
                    when (val result = tokens.verify(credential.token)) {
                        is AnonSessionVerification.Valid -> {
                            val strategy = SecurityContextHolder.getContextHolderStrategy()
                            val context = strategy.createEmptyContext()
                            context.authentication = AnonSessionAuthentication(result.principal)
                            strategy.context = context
                            WideEvent.current()?.put("principal.tier", "anon")
                            filterChain.doFilter(request, response)
                            return
                        }
                        is AnonSessionVerification.Invalid -> result.reason
                    }
            }
        reject(request, response, reason)
    }

    private fun reject(
        request: HttpServletRequest,
        response: HttpServletResponse,
        reason: InvalidTokenReason,
    ) {
        WideEvent
            .current()
            ?.put("auth.failure", "invalid_token")
            ?.put("auth.failure_reason", reason.name.lowercase())
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"")
        ProblemResponses.write(request, response, HttpStatus.UNAUTHORIZED, "Unauthorized", "The bearer token is invalid or expired.")
    }
}
