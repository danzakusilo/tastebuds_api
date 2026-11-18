package dev.danya.tastebuds.core.platform.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import org.springframework.web.filter.OncePerRequestFilter

/** Records what the servlet container reports for the last request, after Tomcat's RemoteIpValve ran. */
@TestConfiguration(proxyBeanMethods = false)
class RequestInfoCapture {
    data class Captured(
        val remoteAddr: String,
        val scheme: String,
        val secure: Boolean,
    )

    class CapturingFilter :
        OncePerRequestFilter(),
        Ordered {
        @Volatile
        private var captured: Captured? = null

        fun last(): Captured = checkNotNull(captured) { "no request captured yet" }

        fun reset() {
            captured = null
        }

        override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

        override fun doFilterInternal(
            request: HttpServletRequest,
            response: HttpServletResponse,
            filterChain: FilterChain,
        ) {
            captured = Captured(request.remoteAddr, request.scheme, request.isSecure)
            filterChain.doFilter(request, response)
        }
    }

    @Bean
    fun requestInfoCaptureFilter(): CapturingFilter = CapturingFilter()
}
