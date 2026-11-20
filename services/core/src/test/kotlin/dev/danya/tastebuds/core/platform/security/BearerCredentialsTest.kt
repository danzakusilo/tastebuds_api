package dev.danya.tastebuds.core.platform.security

import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BearerCredentialsTest {
    private fun credential(vararg headers: String): BearerCredential {
        val request = MockHttpServletRequest("GET", "/v1/x")
        headers.forEach { request.addHeader("Authorization", it) }
        return BearerCredentials.from(request)
    }

    @Test
    fun `no header means no credential`() {
        assertEquals(BearerCredential.Absent, credential())
    }

    @Test
    fun `tokens in the query string and cookies are not read`() {
        val request = MockHttpServletRequest("GET", "/v1/x")
        request.setParameter("access_token", "abc")
        request.setCookies(jakarta.servlet.http.Cookie("token", "abc"))
        assertEquals(BearerCredential.Absent, BearerCredentials.from(request))
    }

    @Test
    fun `other schemes are ignored`() {
        assertEquals(BearerCredential.Absent, credential("Basic dXNlcjpwYXNz"))
    }

    @Test
    fun `bearer credentials are read case-insensitively`() {
        listOf("Bearer abc", "bearer abc", "BEARER  abc").forEach {
            assertEquals("abc", assertIs<BearerCredential.Present>(credential(it)).token, it)
        }
    }

    @Test
    fun `malformed bearer headers are rejected`() {
        listOf("Bearer", "Bearer ", "Bearer a b", "Bearer a,b", "Bearerabc").forEach {
            assertEquals(BearerCredential.Malformed, credential(it), it)
        }
        assertEquals(BearerCredential.Malformed, credential("Bearer abc", "Bearer def"))
    }
}
