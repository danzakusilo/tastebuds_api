# Dependency locking does not cover the plugin (buildscript) classpath of the main build
- **Found during:** spec audit 2026-11-21 (task 005; checklist rule 1)
- **Location:** docs/build/DEPENDENCY_LOCKING.md:3-5; build-logic/src/main/kotlin/tastebuds.kotlin.gradle.kts:68,
  tastebuds.coverage-aggregate.gradle.kts:17 (project-level `dependencyLocking` only)
- **Severity:** low
- **What:** The classpath that loads the convention plugins in the main build (Kotlin, Boot, Kover, ...) resolves
  without lock constraints: there is no `buildscript` locking and no `buildscript-gradle.lockfile`, and no lockfile
  lists a `classpath` configuration. In Gradle 9.8.2 `ScriptHandler` has its own `getDependencyLocking()`, separate
  from `Project.dependencyLocking`. `build-logic/gradle.lockfile` locks only build-logic's own configurations. The doc
  says every project locks all configurations. UNVERIFIED: the audit did not run Gradle. All versions are fixed today,
  so drift is unlikely.
- **Why it matters:** Reproducible-build claims should match what is locked.
- **Suggested fix:** Check whether `plugins {}` resolution in the main build can be locked
  (`buildscript.configurations.classpath` with `dependencyLocking`), lock it or state the limit in the doc.
