package dev.danya.tastebuds.core.platform

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.test.assertEquals

class RoleConditionTest {
    @Suppress("FunctionOnlyReturningConstant") // marker beans: only their presence matters
    @Configuration(proxyBeanMethods = false)
    class RoleBeans {
        @Bean
        @ConditionalOnRole(Role.API)
        fun apiBean() = "api"

        @Bean
        @ConditionalOnRole(Role.WORKER)
        fun workerBean() = "worker"
    }

    private val runner = ApplicationContextRunner().withUserConfiguration(RoleBeans::class.java)

    private fun beansFor(role: String?): Set<String> {
        var names = emptySet<String>()
        val configured = if (role == null) runner else runner.withPropertyValues("tastebuds.role=$role")
        configured.run { context -> names = context.getBeansOfType(String::class.java).values.toSet() }
        return names
    }

    @Test
    fun `api role loads only api beans`() = assertEquals(setOf("api"), beansFor("api"))

    @Test
    fun `worker role loads only worker beans`() = assertEquals(setOf("worker"), beansFor("worker"))

    @Test
    fun `all role loads both`() = assertEquals(setOf("api", "worker"), beansFor("all"))

    @Test
    fun `missing role defaults to all`() = assertEquals(setOf("api", "worker"), beansFor(null))
}
