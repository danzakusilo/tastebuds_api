// Applied to the root project only: merges Kover coverage of all modules and enforces the global floor.
plugins {
    base
    id("org.jetbrains.kotlinx.kover")
}

kover {
    reports {
        verify {
            rule("Aggregate line coverage") {
                minBound(70)
            }
        }
    }
}

dependencyLocking { lockAllConfigurations() }

// Writes/refreshes gradle.lockfile: ./gradlew resolveAndLockAll --write-locks (see docs/build/DEPENDENCY_LOCKING.md)
tasks.register("resolveAndLockAll") {
    notCompatibleWithConfigurationCache("Resolves configurations at execution time")
    doFirst {
        require(gradle.startParameter.isWriteDependencyLocks) { "Run with --write-locks" }
    }
    doLast {
        configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
    }
}

tasks.named("check") {
    dependsOn("koverVerify")
}
