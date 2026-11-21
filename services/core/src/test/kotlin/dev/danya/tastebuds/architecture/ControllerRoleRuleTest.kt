package dev.danya.tastebuds.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import dev.danya.tastebuds.core.ArchitectureTest
import dev.danya.tastebuds.core.meta.MetaController
import dev.danya.tastebuds.core.platform.ConditionalOnRole
import dev.danya.tastebuds.core.platform.Role
import dev.danya.tastebuds.core.sessions.AnonSessionController
import org.junit.jupiter.api.Test
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Self-test of the rule in [ArchitectureTest]: its must-fail cases fail for the stated reason. */
class ControllerRoleRuleTest {
    @RestController
    class WithoutRole

    @RestController
    @ConditionalOnRole(Role.WORKER)
    class WorkerRole

    @Controller
    class PlainWithoutRole

    @RestController
    @ConditionalOnRole(Role.API)
    class ApiRole

    @RestControllerAdvice
    class Advice

    private fun violations(vararg types: Class<*>): List<String> =
        ArchitectureTest.controllersOnlyInApiRole
            .evaluate(ClassFileImporter().importClasses(*types))
            .failureReport
            .details

    private fun expected(type: Class<*>) = "Class <${type.name}> is not annotated with @ConditionalOnRole(Role.API)"

    private fun assertSingleViolation(type: Class<*>) {
        val details = violations(type)

        assertEquals(1, details.size, "violations: $details")
        assertTrue(details.single().startsWith(expected(type)), "unexpected violation: ${details.single()}")
    }

    @Test
    fun `a rest controller without the role annotation breaks the rule`() {
        assertSingleViolation(WithoutRole::class.java)
    }

    @Test
    fun `a rest controller for the worker role breaks the rule`() {
        assertSingleViolation(WorkerRole::class.java)
    }

    @Test
    fun `a plain controller without the role annotation breaks the rule`() {
        assertSingleViolation(PlainWithoutRole::class.java)
    }

    @Test
    fun `an api controller and a controller advice pass the rule`() {
        assertEquals(emptyList(), violations(ApiRole::class.java, Advice::class.java))
    }

    @Test
    fun `each production controller is in the rule's scope and passes it`() {
        assertEquals(emptyList(), violations(AnonSessionController::class.java))
        assertEquals(emptyList(), violations(MetaController::class.java))
    }
}
