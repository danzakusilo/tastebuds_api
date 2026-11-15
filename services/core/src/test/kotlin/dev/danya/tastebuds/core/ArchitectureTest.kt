package dev.danya.tastebuds.core

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.GeneralCodingRules

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
}
