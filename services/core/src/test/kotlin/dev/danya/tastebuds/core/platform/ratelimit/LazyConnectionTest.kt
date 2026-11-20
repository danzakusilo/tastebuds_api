package dev.danya.tastebuds.core.platform.ratelimit

import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LazyConnectionTest {
    private var now = 1_000L
    private val backoff = Duration.ofSeconds(5)

    @Test
    fun `a successful connection is created once and reused`() {
        val calls = AtomicInteger()
        val connection = LazyConnection({ "c${calls.incrementAndGet()}" }, backoff) { now }
        assertEquals("c1", connection.getOrNull())
        assertEquals("c1", connection.getOrNull())
        assertEquals(1, calls.get())
    }

    @Test
    fun `a failed attempt rethrows, then returns null without retrying until the backoff has passed`() {
        val calls = AtomicInteger()
        val connection =
            LazyConnection<String>({
                calls.incrementAndGet()
                error("down")
            }, backoff) { now }
        assertFailsWith<IllegalStateException> { connection.getOrNull() }
        assertNull(connection.getOrNull())
        now += backoff.toNanos() - 1
        assertNull(connection.getOrNull())
        assertEquals(1, calls.get())
    }

    @Test
    fun `after the backoff a new attempt is made`() {
        val calls = AtomicInteger()
        val connection =
            LazyConnection(
                {
                    if (calls.incrementAndGet() == 1) error("down")
                    "up"
                },
                backoff,
            ) { now }
        assertFailsWith<IllegalStateException> { connection.getOrNull() }
        now += backoff.toNanos()
        assertEquals("up", connection.getOrNull())
        assertEquals(2, calls.get())
    }

    @Test
    fun `callers do not wait while another thread connects`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val connection =
            LazyConnection(
                {
                    entered.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    "up"
                },
                backoff,
            ) { now }
        val worker = thread { connection.getOrNull() }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        assertNull(connection.getOrNull())
        release.countDown()
        worker.join()
        assertEquals("up", connection.getOrNull())
    }
}
