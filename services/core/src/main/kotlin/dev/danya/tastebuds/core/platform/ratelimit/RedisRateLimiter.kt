package dev.danya.tastebuds.core.platform.ratelimit

import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.BucketExceptions
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy
import io.github.bucket4j.distributed.proxy.ProxyManager
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce
import io.lettuce.core.ClientOptions
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.SocketOptions
import io.lettuce.core.TimeoutOptions
import io.lettuce.core.codec.ByteArrayCodec
import io.lettuce.core.codec.RedisCodec
import io.lettuce.core.codec.StringCodec
import io.lettuce.core.resource.ClientResources
import org.slf4j.LoggerFactory
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/** Bucket operations for one key that may run at once in this JVM; more get contention without a Redis call. */
internal const val MAX_IN_FLIGHT_PER_KEY = 16

/** Compare-and-swap attempts per bucket operation (Bucket4j `maxRetries` counts attempts); all lost → contention. */
internal const val MAX_CAS_ATTEMPTS = 32

/** Why a bucket said no (`rate_limit.reject_reason`). */
internal enum class RejectReason {
    /** The bucket had fewer tokens than the request costs. */
    TOKENS,

    /**
     * Too many parallel requests on one bucket: [MAX_IN_FLIGHT_PER_KEY] operations for the key already run in this
     * JVM, or all [MAX_CAS_ATTEMPTS] compare-and-swap attempts lost to other writers of the same key. Only the key's
     * own requests cause it, so it is a 429 and never a fail open.
     */
    CONTENTION,
}

internal sealed interface RateLimitDecision {
    data class Allowed(
        val remaining: Long,
    ) : RateLimitDecision

    /** [remaining] is null for [RejectReason.CONTENTION]: the bucket was not read or not changed. */
    data class Rejected(
        val remaining: Long?,
        val retryAfterSeconds: Long,
        val reason: RejectReason,
    ) : RateLimitDecision

    data object Unavailable : RateLimitDecision
}

internal fun interface RateLimiter {
    fun tryConsume(
        key: ClientKey,
        cost: Long,
    ): RateLimitDecision
}

internal class RedisRateLimiter(
    private val proxyManager: () -> ProxyManager<String>?,
    properties: RateLimitProperties,
    private val nanoTime: () -> Long = System::nanoTime,
) : RateLimiter {
    private val ipConfiguration = configuration(properties.capacity, properties.refillTokens, properties.refillPeriod)
    private val sessionConfiguration =
        configuration(properties.session.capacity, properties.session.refillTokens, properties.session.refillPeriod)
    private val ipPrefix = bucketKeyPrefix(properties)
    private val sessionPrefix = sessionBucketKeyPrefix(properties)
    private val lastWarn = AtomicLong(NEVER)
    private val inFlight = KeyInFlightLimit(MAX_IN_FLIGHT_PER_KEY)

    override fun tryConsume(
        key: ClientKey,
        cost: Long,
    ): RateLimitDecision =
        try {
            val manager = proxyManager() ?: return RateLimitDecision.Unavailable
            val isSession = key.type == ClientKeyType.SESSION
            val configuration = if (isSession) sessionConfiguration else ipConfiguration
            val bucketKey = (if (isSession) sessionPrefix else ipPrefix) + key.value
            val probe =
                inFlight.tryRun(bucketKey) {
                    manager.builder().build(bucketKey) { configuration }.tryConsumeAndReturnRemaining(cost)
                }
            when {
                probe == null -> CONTENDED
                probe.isConsumed -> RateLimitDecision.Allowed(probe.remainingTokens)
                else ->
                    RateLimitDecision.Rejected(
                        probe.remainingTokens,
                        retryAfterSeconds(probe.nanosToWaitForRefill),
                        RejectReason.TOKENS,
                    )
            }
        } catch (
            // Store errors (no connection, no answer in time) fail open: rate limiting must never take the API down.
            // Retry exhaustion (all compare-and-swap attempts lost to the key's own parallel requests) is a 429, never a fail open.
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            if (isRetryExhaustion(e)) {
                CONTENDED
            } else {
                warnThrottled(e)
                RateLimitDecision.Unavailable
            }
        }

    /**
     * Bucket4j's "CAS operation failed after N retry attempts" (`BucketExceptions.maxRetriesExceeded`): Redis
     * answered every attempt and another writer of the same key won each time. Its subclass
     * `io.github.bucket4j.TimeoutException` (no answer in time) and wrappers with a cause are store errors.
     */
    private fun isRetryExhaustion(e: Exception): Boolean =
        e.javaClass == BucketExceptions.BucketExecutionException::class.java && e.cause == null

    private fun configuration(
        capacity: Long,
        refillTokens: Long,
        refillPeriod: Duration,
    ): BucketConfiguration =
        BucketConfiguration
            .builder()
            .addLimit { it.capacity(capacity).refillGreedy(refillTokens, refillPeriod) }
            .build()

    private fun warnThrottled(e: Exception) {
        val now = nanoTime()
        val last = lastWarn.get()
        val due = last == NEVER || now - last >= WARN_INTERVAL_NANOS
        if (due && lastWarn.compareAndSet(last, now)) {
            log.warn("Rate limiting unavailable, failing open: {}", e.javaClass.name)
        }
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
        const val CONTENTION_RETRY_AFTER_SECONDS = 1L
        val CONTENDED = RateLimitDecision.Rejected(null, CONTENTION_RETRY_AFTER_SECONDS, RejectReason.CONTENTION)
        val log = LoggerFactory.getLogger(RedisRateLimiter::class.java)
        val WARN_INTERVAL_NANOS: Long = Duration.ofSeconds(60).toNanos()
    }
}

