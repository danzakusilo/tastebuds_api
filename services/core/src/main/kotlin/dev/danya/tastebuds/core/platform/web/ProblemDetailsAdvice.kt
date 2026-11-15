package dev.danya.tastebuds.core.platform.web

import dev.danya.tastebuds.observability.WideEvent
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * Every error leaves as RFC 9457 problem+json. Unexpected errors never reveal internals, only a trace ID that support
 * can look up. The wide event records the error identity; the stack trace is logged once, correlated by trace ID.
 */
@RestControllerAdvice
class ProblemDetailsAdvice : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(ProblemDetailsAdvice::class.java)

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(error: Exception): ProblemDetail {
        WideEvent.current()?.recordError(error)
        log.error("Unhandled exception", error)
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.").apply {
            title = "Internal Server Error"
            MDC.get(TRACE_ID_MDC_KEY)?.let { setProperty("traceId", it) }
        }
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

    private companion object {
        const val TRACE_ID_MDC_KEY = "traceId"
    }
}
