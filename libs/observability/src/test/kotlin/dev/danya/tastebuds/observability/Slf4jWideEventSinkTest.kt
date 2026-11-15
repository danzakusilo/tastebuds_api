package dev.danya.tastebuds.observability

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals

class Slf4jWideEventSinkTest {
    private val logger = LoggerFactory.getLogger(Slf4jWideEventSink.LOGGER_NAME) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun attach() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(appender)
    }

    @Test
    fun `logs fields as key-value pairs at INFO`() {
        Slf4jWideEventSink().emit(WideEvent("http").put("http.response.status_code", 200).put("outcome", "success"))

        val record = appender.list.single()
        assertEquals(Level.INFO, record.level)
        assertEquals("wide_event", record.message)
        assertEquals(
            mapOf("work.kind" to "http", "http.response.status_code" to 200, "outcome" to "success"),
            record.keyValuePairs.associate {
                it.key to
                    it.value
            },
        )
    }

    @Test
    fun `logs failed units of work at ERROR`() {
        Slf4jWideEventSink().emit(WideEvent("http").put("outcome", "error"))

        assertEquals(Level.ERROR, appender.list.single().level)
    }
}
