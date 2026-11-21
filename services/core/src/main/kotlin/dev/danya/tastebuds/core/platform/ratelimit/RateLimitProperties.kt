package dev.danya.tastebuds.core.platform.ratelimit

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("tastebuds.rate-limit")
data class RateLimitProperties(
    /** Master switch. Off: no limiter beans and no Redis connection for rate limiting. */
    val enabled: Boolean = true,
    /** Bucket size in tokens: the largest burst one client can send. */
    val capacity: Long = DEFAULT_TOKENS,
    /** Tokens added back per [refillPeriod], greedily (spread evenly over the period). */
    val refillTokens: Long = DEFAULT_TOKENS,
    val refillPeriod: Duration = Duration.ofMinutes(1),
    /** Bucket of each anonymous session, checked before the client's IP bucket (task 007). */
    val session: SessionLimit = SessionLimit(),
    /** Tokens a `/v1` request costs when no rule matches. */
    val defaultCost: Long = 1,
    /** Redis connect and command timeout, and the deadline of one bucket operation. Slower means fail open. */
    val timeout: Duration = DEFAULT_TIMEOUT,
    /** Minimum time between two connect attempts while Redis is unreachable. Requests fail open meanwhile. */
    val reconnectBackoff: Duration = DEFAULT_BACKOFF,
    /** Cost rules; the first match wins. */
    val rules: List<Rule> = emptyList(),
) {
    data class Rule(
        /** Upper-case HTTP method; null matches every method. */
        val method: String? = null,
        /** Spring `PathPattern` under `/v1/`, matched against the decoded request path, e.g. `/v1/media/{id}`. */
        val pattern: String,
        val cost: Long,
    )

    data class SessionLimit(
        val capacity: Long = DEFAULT_TOKENS,
        val refillTokens: Long = DEFAULT_TOKENS,
        val refillPeriod: Duration = Duration.ofMinutes(1),
    )

    init {
        require(capacity in 1..MAX_TOKENS) { "tastebuds.rate-limit.capacity must be between 1 and $MAX_TOKENS" }
        require(refillTokens in 1..MAX_TOKENS) { "tastebuds.rate-limit.refill-tokens must be between 1 and $MAX_TOKENS" }
        require(refillPeriod >= Duration.ofSeconds(1)) { "tastebuds.rate-limit.refill-period must be at least 1s" }
        require(session.capacity in 1..MAX_TOKENS) { "tastebuds.rate-limit.session.capacity must be between 1 and $MAX_TOKENS" }
        require(session.refillTokens in 1..MAX_TOKENS) {
            "tastebuds.rate-limit.session.refill-tokens must be between 1 and $MAX_TOKENS"
        }
        require(session.refillPeriod >= Duration.ofSeconds(1)) { "tastebuds.rate-limit.session.refill-period must be at least 1s" }
        val maxCost = minOf(capacity, session.capacity)
        require(defaultCost in 1..maxCost) {
            "tastebuds.rate-limit.default-cost must be between 1 and the smaller of capacity and session.capacity"
        }
        require(!timeout.isZero && !timeout.isNegative && timeout <= MAX_TIMEOUT) {
            "tastebuds.rate-limit.timeout must be greater than 0 and at most 5s"
        }
        require(!reconnectBackoff.isZero && !reconnectBackoff.isNegative) {
            "tastebuds.rate-limit.reconnect-backoff must be greater than 0"
        }
        rules.forEachIndexed { index, rule ->
            val name = "tastebuds.rate-limit.rules[$index]"
            require(rule.cost in 1..maxCost) { "$name.cost must be between 1 and the smaller of capacity and session.capacity" }
            require(rule.pattern.startsWith("/v1/")) { "$name.pattern must start with /v1/" }
            require(rule.method == null || rule.method in METHODS) {
                "$name.method must be null or one of ${METHODS.joinToString(" ")}"
            }
        }
    }

    private companion object {
        const val DEFAULT_TOKENS = 120L
        const val MAX_TOKENS = 1_000_000L
        val DEFAULT_TIMEOUT: Duration = Duration.ofMillis(250)
        val DEFAULT_BACKOFF: Duration = Duration.ofSeconds(5)
        val MAX_TIMEOUT: Duration = Duration.ofSeconds(5)
        val METHODS = listOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
    }
}
