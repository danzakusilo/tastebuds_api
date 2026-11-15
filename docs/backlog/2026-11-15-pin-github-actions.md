# Pin GitHub Actions to commit SHAs
- **Found during:** Phase 0 setup
- **Location:** .github/workflows/*.yml
- **Severity:** security
- **What:** Workflows reference actions by version tag. Tags are mutable, which is a supply-chain risk.
- **Why it matters:** A compromised upstream tag would run arbitrary code with repository permissions.
- **Suggested fix:** Renovate's `helpers:pinGitHubActionDigests` preset (already enabled in renovate.json) opens PRs that pin
  them; merge the first batch once the repository is on GitHub.
