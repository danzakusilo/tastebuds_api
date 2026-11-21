package dev.danya.tastebuds.core.platform.ratelimit

import java.util.concurrent.ConcurrentHashMap

/**
 * Caps the bucket operations that run at once for one key in this JVM. Calls beyond the cap are turned away at once,
 * without touching Redis, so one client's parallel requests can neither spin in compare-and-swap retries nor queue
 * on the shared connection. Keys are bucket keys (client identity): never log them.
 */
internal class KeyInFlightLimit(
    private val maxPerKey: Int,
) {
    private val inFlight = ConcurrentHashMap<String, Int>()

    init {
        require(maxPerKey >= 1) { "maxPerKey must be at least 1" }
    }

    /** Runs [block] if fewer than [maxPerKey] calls for [key] are running; otherwise returns null at once. */
    fun <T : Any> tryRun(
        key: String,
        block: () -> T,
    ): T? {
        var admitted = false
        inFlight.compute(key) { _, count ->
            val current = count ?: 0
            if (current < maxPerKey) {
                admitted = true
                current + 1
            } else {
                current
            }
        }
        if (!admitted) return null
        try {
            return block()
        } finally {
            inFlight.compute(key) { _, count -> if (count == null || count <= 1) null else count - 1 }
        }
    }

    /** Number of keys with calls in flight; for tests. */
    fun trackedKeys(): Int = inFlight.size
}
