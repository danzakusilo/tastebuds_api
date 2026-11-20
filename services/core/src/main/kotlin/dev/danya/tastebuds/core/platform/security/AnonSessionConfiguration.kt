package dev.danya.tastebuds.core.platform.security

import dev.danya.tastebuds.core.platform.ConditionalOnRole
import dev.danya.tastebuds.core.platform.Role
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter

/** The `worker` role has no keys and no session beans (least privilege). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnRole(Role.API)
@EnableConfigurationProperties(AnonSessionProperties::class)
internal class AnonSessionConfiguration {
    @Bean
    fun anonSessionTokens(properties: AnonSessionProperties): AnonSessionTokens = AnonSessionTokens.fromProperties(properties)

    @Bean
    fun anonSessionSecurityCustomizer(tokens: AnonSessionTokens): Customizer<HttpSecurity> {
        val filter = AnonSessionAuthenticationFilter(tokens)
        return Customizer { http -> http.addFilterBefore(filter, AnonymousAuthenticationFilter::class.java) }
    }
}
