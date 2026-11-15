package dev.danya.tastebuds.observability

import org.slf4j.LoggerFactory
import org.slf4j.event.Level

/** Destination for finished wide events. */
fun interface WideEventSink {
    fun emit(event: WideEvent)
}

/** Adds fields common to every event of a service (role, version, ...) at the start of a unit of work. */
fun interface WideEventEnricher {
    fun enrich(event: WideEvent)
}

/**
 * Emits each event as one log record on the `wide_event` logger, fields as SLF4J key-value pairs. Structured logging
 * renders them as JSON attributes on the console, and the OpenTelemetry appender exports them as log attributes.
 */
class Slf4jWideEventSink : WideEventSink {
    private val logger = LoggerFactory.getLogger(LOGGER_NAME)

    override fun emit(event: WideEvent) {
        val level = if (event[WideEvent.OUTCOME] == Outcome.ERROR.name.lowercase()) Level.ERROR else Level.INFO
        val builder = logger.atLevel(level)
        event.snapshot().forEach { (key, value) -> builder.addKeyValue(key, value) }
        builder.log(MESSAGE)
    }

    companion object {
        const val LOGGER_NAME = "wide_event"
        const val MESSAGE = "wide_event"
    }
}
