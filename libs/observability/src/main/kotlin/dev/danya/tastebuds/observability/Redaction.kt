package dev.danya.tastebuds.observability

internal object Redaction {
    const val REDACTED = "[REDACTED]"
    const val MAX_STRING_LENGTH = 256

    private val sensitiveWords =
        listOf("password", "passwd", "secret", "token", "authorization", "cookie", "api_key", "apikey", "email", "phone", "ssn")

    private val notSensitive = setOf("llm.tokens_in", "llm.tokens_out")

    /**
     * True when the lowercased key contains any sensitive word (substring match), unless the whole key is in
     * [notSensitive]. Redaction must fail closed: false positives are preferred over leaks, so plurals and
     * run-together names (`auth.tokens`, `auth.accesstoken`) are redacted. To exempt a new key, add its exact name to
     * [notSensitive] deliberately.
     */
    fun isSensitive(key: String): Boolean {
        val lower = key.lowercase()
        return lower !in notSensitive && sensitiveWords.any { it in lower }
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
