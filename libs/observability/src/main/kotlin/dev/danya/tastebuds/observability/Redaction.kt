package dev.danya.tastebuds.observability

internal object Redaction {
    const val REDACTED = "[REDACTED]"
    const val MAX_STRING_LENGTH = 256

    private val sensitiveFragments =
        listOf("password", "passwd", "secret", "token", "authorization", "cookie", "api_key", "apikey", "email", "phone", "ssn")

    fun isSensitive(key: String): Boolean {
        val normalized = key.lowercase()
        return sensitiveFragments.any { normalized.contains(it) }
    }

    fun sanitize(
        key: String,
        value: Any?,
    ): Any? =
        when {
            value == null -> null
            isSensitive(key) -> REDACTED
            value is Boolean || value is Int || value is Long || value is Double || value is Float -> value
            value is Number -> value.toString()
            value is Enum<*> -> value.name.lowercase()
            value is CharSequence -> truncate(value.toString())
            else -> "unsupported:${value.javaClass.simpleName}"
        }

    private fun truncate(value: String): String = if (value.length <= MAX_STRING_LENGTH) value else value.take(MAX_STRING_LENGTH) + "…"
}
