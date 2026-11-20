package dev.danya.tastebuds.core.platform.ratelimit

import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IpClientKeyResolverTest {
    private val keyShape =
        Regex("^(ip4:[0-9]{1,3}(\\.[0-9]{1,3}){3}|ip6:[0-9a-f]{1,4}(:[0-9a-f]{1,4}){3}::/64|ip:invalid)$")
    private val invalid = ClientKey("ip:invalid", ClientKeyType.INVALID)

    private fun key(address: String?) = IpClientKeyResolver.keyFor(address)

    @Test
    fun `IPv4 addresses key by the full address`() {
        assertEquals(ClientKey("ip4:203.0.113.7", ClientKeyType.IP4), key("203.0.113.7"))
    }

    @Test
    fun `IPv6 addresses key by their 64-bit prefix`() {
        val expected = ClientKey("ip6:2001:db8:85a3:0::/64", ClientKeyType.IP6)
        assertEquals(expected, key("2001:db8:85a3::8a2e:370:7334"))
        assertEquals(expected, key("2001:DB8:85A3:0:ffff::1"))
        assertNotEquals(expected, key("2001:db8:85a3:1::1"))
    }

    @Test
    fun `IPv4-mapped IPv6 addresses key as IPv4`() {
        val expected = ClientKey("ip4:203.0.113.7", ClientKeyType.IP4)
        assertEquals(expected, key("::ffff:203.0.113.7"))
        assertEquals(expected, key("::ffff:cb00:7107"))
    }

    @Test
    fun `zone ids are ignored`() {
        assertEquals(ClientKey("ip6:fe80:0:0:0::/64", ClientKeyType.IP6), key("fe80::1%eth0"))
    }

    @Test
    fun `host names are never resolved`() {
        listOf("localhost", "example.com", "cafe", "deadbeef").forEach { assertEquals(invalid, key(it), it) }
    }

    @Test
    fun `malformed addresses fall back to one shared key`() {
        val inputs =
            listOf(
                null,
                "",
                "not-an-ip",
                "1.2.3",
                "256.1.1.1",
                "010.0.0.1",
                "1.2.3.4:80",
                "[2001:db8::1]",
                "2001:db8::1::2",
                ".:1",
                " 203.0.113.7",
                "203.0.113.7\n",
                "203.0.113.7\r\nX-Injected: 1",
                "１２３.0.0.1",
                "1:".repeat(32) + "1",
                "a".repeat(10_000),
            )
        inputs.forEach { assertEquals(invalid, key(it), "input: $it") }
    }

    @Test
    fun `keys always have a bounded, known shape`() {
        val random = Random(42)
        val alphabet = "0123456789abcdefABCDEF:.%[] \né１"
        repeat(10_000) {
            val input = String(CharArray(random.nextInt(0, 81)) { alphabet[random.nextInt(alphabet.length)] })
            val value = key(input).value
            assertTrue(keyShape.matches(value), "shape: $value")
            assertTrue(value.length <= 28, "length: $value")
        }
    }

    @Test
    fun `the resolver reads remoteAddr and ignores X-Forwarded-For`() {
        val request =
            MockHttpServletRequest().apply {
                remoteAddr = "203.0.113.7"
                addHeader("X-Forwarded-For", "198.51.100.9")
            }
        assertEquals("ip4:203.0.113.7", IpClientKeyResolver().resolve(request).value)
    }
}
