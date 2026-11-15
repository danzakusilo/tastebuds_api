# Backlog: out-of-scope findings

Agents (and humans) working on a task **do not fix unrelated problems in place**, even in the same file. They record
them here, one file per finding, and move on. The planner triages this folder into task specs.

File name: `YYYY-MM-DD-short-slug.md`

```markdown
# <one-line summary>
- **Found during:** <task id or "ad hoc">
- **Location:** <path:line>
- **Severity:** low | medium | high | security
- **What:** <observed problem>
- **Why it matters:** <impact>
- **Suggested fix:** <optional>
```
