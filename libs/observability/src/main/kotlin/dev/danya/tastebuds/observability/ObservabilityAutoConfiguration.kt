package dev.danya.tastebuds.observability

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.slf4j.Logger as Slf4jLogger

@ConfigurationProperties("tastebuds.observability.wide-events")
data class WideEventProperties(
    /** Request paths (PathPattern syntax) that never produce a wide event, e.g. health probes. */
    val excludePaths: List<String> = listOf("/actuator/**"),
)

// Ordered after Boot's OpenTelemetry SDK setup, otherwise @ConditionalOnBean(OpenTelemetry) is evaluated too early.
@AutoConfiguration(
    afterName = [
        "org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration",
        "org.springframework.boot.opentelemetry.autoconfigure.logging.OpenTelemetryLoggingAutoConfiguration",
    ],
)
@EnableConfigurationProperties(WideEventProperties::class)
class ObservabilityAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun wideEventSink(): WideEventSink = Slf4jWideEventSink()

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    class ServletWideEvents {
        @Bean
        fun wideEventFilter(
            sink: WideEventSink,
            enrichers: ObjectProvider<WideEventEnricher>,
            properties: WideEventProperties,
        ): FilterRegistrationBean<WideEventFilter> =
            FilterRegistrationBean(WideEventFilter(sink, enrichers.orderedStream().toList(), properties.excludePaths)).apply {
                // Directly after Spring's ServerHttpObservationFilter (HIGHEST_PRECEDENCE + 1), before Spring Security.
                order = Ordered.HIGHEST_PRECEDENCE + 2
            }
    }

    /** Exports application logs (including wide events, with key-value pairs as attributes) over OTLP. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(LoggerContext::class, OpenTelemetryAppender::class)
    @ConditionalOnBean(OpenTelemetry::class)
    class OtlpLogExport {
        @Bean
        fun openTelemetryAppenderInstaller(openTelemetry: OpenTelemetry) =
            InitializingBean {
                val context = org.slf4j.LoggerFactory.getILoggerFactory() as? LoggerContext ?: return@InitializingBean
                val root = context.getLogger(Slf4jLogger.ROOT_LOGGER_NAME) as Logger
                if (root.getAppender(APPENDER_NAME) == null) {
                    val appender =
                        OpenTelemetryAppender().apply {
                            name = APPENDER_NAME
                            this.context = context
                            setCaptureKeyValuePairAttributes(true)
                            setCaptureMdcAttributes("*")
                            start()
                        }
                    root.addAppender(appender)
                }
                OpenTelemetryAppender.install(openTelemetry)
            }

        companion object {
            const val APPENDER_NAME = "OTEL"
        }
    }
}
