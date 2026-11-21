package dev.danya.tastebuds.core.platform.ratelimit

import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyInFlightLimitTest {
    @Test
    fun `admits calls up to the limit for one key and turns the next one away at once`() {
        val limit = KeyInFlightLimit(2)
        val inside = CountDownLatch(2)
        val release = CountDownLatch(1)
        val holders =
            List(2) {
                Thread.ofVirtual().start {
                    limit.tryRun("k") {
                        inside.countDown()
                        release.await()
                        "done"
                    }
                }
            }
        assertTrue(inside.await(10, TimeUnit.SECONDS))

        var ran = false
        val third =
            limit.tryRun("k") {
                ran = true
                "third"
            }
        assertNull(third)
        assertFalse(ran)

        release.countDown()
        holders.forEach { it.join(Duration.ofSeconds(10)) }
        assertEquals("again", limit.tryRun("k") { "again" })
    }

    @Test
    fun `each key has its own limit`() {
        val limit = KeyInFlightLimit(1)
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder =
            Thread.ofVirtual().start {
                limit.tryRun("a") {
                    inside.countDown()
                    release.await()
                    "done"
                }
            }
        assertTrue(inside.await(10, TimeUnit.SECONDS))

        assertNull(limit.tryRun("a") { "second" })
        assertEquals("other", limit.tryRun("b") { "other" })

        release.countDown()
        holder.join(Duration.ofSeconds(10))
    }

    @Test
    fun `a slot is freed when the call returns or throws`() {
        val limit = KeyInFlightLimit(1)
        assertEquals("ok", limit.tryRun("k") { "ok" })
        assertEquals(0, limit.trackedKeys())

        val thrown = assertFailsWith<IllegalStateException> { limit.tryRun("k") { throw IllegalStateException("boom") } }
        assertEquals("boom", thrown.message)
        assertEquals(0, limit.trackedKeys())
        assertEquals("again", limit.tryRun("k") { "again" })
    }

    @Test
    fun `never more than the limit run at once per key, and nothing stays behind`() {
        val maxPerKey = 4
        val limit = KeyInFlightLimit(maxPerKey)
        val keys = List(10) { "key-$it" }
        val running = keys.associateWith { AtomicInteger() }
        val highest = ConcurrentHashMap<String, Int>()
        val start = CountDownLatch(1)
        val threads =
            List(1000) { index ->
                val key = keys[index % keys.size]
                Thread.ofVirtual().start {
                    start.await()
                    limit.tryRun(key) {
                        val now = running.getValue(key).incrementAndGet()
                        highest.merge(key, now, ::maxOf)
                        Thread.yield()
                        running.getValue(key).decrementAndGet()
                        now
                    }
                }
            }
        start.countDown()
        threads.forEach {
            it.join(Duration.ofSeconds(30))
            assertFalse(it.isAlive)
        }

        assertTrue(highest.values.all { it <= maxPerKey }, "highest per key: ${highest.values}")
        assertEquals(0, limit.trackedKeys())
    }

    @Test
    fun `a limit below one is refused`() {
        val thrown = assertFailsWith<IllegalArgumentException> { KeyInFlightLimit(0) }
        assertEquals("maxPerKey must be at least 1", thrown.message)
    }
}
