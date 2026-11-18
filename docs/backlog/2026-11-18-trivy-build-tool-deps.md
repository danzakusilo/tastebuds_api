# Trivy now scans locked build-tool dependencies
- **Found during:** 005 (review round 2, unverified)
- **Location:** .github/workflows/ci.yml (`dependencies` job), */gradle.lockfile
- **Severity:** medium
- **What:** The lockfiles include ~600 build-only artifacts (Kotlin compiler, detekt, ktlint, kotlin-dsl). Trivy's fs
  scan will report their HIGH/CRITICAL findings like runtime dependencies.
- **Why it matters:** The CI `dependencies` job may start failing on vulnerabilities that never ship in the image.
- **Suggested fix:** Decide a policy: scan only runtime-classpath lock entries (e.g. Trivy on the built image or
  SBOM), or accept and triage build-tool findings with a documented `.trivyignore`.
