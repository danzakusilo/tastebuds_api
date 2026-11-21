# TEMPLATE.md says the task file is always allowed, but it is read-only during a task
- **Found during:** planning of task 015 (spec audit 2026-11-21)
- **Location:** docs/tasks/TEMPLATE.md (comment under "## Allowed paths")
- **Severity:** low
- **What:** The comment says "The task file and docs/backlog/** are always allowed." Since task 005 the scope-guard hook
  blocks file-tool edits to the active spec; only `scripts/agentic/task-meta.sh` changes its Status and Review log.
- **Why it matters:** A planner or implementer reading the template gets the wrong rule.
- **Suggested fix:** "docs/backlog/** is always allowed. The task file is allowed in the diff, but read-only during the
  task (the orchestrator edits Status and Review log with scripts/agentic/task-meta.sh)." Change it on its own branch
  (TEMPLATE.md is also changed in PR #10).
