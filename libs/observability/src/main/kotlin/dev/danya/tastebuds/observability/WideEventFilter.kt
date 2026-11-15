package dev.danya.tastebuds.observability

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.server.PathContainer
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.util.pattern.PathPatternParser

/**
 * Opens a wide event for each HTTP request and emits it when the response is complete. Runs inside the HTTP
 * observation, so the trace and span IDs are in the MDC when the event is logged.
 */
class WideEventFilter(
    private val sink: WideEventSink,
    private val enrichers: List<WideEventEnricher>,
    excludePaths: List<String>,
) : OncePerRequestFilter() {
    private val excluded = excludePaths.map { PathPatternParser.defaultInstance.parse(it) }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = PathContainer.parsePath(request.requestURI.removePrefix(request.contextPath))
        return excluded.any { it.matches(path) }
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val event = WideEvent(KIND).put("http.request.method", request.method)
        enrichers.forEach { it.enrich(event) }
        val startNanos = System.nanoTime()
        var failure: Throwable? = null
        try {
            WideEvent.within(event) { chain.doFilter(request, response) }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            failure = e
            throw e
        } finally {
            complete(event, request, response, failure, startNanos)
        }
    }

    private fun complete(
        event: WideEvent,
        request: HttpServletRequest,
        response: HttpServletResponse,
        failure: Throwable?,
        startNanos: Long,
    ) {
        val status = if (failure != null) HttpServletResponse.SC_INTERNAL_SERVER_ERROR else response.status
        failure?.let(event::recordError)
        event
            .put("http.route", request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String ?: UNMATCHED)
            .put("http.response.status_code", status)
            .put(WideEvent.DURATION_MS, (System.nanoTime() - startNanos) / NANOS_PER_MILLI)
        if (event[WideEvent.OUTCOME] == null) event.put(WideEvent.OUTCOME, outcomeOf(status))
        sink.emit(event)
    }

    private fun outcomeOf(status: Int): Outcome =
        when {
            status >= HttpServletResponse.SC_INTERNAL_SERVER_ERROR -> Outcome.ERROR
            status >= HttpServletResponse.SC_BAD_REQUEST -> Outcome.CLIENT_ERROR
            else -> Outcome.SUCCESS
        }

    companion object {
        const val KIND = "http"
        const val UNMATCHED = "UNMATCHED"
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
