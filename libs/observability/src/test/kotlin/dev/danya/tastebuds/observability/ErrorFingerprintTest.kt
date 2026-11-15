package dev.danya.tastebuds.observability

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ErrorFingerprintTest {
    private fun failAt(message: String): Throwable = runCatching { error(message) }.exceptionOrNull()!!

    private fun failDifferently(): Throwable = runCatching { throw IllegalArgumentException("x") }.exceptionOrNull()!!

    @Test
    fun `same site and type yield the same fingerprint even with different messages`() {
        assertEquals(ErrorFingerprint.of(failAt("a")), ErrorFingerprint.of(failAt("b")))
    }

    @Test
    fun `different types yield different fingerprints`() {
        assertNotEquals(ErrorFingerprint.of(failAt("a")), ErrorFingerprint.of(failDifferently()))
    }

    @Test
    fun `wrapping exceptions are fingerprinted by their root cause`() {
        val root = failAt("a")
        assertEquals(ErrorFingerprint.of(root), ErrorFingerprint.of(RuntimeException("wrapper", root)))
    }
}
