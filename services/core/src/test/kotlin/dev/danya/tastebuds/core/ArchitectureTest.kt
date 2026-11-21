package dev.danya.tastebuds.core

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaAnnotation
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaEnumConstant
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.GeneralCodingRules
import dev.danya.tastebuds.core.platform.ConditionalOnRole
import dev.danya.tastebuds.core.platform.Role
import org.springframework.stereotype.Controller

/**
 * Project-wide rules that reviewers should never have to catch by hand.
 * SQL injection: raw JDBC statements are banned outright; queries go through jOOQ or bound parameters.
 */
@AnalyzeClasses(packages = ["dev.danya.tastebuds"], importOptions = [ImportOption.DoNotIncludeTests::class])
class ArchitectureTest {
    @ArchTest
    fun `no raw JDBC statements`(classes: JavaClasses) {
        noClasses()
            .should()
            .dependOnClassesThat()
            .areAssignableTo(java.sql.Statement::class.java)
            .because("string-built SQL is an injection risk; use jOOQ or bound parameters")
            .check(classes)
    }

    @ArchTest
    fun `no field injection`(classes: JavaClasses) {
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION.check(classes)
    }

    @ArchTest
    fun `no standard streams or printStackTrace`(classes: JavaClasses) {
        GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.check(classes)
    }

    @ArchTest
    fun `no java util logging`(classes: JavaClasses) {
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.check(classes)
    }

    @ArchTest
    fun `controllers exist only in the api role`(classes: JavaClasses) {
        controllersOnlyInApiRole.check(classes)
    }

    companion object {
        private val apiRole =
            DescribedPredicate.describe("@ConditionalOnRole(Role.API)") { annotation: JavaAnnotation<*> ->
                annotation.rawType.isEquivalentTo(ConditionalOnRole::class.java) &&
                    (annotation.get("value").orElse(null) as? JavaEnumConstant)?.name() == Role.API.name
            }

        /** CLAUDE.md, Roles: HTTP controllers get @ConditionalOnRole(Role.API). Shared with ControllerRoleRuleTest. */
        internal val controllersOnlyInApiRole: ArchRule =
            classes()
                .that()
                .areMetaAnnotatedWith(Controller::class.java)
                .should()
                .beAnnotatedWith(apiRole)
                .because("only the api role serves HTTP; a controller in the worker role fails startup or exposes an endpoint")
    }
}
