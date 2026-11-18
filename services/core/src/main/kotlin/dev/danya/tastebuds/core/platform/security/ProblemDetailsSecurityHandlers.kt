package dev.danya.tastebuds.core.platform.security

import dev.danya.tastebuds.core.platform.web.ProblemResponses
import dev.danya.tastebuds.observability.WideEvent
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler

/** 401 as problem+json. The exception message is deliberately neither returned nor recorded. */
internal class ProblemDetailsAuthenticationEntryPoint : AuthenticationEntryPoint {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        WideEvent.current()?.put("auth.failure", "unauthenticated")
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        ProblemResponses.write(request, response, HttpStatus.UNAUTHORIZED, "Unauthorized", "Authentication is required.")
    }
}

/** 403 as problem+json. The exception message is deliberately neither returned nor recorded. */
internal class ProblemDetailsAccessDeniedHandler : AccessDeniedHandler {
    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        WideEvent.current()?.put("auth.failure", "access_denied")
        ProblemResponses.write(request, response, HttpStatus.FORBIDDEN, "Forbidden", "Access is denied.")
    }
}
