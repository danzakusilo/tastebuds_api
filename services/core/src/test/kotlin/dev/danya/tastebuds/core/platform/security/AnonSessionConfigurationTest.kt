package dev.danya.tastebuds.core.platform.security

import dev.danya.tastebuds.core.platform.AnonSessionIssuer
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.security.config.Customizer
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnonSessionConfigurationTest {
    private val secret = AnonSessionTestKeys.base64(AnonSessionTestKeys.randomSecret())
    private val runner = ApplicationContextRunner().withUserConfiguration(AnonSessionConfiguration::class.java)
    private val keyProperties =
        arrayOf("tastebuds.anon-session.signing-keys[0].id=k1", "tastebuds.anon-session.signing-keys[0].secret=$secret")

    private fun AssertableApplicationContext.hasSessionBeans() =
        containsBean("anonSessionTokens") &&
            containsBean("anonSessionSecurityCustomizer") &&
            getBeanNamesForType(AnonSessionIssuer::class.java).isNotEmpty() &&
            getBeanNamesForType(Customizer::class.java).isNotEmpty()

    private fun AssertableApplicationContext.failureText() =
        generateSequence(assertNotNull(startupFailure)) { it.cause }.mapNotNull { it.message }.joinToString("\n")

    @Test
    fun `the api role gets session tokens and the authentication customizer`() {
        runner.withPropertyValues("tastebuds.role=api", *keyProperties).run { context ->
            assertNull(context.startupFailure)
            assertTrue(context.hasSessionBeans())
        }
    }

    @Test
    fun `the worker role starts without a key and has no session beans`() {
        runner.withPropertyValues("tastebuds.role=worker").run { context ->
            assertNull(context.startupFailure)
            assertTrue(context.getBeanNamesForType(AnonSessionTokens::class.java).isEmpty())
            assertFalse(context.containsBean("anonSessionSecurityCustomizer"))
        }
    }

    @Test
    fun `the api role without a key fails startup`() {
        runner.withPropertyValues("tastebuds.role=api").run { context ->
            assertContains(context.failureText(), "tastebuds.anon-session.signing-keys")
        }
    }

    @Test
    fun `the ephemeral key lets the api role start without configured keys`() {
        runner.withPropertyValues("tastebuds.role=api", "tastebuds.anon-session.ephemeral-signing-key=true").run { context ->
            assertNull(context.startupFailure)
            assertTrue(context.hasSessionBeans())
        }
    }

    @Test
    fun `invalid settings fail startup without showing the secret`() {
        val shortSecret = "c2hvcnQ="
        runner
            .withPropertyValues(
                "tastebuds.anon-session.signing-keys[0].id=k1",
                "tastebuds.anon-session.signing-keys[0].secret=$shortSecret",
            ).run { context ->
                val text = context.failureText()
                assertContains(text, "signing-keys[0].secret")
                assertFalse(text.contains(shortSecret))
            }
    }
}
