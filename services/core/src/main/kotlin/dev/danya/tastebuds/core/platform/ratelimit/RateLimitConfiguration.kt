package dev.danya.tastebuds.core.platform.ratelimit

import dev.danya.tastebuds.core.platform.ConditionalOnRole
import dev.danya.tastebuds.core.platform.Role
import dev.danya.tastebuds.core.platform.security.AnonSessionTokens
import io.lettuce.core.RedisClient
import io.lettuce.core.resource.ClientResources
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.web.filter.CorsFilter

@Configuration(proxyBeanMethods = false)
@ConditionalOnRole(Role.API)
@ConditionalOnBooleanProperty("tastebuds.rate-limit.enabled", matchIfMissing = true)
@EnableConfigurationProperties(RateLimitProperties::class)
internal class RateLimitConfiguration {
    @Bean(destroyMethod = "shutdown")
    fun rateLimitRedisClient(
        resources: ClientResources,
        details: DataRedisConnectionDetails,
        properties: RateLimitProperties,
    ): RedisClient = RateLimitRedis.client(resources, details, properties.timeout)

    @Bean
    fun rateLimiter(
        rateLimitRedisClient: RedisClient,
        properties: RateLimitProperties,
    ): RateLimiter {
        val connection =
            LazyConnection(
                connect = { RateLimitRedis.proxyManager(rateLimitRedisClient, properties.timeout) },
                backoff = properties.reconnectBackoff,
            )
        return RedisRateLimiter(connection::getOrNull, properties)
    }

    @Bean
    fun clientKeyResolver(): ClientKeyResolver = IpClientKeyResolver()

    @Bean
    fun rateLimitSecurityCustomizer(
        keys: ClientKeyResolver,
        sessions: ObjectProvider<AnonSessionTokens>,
        limiter: RateLimiter,
        properties: RateLimitProperties,
    ): Customizer<HttpSecurity> {
        val resolvers = listOfNotNull(sessions.ifAvailable?.let { AnonSessionClientKeyResolver(it) }, keys)
        val filter = RateLimitFilter(resolvers, RequestCostResolver(properties), limiter)
        // CorsFilter = org.springframework.web.filter.CorsFilter
        return Customizer { http -> http.addFilterAfter(filter, CorsFilter::class.java) }
    }
}
