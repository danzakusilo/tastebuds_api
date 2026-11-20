# The documented root lock command rewrites the header of build-logic/gradle.lockfile
- **Found during:** 001 (run-task round 1; the implementer stopped with SCOPE_BLOCKED)
- **Location:** docs/build/DEPENDENCY_LOCKING.md:19-20, build-logic/gradle.lockfile:4
- **Severity:** low
- **What:** `./gradlew resolveAndLockAll --write-locks --no-configuration-cache` from the root also rewrites
  `build-logic/gradle.lockfile`. No dependency line changes, but the header comment goes from
  `./gradlew :dependencies --write-locks` (as written by `-p build-logic`) to
  `./gradlew :build-logic:dependencies --write-locks`. The committed file has the `-p build-logic` header, so every
  task that adds a dependency and follows the doc gets a stray change outside its Allowed paths.
- **Why it matters:** Scope guard and `scope.sh diff` flag the stray file; implementers stop with SCOPE_BLOCKED for
  a comment-only change (happened in 001).
- **Suggested fix:** Commit `build-logic/gradle.lockfile` once with the header the root command writes (so both
  commands agree, or document that only the root command is used), or make task specs that add dependencies list
  `build-logic/gradle.lockfile` too. Check whether `-p build-logic` then flips it back.
