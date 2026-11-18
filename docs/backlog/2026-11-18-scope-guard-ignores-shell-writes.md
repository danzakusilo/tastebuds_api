# Scope guard hook does not see file writes made through Bash
- **Found during:** 003 (run-task round 1; the implementer wrote files with heredocs and Python)
- **Location:** .claude/settings.json (PreToolUse matcher `Edit|Write|MultiEdit|NotebookEdit`), .claude/agents/implementer.md
- **Severity:** medium
- **What:** Writes via `cat > file`, `sed -i`, Python etc. never pass through `scope-guard.sh`. In 003 every target was
  in scope (confirmed post hoc by `scope.sh diff`), but the hook layer was effectively bypassed. Task 005 hardens the
  hook's fail-closed behaviour but not its coverage.
- **Why it matters:** Layer 2 of the scope enforcement in docs/agentic/WORKFLOW.md is advisory for shell writes; only
  the after-the-fact diff check catches violations.
- **Suggested fix:** State in implementer.md that file changes go through Edit/Write only (shell for build/test/read);
  optionally add a Bash PreToolUse check that rejects obvious write patterns, or run `scope.sh diff` in a Stop/SubagentStop
  hook. Changing settings.json is the user's call.
