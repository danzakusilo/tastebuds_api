# Scope guard: decide how to treat `tool_input._host` while a task is active
- **Found during:** 015 (review round 1, checklist rule 7)
- **Location:** .claude/hooks/scope-guard.sh
- **Severity:** low
- **What:** In Claude Code 2.1.330, Edit and Write accept an optional, feature-gated `tool_input._host` (an
  "attached machine" to run the call on). The hook checks `file_path` as a path in this repository whatever `_host`
  says, so an edit on another machine is judged against this repository's Allowed paths.
- **Why it matters:** The guard's decision would not match where the edit happens once the feature is enabled.
- **Suggested fix:** When `_host` is set and a task is active, block with a clear message (fail closed), or allow only
  when `_host` is absent or names the local machine. Check the field again when Claude Code documents it.
