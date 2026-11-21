package dev.danya.tastebuds.core.platform.ratelimit

import dev.danya.tastebuds.core.platform.web.ProblemResponses
import dev.danya.tastebuds.observability.WideEvent
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.server.PathContainer
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.pattern.PathPatternParser

internal enum class RateLimitOutcome { ALLOWED, REJECTED, UNAVAILABLE }

/**
 * Draws the request's cost from each applicable bucket (session first, then IP).
 * Not a Spring bean: it lives in the security chain only.
 */
internal class RateLimitFilter(
    private val keys: List<ClientKeyResolver>,
    private val costs: RequestCostResolver,
    private val limiter: RateLimiter,
) : OncePerRequestFilter() {
    private val v1 = PathPatternParser.defaultInstance.parse("/v1/**")

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !v1.matches(PathContainer.parsePath(request.requestURI.removePrefix(request.contextPath)))

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val resolved = keys.mapNotNull { it.resolve(request) }
        val cost = costs.resolve(request)
        val event = WideEvent.current()
        event
            ?.put("rate_limit.rule", cost.rule)
            ?.put("rate_limit.cost", cost.tokens)
        resolved.firstOrNull { it.type != ClientKeyType.SESSION }?.let { event?.put("rate_limit.key_type", it.type) }
        var smallestRemaining: Long? = null
        for (key in resolved) {
            when (val decision = limiter.tryConsume(key, cost.tokens)) {
                is RateLimitDecision.Allowed -> smallestRemaining = minOf(smallestRemaining ?: decision.remaining, decision.remaining)
                is RateLimitDecision.Rejected -> {
                    reject(request, response, key, decision)
                    return
                }
                RateLimitDecision.Unavailable -> {
                    event?.put("rate_limit.outcome", RateLimitOutcome.UNAVAILABLE)
                    filterChain.doFilter(request, response)
                    return
                }
            }
        }
        event?.put("rate_limit.outcome", RateLimitOutcome.ALLOWED)
        smallestRemaining?.let { event?.put("rate_limit.remaining", it) }
        filterChain.doFilter(request, response)
    }

    private fun reject(
        request: HttpServletRequest,
        response: HttpServletResponse,
        key: ClientKey,
        decision: RateLimitDecision.Rejected,
    ) {
        WideEvent
            .current()
            ?.put("rate_limit.outcome", RateLimitOutcome.REJECTED)
            ?.put("rate_limit.remaining", decision.remaining)
            ?.put("rate_limit.limited_by", if (key.type == ClientKeyType.SESSION) "session" else "ip")
        response.setHeader(HttpHeaders.RETRY_AFTER, decision.retryAfterSeconds.toString())
        ProblemResponses.write(request, response, HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", "Rate limit exceeded.")
    }
}