internal object RateLimitRedis {
    fun client(
        resources: ClientResources,
        details: DataRedisConnectionDetails,
        timeout: Duration,
    ): RedisClient {
        check(details.sentinel == null && details.cluster == null && details.masterReplica == null && details.sslBundle == null) {
            "Rate limiting supports a standalone Redis without TLS only (spring.data.redis.*)"
        }
        val standalone = checkNotNull(details.standalone) { "No standalone Redis configured (spring.data.redis.*)" }
        val uri =
            RedisURI
                .builder()
                .withHost(standalone.host)
                .withPort(standalone.port)
                .withDatabase(standalone.database)
                .withTimeout(timeout)
                .apply {
                    val password = details.password
                    if (!password.isNullOrEmpty()) {
                        val username = details.username
                        if (!username.isNullOrEmpty()) withAuthentication(username, password) else withPassword(password)
                    }
                }.build()
        val client = RedisClient.create(resources, uri)
        client.options =
            ClientOptions
                .builder()
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(timeout).build())
                .timeoutOptions(TimeoutOptions.enabled(timeout))
                .build()
        return client
    }

    fun proxyManager(
        client: RedisClient,
        timeout: Duration,
    ): ProxyManager<String> =
        Bucket4jLettuce
            .casBasedBuilder(client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE)))
            .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(1)))
            .requestTimeout(timeout)
            .maxRetries(MAX_CAS_ATTEMPTS)
            .build()
}

/** `rl:<capacity>-<refillTokens>-<refillPeriodMillis>:`, e.g. `rl:120-120-60000:`. */
internal fun bucketKeyPrefix(properties: RateLimitProperties): String =
    "rl:${properties.capacity}-${properties.refillTokens}-${properties.refillPeriod.toMillis()}:"

/** `rl:<capacity>-<refillTokens>-<refillPeriodMillis>:` of the session bucket; session keys add `as:<uuid>`. */
internal fun sessionBucketKeyPrefix(properties: RateLimitProperties): String =
    "rl:${properties.session.capacity}-${properties.session.refillTokens}-${properties.session.refillPeriod.toMillis()}:"

/** Whole seconds, rounded up, at least 1. */
internal fun retryAfterSeconds(nanosToWait: Long): Long = maxOf(1L, Math.ceilDiv(nanosToWait, NANOS_PER_SECOND))

private const val NANOS_PER_SECOND = 1_000_000_000L
