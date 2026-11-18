plugins {
    `kotlin-dsl`
}

dependencyLocking { lockAllConfigurations() }

// Writes/refreshes gradle.lockfile: ./gradlew -p build-logic resolveAndLockAll --write-locks
// (see docs/build/DEPENDENCY_LOCKING.md)
tasks.register("resolveAndLockAll") {
    notCompatibleWithConfigurationCache("Resolves configurations at execution time")
    doFirst {
        require(gradle.startParameter.isWriteDependencyLocks) { "Run with --write-locks" }
    }
    doLast {
        configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
    }
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.allopen)
    implementation(libs.spring.boot.gradle.plugin)
    implementation(libs.kover.gradle.plugin)
    implementation(libs.ktlint.gradle.plugin)
    implementation(libs.detekt.gradle.plugin)

    constraints {
        implementation(libs.freemarker) {
            because("CVE-2026-84939 (CRITICAL) in 2.3.32, which Kover's HTML coverage report pulls in; remove once Kover ships >= 2.3.35")
        }
    }
}
