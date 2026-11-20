package dev.danya.tastebuds.core.platform.ratelimit

import java.time.Duration
import java.util.concurrent.locks.ReentrantLock

/**
 * One lazily created connection. It is created on a caller's thread, by one thread at a time, and at most once per
 * [backoff] after a failure. Callers that would have to wait get null instead (the limiter then fails open).
 */
internal class LazyConnection<T : Any>(
    private val connect: () -> T,
    private val backoff: Duration,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    @Volatile
    private var value: T? = null

    private val lock = ReentrantLock()
    private var lastFailureNanos: Long? = null

    fun getOrNull(): T? = value ?: connectIfFree()

    private fun connectIfFree(): T? {
        if (!lock.tryLock()) return null
        try {
            return value ?: connectUnlessBackingOff()
        } finally {
            lock.unlock()
        }
    }

    private fun connectUnlessBackingOff(): T? {
        val failedAt = lastFailureNanos
        if (failedAt != null && nanoTime() - failedAt < backoff.toNanos()) return null
        return try {
            connect().also {
                value = it
                lastFailureNanos = null
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            lastFailureNanos = nanoTime()
            throw e
        }
    }
}
