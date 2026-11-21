package dev.danya.tastebuds.core.platform.ratelimit

import dev.danya.tastebuds.core.platform.security.AnonSessionTestKeys
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnonSessionClientKeyResolverTest {
    private val secret = AnonSessionTestKeys.randomSecret()
    private val tokens = AnonSessionTestKeys.tokens("k1", secret)
    private val resolver = AnonSessionClientKeyResolver(tokens)
    private val sessionKeyShape = Regex("^as:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    private fun request(vararg authorization: String) =
        MockHttpServletRequest("GET", "/v1/meta").apply { authorization.forEach { addHeader("Authorization", it) } }

    @Test
    fun `a valid session token keys by the session`() {
        val issued = tokens.issue()
        val key = resolver.resolve(request("Bearer ${issued.token}"))
        assertEquals(ClientKey("as:${issued.principal.sessionId}", ClientKeyType.SESSION), key)
        assertTrue(sessionKeyShape.matches(key!!.value), key.value)
    }

    @Test
    fun `requests without a valid session token have no session key`() {
        val valid = tokens.issue().token
        val otherSecret = AnonSessionTestKeys.tokens("k1", AnonSessionTestKeys.randomSecret()).issue().token
        val unknownKid = AnonSessionTestKeys.tokens("zz", secret).issue().token
        val expired =
            AnonSessionTestKeys
                .tokens("k1", secret, Duration.ofSeconds(1), Instant.now().minus(Duration.ofDays(1)))
                .issue()
                .token
        listOf(
            request(),
            request("Basic dXNlcjpwYXNz"),
            request("Bearer"),
            request("Bearer garbage"),
            request("Bearer $otherSecret"),
            request("Bearer $unknownKid"),
            request("Bearer $expired"),
            request("Bearer $valid", "Bearer $valid"),
        ).forEach { assertNull(resolver.resolve(it)) }
    }
}
