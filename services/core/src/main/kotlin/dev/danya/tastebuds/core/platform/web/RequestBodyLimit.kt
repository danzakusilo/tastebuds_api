package dev.danya.tastebuds.core.platform.web

import dev.danya.tastebuds.core.platform.TastebudsProperties
import dev.danya.tastebuds.observability.WideEvent
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.web.filter.OncePerRequestFilter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.Charset

/** Thrown while reading a request body that grows past the configured cap. Mapped to 413 by `ProblemDetailsAdvice`. */
class RequestBodyTooLargeException(
    val limitBytes: Long,
) : RuntimeException("Request body exceeds $limitBytes bytes")

internal const val BODY_TOO_LARGE_TITLE = "Content Too Large"

internal fun bodyTooLargeDetail(limitBytes: Long) = "Request body exceeds $limitBytes bytes."

/** Rejects requests whose body exceeds [maxBytes]: by declared length up front, otherwise while the body is read. */
internal class RequestBodyLimitFilter(
    private val maxBytes: Long,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.contentLengthLong > maxBytes) {
            reject(request, response)
            return
        }
        try {
            filterChain.doFilter(LimitedRequest(request, maxBytes), response)
        } catch (
            @Suppress("TooGenericExceptionCaught") error: Exception,
        ) {
            // Filters between here and MVC (e.g. form content parsing) can read the body before the advice could map this.
            if (response.isCommitted || !causedByBodyLimit(error)) throw error
            response.resetBuffer()
            reject(request, response)
        }
    }

    private fun causedByBodyLimit(error: Throwable): Boolean =
        generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }
            .take(MAX_CAUSE_DEPTH)
            .any { it is RequestBodyTooLargeException }

    private fun reject(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        WideEvent.current()?.put("http.request.body_too_large", true)
        ProblemResponses.write(request, response, HttpStatus.CONTENT_TOO_LARGE, BODY_TOO_LARGE_TITLE, bodyTooLargeDetail(maxBytes))
    }

    private companion object {
        const val MAX_CAUSE_DEPTH = 16
    }

    private class LimitedRequest(
        request: HttpServletRequest,
        private val maxBytes: Long,
    ) : HttpServletRequestWrapper(request) {
        private val stream: ServletInputStream by lazy { CountingInputStream(super.getInputStream(), maxBytes) }

        override fun getInputStream(): ServletInputStream = stream

        private val bufferedReader: BufferedReader by lazy {
            val charset = characterEncoding?.let { Charset.forName(it) } ?: Charsets.UTF_8
            BufferedReader(InputStreamReader(inputStream, charset))
        }

        override fun getReader(): BufferedReader = bufferedReader
    }

    private class CountingInputStream(
        private val delegate: ServletInputStream,
        private val maxBytes: Long,
    ) : ServletInputStream() {
        private var count = 0L

        override fun read(): Int {
            val b = delegate.read()
            if (b >= 0) add(1)
            return b
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            val n = delegate.read(b, off, len)
            if (n > 0) add(n.toLong())
            return n
        }

        private fun add(n: Long) {
            count += n
            if (count > maxBytes) throw RequestBodyTooLargeException(maxBytes)
        }

        override fun isFinished(): Boolean = delegate.isFinished

        override fun isReady(): Boolean = delegate.isReady

        override fun setReadListener(readListener: ReadListener) = delegate.setReadListener(readListener)
    }
}

@Configuration(proxyBeanMethods = false)
internal class RequestBodyLimitConfiguration {
    @Bean
    fun requestBodyLimitFilter(properties: TastebudsProperties): FilterRegistrationBean<RequestBodyLimitFilter> =
        FilterRegistrationBean(RequestBodyLimitFilter(properties.web.maxRequestBodySize.toBytes())).apply {
            // Directly after WideEventFilter (+2): a rejection gets a wide event; before Spring Security: cheap for anonymous callers.
            order = Ordered.HIGHEST_PRECEDENCE + ORDER_OFFSET
        }

    private companion object {
        const val ORDER_OFFSET = 3
    }
}
