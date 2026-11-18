package dev.danya.tastebuds.core.platform.security

import dev.danya.tastebuds.core.platform.TastebudsProperties
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
import org.springframework.security.web.savedrequest.NullRequestCache
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * Baseline for a stateless JSON API: no sessions, no cookies, deny by default, strict response headers.
 * Bearer-token authentication (OAuth2 resource server) is added in Phase 1, see docs/PLAN.md section 8.3.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {
    @Bean
    fun apiSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            // CSRF protection targets ambient credentials (cookies). This API accepts none, only bearer tokens.
            csrf { disable() }
            httpBasic { disable() }
            formLogin { disable() }
            logout { disable() }
            requestCache { requestCache = NullRequestCache() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            cors { }
            authorizeHttpRequests {
                authorize(HttpMethod.GET, "/v1/meta", permitAll)
                // Probes and build info on the management port (never exposed through the ingress).
                authorize(EndpointRequest.to("health", "info"), permitAll)
                authorize("/error", permitAll)
                authorize(anyRequest, authenticated)
            }
            exceptionHandling {
                authenticationEntryPoint = ProblemDetailsAuthenticationEntryPoint()
                accessDeniedHandler = ProblemDetailsAccessDeniedHandler()
            }
            headers {
                contentSecurityPolicy { policyDirectives = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'" }
                frameOptions { deny = true }
                referrerPolicy { policy = ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER }
                httpStrictTransportSecurity {
                    includeSubDomains = true
                    preload = true
                    maxAgeInSeconds = HSTS_MAX_AGE_SECONDS
                }
            }
        }
        return http.build()
    }

    @Bean
    fun corsConfigurationSource(properties: TastebudsProperties): CorsConfigurationSource {
        val configuration =
            CorsConfiguration().apply {
                allowedOrigins = properties.cors.allowedOrigins
                allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE")
                allowedHeaders = listOf("Authorization", "Content-Type", "Idempotency-Key", "If-None-Match")
                exposedHeaders = listOf("ETag", "Location", "Retry-After")
                allowCredentials = false
                maxAge = CORS_MAX_AGE_SECONDS
            }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/v1/**", configuration) }
    }

    private companion object {
        const val HSTS_MAX_AGE_SECONDS = 31_536_000L
        const val CORS_MAX_AGE_SECONDS = 3_600L
    }
}
