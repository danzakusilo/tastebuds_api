package dev.danya.tastebuds.core.platform.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import tools.jackson.databind.json.JsonMapper

internal object ProblemResponses {
    private val mapper = JsonMapper.builder().build()

    /** Writes an RFC 9457 body for responses produced outside Spring MVC (filters, Spring Security handlers). */
    fun write(
        request: HttpServletRequest,
        response: HttpServletResponse,
        status: HttpStatus,
        title: String,
        detail: String,
    ) {
        if (response.isCommitted) return
        val body = LinkedHashMap<String, Any?>()
        body["type"] = "about:blank"
        body["title"] = title
        body["status"] = status.value()
        body["detail"] = detail
        body["instance"] = request.requestURI
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(mapper.writeValueAsString(body))
        response.writer.flush()
    }
}
