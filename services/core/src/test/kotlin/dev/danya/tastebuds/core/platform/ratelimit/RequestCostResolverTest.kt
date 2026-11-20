package dev.danya.tastebuds.core.platform.ratelimit

import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.util.pattern.PatternParseException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RequestCostResolverTest {
    private fun resolver(vararg rules: RateLimitProperties.Rule) =
        RequestCostResolver(RateLimitProperties(defaultCost = 1, rules = rules.toList()))

    private fun cost(
        resolver: RequestCostResolver,
        method: String,
        uri: String,
    ) = resolver.resolve(MockHttpServletRequest(method, uri))

    @Test
    fun `the first matching rule sets the cost`() {
        val resolver =
            resolver(
                RateLimitProperties.Rule(null, "/v1/media/**", 5),
                RateLimitProperties.Rule(null, "/v1/media/{id}", 9),
            )
        assertEquals(RequestCost(5, "* /v1/media/**"), cost(resolver, "GET", "/v1/media/42"))
    }

    @Test
    fun `a rule with a method matches only that method`() {
        val resolver = resolver(RateLimitProperties.Rule("GET", "/v1/meta", 2))
        assertEquals(RequestCost(2, "GET /v1/meta"), cost(resolver, "GET", "/v1/meta"))
        assertEquals(RequestCost(1, "default"), cost(resolver, "POST", "/v1/meta"))
    }

    @Test
    fun `path variables in patterns match`() {
        val resolver = resolver(RateLimitProperties.Rule(null, "/v1/media/{id}", 3))
        assertEquals(3, cost(resolver, "GET", "/v1/media/42").tokens)
    }

    @Test
    fun `paths match after decoding, like Spring MVC routing`() {
        val resolver = resolver(RateLimitProperties.Rule("GET", "/v1/meta", 2))
        assertEquals(2, cost(resolver, "GET", "/v1/%6Deta").tokens)
    }

    @Test
    fun `unmatched requests use the default cost`() {
        val resolver = resolver(RateLimitProperties.Rule("GET", "/v1/meta", 2))
        assertEquals(RequestCost(1, "default"), cost(resolver, "GET", "/v1/other"))
        assertEquals(RequestCost(1, "default"), cost(resolver, "GET", "/v1/meta/"))
    }

    @Test
    fun `an invalid pattern fails construction`() {
        assertFailsWith<PatternParseException> { resolver(RateLimitProperties.Rule(null, "/v1/{", 1)) }
    }
}
