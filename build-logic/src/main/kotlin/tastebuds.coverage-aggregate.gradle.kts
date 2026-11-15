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

tasks.named("check") {
    dependsOn("koverVerify")
}
