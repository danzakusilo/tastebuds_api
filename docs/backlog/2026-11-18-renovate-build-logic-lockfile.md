# Renovate may not refresh build-logic/gradle.lockfile
- **Found during:** 005 (review round 2, unverified)
- **Location:** build-logic/gradle.lockfile, docs/build/DEPENDENCY_LOCKING.md (Renovate statement)
- **Severity:** medium
- **What:** Renovate's Gradle lockfile maintenance likely runs `--write-locks` only for the root build, not the included
  `build-logic` build. Plugin bumps in `libs.versions.toml` would then leave `build-logic/gradle.lockfile` stale.
- **Why it matters:** Renovate plugin-bump PRs fail CI; the doc's claim that Renovate PRs include refreshed lockfiles
  would be wrong.
- **Suggested fix:** Check the first Renovate plugin-bump PR; if stale, add a Renovate `postUpgradeTasks` or a
  root-level task that also locks the included build, and fix the doc.
