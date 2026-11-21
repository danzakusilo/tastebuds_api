package dev.danya.tastebuds.core.platform.ratelimit

import dev.danya.tastebuds.core.platform.security.AnonSessionTokens
import dev.danya.tastebuds.core.platform.security.AnonSessionVerification
import dev.danya.tastebuds.core.platform.security.BearerCredential
import dev.danya.tastebuds.core.platform.security.BearerCredentials
import jakarta.servlet.http.HttpServletRequest
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

internal enum class ClientKeyType { IP4, IP6, INVALID, SESSION }

/** [value] is the bucket identity. Never log it or put it in a wide event or response; [type] is safe to record. */
internal data class ClientKey(
    val value: String,
    val type: ClientKeyType,
)

/** Chooses one bucket a request draws from; null when this resolver has no key for the request. */
internal fun interface ClientKeyResolver {
    fun resolve(request: HttpServletRequest): ClientKey?
}

/** Keys by the anonymous session of a valid bearer token (signature and expiry checked, no I/O); null otherwise. */
internal class AnonSessionClientKeyResolver(
    private val tokens: AnonSessionTokens,
) : ClientKeyResolver {
    override fun resolve(request: HttpServletRequest): ClientKey? {
        val credential = BearerCredentials.from(request) as? BearerCredential.Present ?: return null
        val verification = tokens.verify(credential.token) as? AnonSessionVerification.Valid
        return verification?.let { ClientKey("as:${it.principal.sessionId}", ClientKeyType.SESSION) }
    }
}

/** Keys by `request.remoteAddr` (resolved from trusted proxies only, docs/security/FORWARDED_HEADERS.md). */
internal class IpClientKeyResolver : ClientKeyResolver {
    override fun resolve(request: HttpServletRequest): ClientKey = keyFor(request.remoteAddr)

    companion object {
        private const val MAX_LENGTH = 64
        private const val PREFIX_GROUPS = 4
        private const val BYTES_PER_GROUP = 2
        private const val BYTE_MASK = 0xFF
        private const val BYTE_BITS = 8
        private const val OCTET = "(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])"
        private val DOTTED_QUAD = Regex("^($OCTET\\.){3}$OCTET$")
        private val IPV6_CHARS = Regex("^[0-9A-Fa-f:][0-9A-Fa-f:.]*$")
        private val INVALID = ClientKey("ip:invalid", ClientKeyType.INVALID)

        /** Applies the rules of the task 001 design (§3); never resolves host names. */
        fun keyFor(address: String?): ClientKey =
            when {
                address.isNullOrEmpty() || address.length > MAX_LENGTH -> INVALID
                DOTTED_QUAD.matches(address) -> ClientKey("ip4:$address", ClientKeyType.IP4)
                else -> ipv6Key(address.substringBefore('%'))
            }

        private fun ipv6Key(rest: String): ClientKey {
            if (!IPV6_CHARS.matches(rest) || ':' !in rest) return INVALID
            return when (val parsed = parseLiteral(rest)) {
                is Inet4Address -> ClientKey("ip4:${parsed.hostAddress}", ClientKeyType.IP4)
                is Inet6Address -> ClientKey("ip6:${prefix64(parsed)}", ClientKeyType.IP6)
                else -> INVALID
            }
        }

        /** Only called with a string that has a `:` and hex/colon/dot characters, so the JDK never does a DNS lookup. */
        private fun parseLiteral(literal: String): InetAddress? =
            try {
                InetAddress.getByName(literal)
            } catch (_: UnknownHostException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }

        private fun prefix64(address: Inet6Address): String {
            val bytes = address.address
            val groups =
                (0 until PREFIX_GROUPS).map { i ->
                    val high = bytes[i * BYTES_PER_GROUP].toInt() and BYTE_MASK
                    val low = bytes[i * BYTES_PER_GROUP + 1].toInt() and BYTE_MASK
                    ((high shl BYTE_BITS) or low).toString(16)
                }
            return groups.joinToString(":") + "::/64"
        }
    }
}
