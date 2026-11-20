package dev.danya.tastebuds.core.platform.ratelimit

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.server.PathContainer
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser

internal data class RequestCost(
    val tokens: Long,
    val rule: String,
)

internal class RequestCostResolver(
    properties: RateLimitProperties,
) {
    private class CompiledRule(
        val method: String?,
        val pattern: PathPattern,
        val cost: RequestCost,
    )

    private val defaultCost = RequestCost(properties.defaultCost, "default")
    private val rules =
        properties.rules.map {
            CompiledRule(
                it.method,
                PathPatternParser.defaultInstance.parse(it.pattern),
                RequestCost(it.cost, "${it.method ?: "*"} ${it.pattern}"),
            )
        }

    fun resolve(request: HttpServletRequest): RequestCost {
        val path = PathContainer.parsePath(request.requestURI.removePrefix(request.contextPath))
        return rules.firstOrNull { (it.method == null || it.method == request.method) && it.pattern.matches(path) }?.cost
            ?: defaultCost
    }
}
