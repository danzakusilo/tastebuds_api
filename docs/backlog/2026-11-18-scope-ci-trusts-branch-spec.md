# CI scope job trusts a spec that is new on the branch
- **Found during:** 005 (review round 2)
- **Location:** scripts/agentic/scope.sh (`diff`), .github/workflows/ci.yml (`scope` job)
- **Severity:** medium
- **What:** The Allowed-paths comparison is skipped for a spec that does not exist at the merge base, and CI reads the
  spec from the branch. A branch can bring its own permissive spec (e.g. `- `**``) and pass the scope job.
- **Why it matters:** Task specs are supposed to be planned (and merged) before implementation; the check can be
  defeated by the branch under test.
- **Suggested fix:** In CI, require the spec to exist at the merge base (or read it from the base ref).
