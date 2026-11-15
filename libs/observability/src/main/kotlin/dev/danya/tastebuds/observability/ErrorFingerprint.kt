package dev.danya.tastebuds.observability

import java.security.MessageDigest

/**
 * Stable, low-cardinality identity for "the same bug": exception type plus the top stack frames by class and method
 * (no line numbers, so a fingerprint survives unrelated edits and redeploys). Used to cluster errors in digests.
 */
object ErrorFingerprint {
    private const val APP_PACKAGE = "dev.danya.tastebuds"
    private const val FRAME_COUNT = 3
    private const val HEX_LENGTH = 12

    fun of(error: Throwable): String {
        val root = rootCause(error)
        val frames =
            root.stackTrace
                .filter { it.className.startsWith(APP_PACKAGE) }
                .ifEmpty { root.stackTrace.toList() }
                .take(FRAME_COUNT)
                .joinToString("|") { "${it.className}#${it.methodName}" }
        val digest = MessageDigest.getInstance("SHA-256").digest("${root.javaClass.name}|$frames".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(HEX_LENGTH)
    }

    private fun rootCause(error: Throwable): Throwable {
        var current = error
        val seen = mutableSetOf<Throwable>()
        while (current.cause != null && seen.add(current)) {
            current = current.cause!!
        }
        return current
    }
}
