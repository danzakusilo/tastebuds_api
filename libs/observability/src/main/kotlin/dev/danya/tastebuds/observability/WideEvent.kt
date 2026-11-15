package dev.danya.tastebuds.observability

/**
 * One structured, schema-stable event per unit of work (HTTP request, Kafka message, scheduled job).
 *
 * Code anywhere on the call path enriches the current event via [WideEvent.current]; the event is emitted exactly once
 * when the unit of work ends. Field names follow `docs/observability/WIDE_EVENT_SCHEMA.md`.
 *
 * Values are sanitized on write: only scalars are kept, long strings are truncated and keys that look sensitive are
 * redacted, so an accidental `put("password", ...)` can never reach the logs.
 */
class WideEvent(
    val kind: String,
) {
    private val fields = LinkedHashMap<String, Any?>()

    init {
        fields[KIND] = kind
    }

    fun put(
        key: String,
        value: Any?,
    ): WideEvent {
        require(KEY_PATTERN.matches(key)) { "Invalid wide event key '$key'" }
        fields[key] = Redaction.sanitize(key, value)
        return this
    }

    fun increment(
        key: String,
        by: Long = 1,
    ): WideEvent {
        val current = fields[key] as? Long ?: 0L
        return put(key, current + by)
    }

    /** Records error identity without the message: messages routinely contain user input. */
    fun recordError(error: Throwable): WideEvent =
        put(ERROR_CLASS, error.javaClass.name)
            .put(ERROR_FINGERPRINT, ErrorFingerprint.of(error))

    operator fun get(key: String): Any? = fields[key]

    fun snapshot(): Map<String, Any?> = LinkedHashMap(fields)

    companion object {
        const val KIND = "work.kind"
        const val OUTCOME = "outcome"
        const val DURATION_MS = "duration_ms"
        const val ERROR_CLASS = "error.class"
        const val ERROR_FINGERPRINT = "error.fingerprint"

        private val KEY_PATTERN = Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)*$")
        private val holder = ThreadLocal<WideEvent?>()

        /** The event bound to the current unit of work, or null outside of one. */
        fun current(): WideEvent? = holder.get()

        /** Binds [event] for the duration of [block]; restores the previous binding afterwards. */
        fun <T> within(
            event: WideEvent,
            block: () -> T,
        ): T {
            val previous = holder.get()
            holder.set(event)
            try {
                return block()
            } finally {
                if (previous == null) holder.remove() else holder.set(previous)
            }
        }
    }
}

enum class Outcome { SUCCESS, CLIENT_ERROR, ERROR }
