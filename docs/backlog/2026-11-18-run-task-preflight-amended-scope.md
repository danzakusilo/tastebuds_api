# run-task preflight does not check for an unmerged Allowed-paths amendment
- **Found during:** 005 (run-task; the round-2 resume had the amendment only on the branch)
- **Location:** .claude/skills/run-task/SKILL.md (Preflight)
- **Severity:** low
- **What:** WORKFLOW.md now says an amended Allowed paths section must be merged to `main` before re-running a task,
  but the skill's preflight does not check it.
- **Why it matters:** The run proceeds and the CI `scope` job fails later on the PR.
- **Suggested fix:** In preflight, run `scripts/agentic/scope.sh diff <spec> main` and stop when it reports
  "Allowed paths changed".
