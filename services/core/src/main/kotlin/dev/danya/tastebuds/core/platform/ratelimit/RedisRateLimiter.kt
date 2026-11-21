package dev.danya.tastebuds.core.platform.ratelimit

import io.github.bucket4j.BucketConfiguration
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

internal sealed interface RateLimitDecision {
    data class Allowed(
        val remaining: Long,
    ) : RateLimitDecision

    data class Rejected(
        val remaining: Long,
        val retryAfterSeconds: Long,
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

    override fun tryConsume(
        key: ClientKey,
        cost: Long,
    ): RateLimitDecision =
        try {
            val manager = proxyManager() ?: return RateLimitDecision.Unavailable
            val isSession = key.type == ClientKeyType.SESSION
            val configuration = if (isSession) sessionConfiguration else ipConfiguration
            val prefix = if (isSession) sessionPrefix else ipPrefix
            val probe = manager.builder().build(prefix + key.value) { configuration }.tryConsumeAndReturnRemaining(cost)
            if (probe.isConsumed) {
                RateLimitDecision.Allowed(probe.remainingTokens)
            } else {
                RateLimitDecision.Rejected(probe.remainingTokens, retryAfterSeconds(probe.nanosToWaitForRefill))
            }
        } catch (
            // Fail open on any store error: rate limiting must never take the API down.
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            warnThrottled(e)
            RateLimitDecision.Unavailable
        }

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
