#!/usr/bin/env bash
# PreToolUse hook (Edit|Write|MultiEdit|NotebookEdit): while a task is active, file edits outside the task's
# allowed paths are blocked (exit 2 feeds the message back to the agent). With no active task, nothing is restricted.
# The active task is set by the run-task skill in .claude/active-task (git-ignored).
set -euo pipefail

root="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel)}"
active_file="$root/.claude/active-task"
[[ -f "$active_file" ]] || exit 0
task="$(tr -d '[:space:]' < "$active_file")"
[[ -n "$task" ]] || exit 0

file="$(jq -r '.tool_input.file_path // .tool_input.notebook_path // empty')"
[[ -n "$file" ]] || exit 0

if (cd "$root" && scripts/agentic/scope.sh allowed "$task" "$file"); then
  exit 0
fi

cat >&2 <<MSG
scope-guard: blocked edit to '$file': it is outside the allowed paths of $task.
Do not work around this. If you noticed an unrelated problem, record it in docs/backlog/ (see docs/backlog/README.md)
and continue with the task. If the task genuinely cannot be completed without this file, stop and report
"SCOPE_BLOCKED: <file> — <why>" so the planner can amend the spec.
MSG
exit 2
