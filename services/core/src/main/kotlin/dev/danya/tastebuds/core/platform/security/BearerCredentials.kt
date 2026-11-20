package dev.danya.tastebuds.core.platform.security

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders

internal sealed interface BearerCredential {
    data object Absent : BearerCredential

    data object Malformed : BearerCredential

    class Present(
        val token: String,
    ) : BearerCredential {
        override fun toString(): String = "Present(token=[REDACTED])"
    }
}

/** Reads a bearer token from the `Authorization` header only: never from the query string, a form, the body or a cookie. */
internal object BearerCredentials {
    private val SCHEME = Regex("^bearer", RegexOption.IGNORE_CASE)
    private val CREDENTIAL = Regex("^bearer +([A-Za-z0-9\\-._~+/]+=*)$", RegexOption.IGNORE_CASE)

    fun from(request: HttpServletRequest): BearerCredential {
        val values = request.getHeaders(HttpHeaders.AUTHORIZATION).toList()
        return when {
            values.isEmpty() -> BearerCredential.Absent
            values.size > 1 -> BearerCredential.Malformed
            !SCHEME.containsMatchIn(values[0]) -> BearerCredential.Absent
            else -> CREDENTIAL.matchEntire(values[0])?.let { BearerCredential.Present(it.groupValues[1]) } ?: BearerCredential.Malformed
        }
    }
}
