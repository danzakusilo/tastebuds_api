# Scope guard: unverified whether hooks can receive a relative `file_path`
- **Found during:** 015 (review round 1, checklist rule 1)
- **Location:** .claude/hooks/scope-guard.sh (exactly-one-absolute-path check)
- **Severity:** low
- **What:** Task 015 blocks any input whose path is not absolute. "Edit and Write send an absolute `file_path`" comes
  from the tool schema text in Claude Code 2.1.330, not from a captured PreToolUse input. UNVERIFIED: whether Claude
  Code passes a model-supplied relative path to the hook unchanged.
- **Why it matters:** If it does, such edits get a block. This fails closed, and the agent can recover by retrying with
  an absolute path, but the message could confuse it.
- **Suggested fix:** Capture a real PreToolUse input for an Edit with a relative path (a logging hook in a scratch
  project) and record the result; if relative paths arrive, mention "use an absolute path" in the block message.
