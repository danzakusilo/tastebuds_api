package dev.danya.tastebuds.core.platform.ratelimit

import dev.danya.tastebuds.core.RedisTestcontainersConfiguration
import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.BucketExceptions
import io.lettuce.core.RedisClient
import io.lettuce.core.resource.ClientResources
import io.lettuce.core.resource.DefaultClientResources
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RedisRateLimiterContentionTest {
    private val properties = RateLimitProperties()

    private fun details() =
        object : DataRedisConnectionDetails {
            override fun getStandalone() =
                DataRedisConnectionDetails.Standalone.of(
                    RedisTestcontainersConfiguration.redis.host,
                    RedisTestcontainersConfiguration.redis.getMappedPort(RedisTestcontainersConfiguration.REDIS_PORT),
                )
        }

    private fun newClient(): RedisClient = RateLimitRedis.client(resources, details(), properties.timeout)

    private fun limiter(client: RedisClient = newClient()): RedisRateLimiter {
        val connection = LazyConnection({ RateLimitRedis.proxyManager(client, properties.timeout) }, Duration.ofSeconds(5))
        return RedisRateLimiter(connection::getOrNull, properties)
    }

    private fun ipKey() = ClientKey("test:" + UUID.randomUUID(), ClientKeyType.IP4)

    private fun sessionKey() = ClientKey("as:" + UUID.randomUUID(), ClientKeyType.SESSION)

    /** Warms the connection and the script, so the burst measures contention and not start-up. */
    private fun warm(limiter: RedisRateLimiter) {
        assertIs<RateLimitDecision.Allowed>(limiter.tryConsume(ipKey(), 1))
    }

    /** Runs every task on its own virtual thread, released together; returns the results in task order. */
    private fun <T> burst(tasks: List<() -> T>): List<T> {
        val start = CountDownLatch(1)
        val results = ConcurrentLinkedQueue<Pair<Int, T>>()
        val threads =
            tasks.mapIndexed { index, task ->
                Thread.ofVirtual().start {
                    start.await()
                    results.add(index to task())
                }
            }
        start.countDown()
        threads.forEach { thread ->
            thread.join(Duration.ofSeconds(30))
            assertTrue(!thread.isAlive, "a parallel caller did not finish within 30 s")
        }
        return results.sortedBy { it.first }.map { it.second }
    }

    private fun tally(decisions: List<RateLimitDecision>): String = decisions.groupingBy { it.javaClass.simpleName }.eachCount().toString()

    private fun assertNeverMoreThanTheBucketHolds(key: ClientKey) {
        val limiter = limiter()
        warm(limiter)
        val decisions = burst(List(PARALLEL) { { limiter.tryConsume(key, 1) } })
        val message = "decisions: ${tally(decisions)}"
        assertEquals(0, decisions.count { it is RateLimitDecision.Unavailable }, message)
        val allowed = decisions.count { it is RateLimitDecision.Allowed }
        assertTrue(allowed in 1..120, message)
        assertTrue(decisions.all { it is RateLimitDecision.Allowed || it is RateLimitDecision.Rejected }, message)
    }

    @Test
    fun `a thousand parallel requests never take more than the IP bucket holds`() {
        assertNeverMoreThanTheBucketHolds(ipKey())
    }

    @Test
    fun `a thousand parallel requests never take more than the session bucket holds`() {
        assertNeverMoreThanTheBucketHolds(sessionKey())
    }

    @Test
    fun `sixteen parallel requests on one key are all allowed`() {
        val limiter = limiter()
        warm(limiter)
        val key = ipKey()
        val decisions = burst(List(16) { { limiter.tryConsume(key, 1) } })
        assertTrue(decisions.all { it is RateLimitDecision.Allowed }, "decisions: ${tally(decisions)}")
    }

    @Test
    fun `a flood on one key leaves other keys alone`() {
        val limiter = limiter()
        warm(limiter)
        val flooded = ipKey()
        val others = List(50) { ipKey() }
        val tasks: List<() -> RateLimitDecision> =
            List(PARALLEL) { { limiter.tryConsume(flooded, 1) } } + others.map { other -> { limiter.tryConsume(other, 1) } }
        val decisions = burst(tasks).takeLast(others.size)
        assertTrue(decisions.all { it == RateLimitDecision.Allowed(119) }, "other keys: ${tally(decisions)}")
    }

    @Test
    fun `compare-and-swap gives up after 32 lost attempts with Bucket4j's retry error`() {
        val manager = RateLimitRedis.proxyManager(newClient(), Duration.ofMillis(250))
        val configuration =
            BucketConfiguration
                .builder()
                .addLimit { it.capacity(120).refillGreedy(120, Duration.ofMinutes(1)) }
                .build()
        manager.builder().build("rl:contention-test:warm:" + UUID.randomUUID()) { configuration }.tryConsume(1)

        // Whether one burst produces the error depends on how fast the client side runs (32 attempts must end before
        // the 250 ms deadline; a cold JVM with code not yet JIT-compiled is slow), so a burst may miss and a few fresh
        // buckets are tried before the test gives up.
        val tallies = mutableListOf<Map<String, Int>>()
        var retryErrors = emptyList<Throwable>()
        for (attempt in 1..MAX_BURSTS) {
            val bucketKey = "rl:contention-test:" + UUID.randomUUID()
            val outcomes: List<Throwable?> =
                burst(
                    List(100) {
                        {
                            try {
                                manager.builder().build(bucketKey) { configuration }.tryConsume(1)
                                null
                            } catch (
                                @Suppress("TooGenericExceptionCaught") e: Exception,
                            ) {
                                e
                            }
                        }
                    },
                )
            tallies += outcomes.groupingBy { it?.javaClass?.name ?: "ok" }.eachCount()
            retryErrors =
                outcomes.filterNotNull().filter {
                    it.javaClass == BucketExceptions.BucketExecutionException::class.java && it.cause == null
                }
            if (retryErrors.isNotEmpty()) break
        }
        assertTrue(retryErrors.isNotEmpty(), "no retry error in any burst; outcomes per burst: $tallies")
        assertTrue(
            retryErrors.all { it.message == "CAS operation failed after 32 retry attempts" },
            "messages: ${retryErrors.map { it.message }.toSet()}",
        )
    }

    companion object {
        private const val PARALLEL = 1000
        private const val MAX_BURSTS = 10
        private lateinit var resources: ClientResources

        @JvmStatic
        @BeforeAll
        fun startResources() {
            resources = DefaultClientResources.create()
        }

        @JvmStatic
        @AfterAll
        fun stopResources() {
            resources.shutdown().get()
        }
    }
}
