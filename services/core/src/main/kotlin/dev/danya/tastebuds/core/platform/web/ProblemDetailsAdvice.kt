package dev.danya.tastebuds.core.platform.web

import dev.danya.tastebuds.observability.WideEvent
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * Turns exceptions from Spring MVC request handling into RFC 9457 problem+json. Responses written outside MVC (filters,
 * Spring Security handlers) use [ProblemResponses]; the error responses that are not problem+json are listed under
 * "Known gaps" in SECURITY.md. Unexpected errors never reveal internals, only a trace ID that support can look up. The
 * wide event records the error identity; the stack trace is logged once, correlated by trace ID.
 */
@RestControllerAdvice
class ProblemDetailsAdvice : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(ProblemDetailsAdvice::class.java)

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(error: Exception): ProblemDetail {
        // Rethrowing the same exception makes MVC treat it as unhandled, so ExceptionTranslationFilter produces 401/403.
        if (error is AccessDeniedException || error is AuthenticationException) throw error
        bodyTooLarge(error)?.let { return bodyTooLargeProblem(it) }
        WideEvent.current()?.recordError(error)
        log.error("Unhandled exception", error)
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.").apply {
            title = "Internal Server Error"
            MDC.get(TRACE_ID_MDC_KEY)?.let { setProperty("traceId", it) }
        }
    }

    override fun handleHttpMessageNotReadable(
        ex: HttpMessageNotReadableException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val tooLarge = bodyTooLarge(ex) ?: return super.handleHttpMessageNotReadable(ex, headers, status, request)
        return handleExceptionInternal(ex, bodyTooLargeProblem(tooLarge), headers, HttpStatus.CONTENT_TOO_LARGE, request)
    }

    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        WideEvent.current()?.put(WideEvent.ERROR_CLASS, ex.javaClass.name)
        return super.handleExceptionInternal(ex, body, headers, statusCode, request)
    }

    private fun bodyTooLarge(ex: Throwable): RequestBodyTooLargeException? =
        generateSequence(ex) { it.cause?.takeIf { cause -> cause !== it } }
            .take(MAX_CAUSE_DEPTH)
            .filterIsInstance<RequestBodyTooLargeException>()
            .firstOrNull()

    private fun bodyTooLargeProblem(ex: RequestBodyTooLargeException): ProblemDetail {
        WideEvent.current()?.put("http.request.body_too_large", true)
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, bodyTooLargeDetail(ex.limitBytes)).apply {
            title = BODY_TOO_LARGE_TITLE
        }
    }

    private companion object {
        const val TRACE_ID_MDC_KEY = "traceId"
        const val MAX_CAUSE_DEPTH = 16
    }
}
