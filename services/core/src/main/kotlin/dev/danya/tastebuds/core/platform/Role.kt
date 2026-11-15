package dev.danya.tastebuds.core.platform

import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * The same image runs as `api` (HTTP controllers) or `worker` (Kafka consumers, schedulers), scaled independently.
 * `all` runs both in one process, for local development and tests.
 */
enum class Role { API, WORKER, ALL }

/** Registers the annotated bean only when the process runs [value] (or `all`). */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Conditional(OnRoleCondition::class)
annotation class ConditionalOnRole(
    val value: Role,
)

internal class OnRoleCondition : Condition {
    override fun matches(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata,
    ): Boolean {
        val required = metadata.getAnnotationAttributes(ConditionalOnRole::class.java.name)?.get("value") as Role
        val active = Binder.get(context.environment).bind("tastebuds.role", Role::class.java).orElse(Role.ALL)
        return active == Role.ALL || active == required
    }
}
