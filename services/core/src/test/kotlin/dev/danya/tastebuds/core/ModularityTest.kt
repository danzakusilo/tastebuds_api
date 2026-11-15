package dev.danya.tastebuds.core

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.docs.Documenter

/** Module boundaries are part of the build: a cycle or an access to another module's internals fails `check`. */
class ModularityTest {
    private val modules = ApplicationModules.of(TastebudsCoreApplication::class.java)

    @Test
    fun `module boundaries are respected`() {
        modules.verify()
    }

    @Test
    fun `module documentation is generated`() {
        Documenter(modules).writeDocumentation()
    }
}
