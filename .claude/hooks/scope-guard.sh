#!/usr/bin/env bash
# PreToolUse hook (Edit|Write|MultiEdit|NotebookEdit): while a task is active, file edits outside the task's
# allowed paths are blocked (exit 2 feeds the message back to the agent). With no active task, nothing is restricted.
# The active task is set by the run-task skill in .claude/active-task (git-ignored).
# The active spec itself is read-only; the orchestrator updates it with scripts/agentic/task-meta.sh.
# Fails closed: any internal error blocks the edit (exit 2), because Claude Code treats other exit codes as "allow".
set -euo pipefail
trap 'echo "scope-guard: internal error (line $LINENO); blocking the edit (fail closed)" >&2; exit 2' ERR

block() { echo "scope-guard: $*; blocking the edit (fail closed)" >&2; exit 2; }

command -v jq >/dev/null 2>&1 || block "jq is not installed"

input="$(cat)"
# jq 1.6 accepts empty input, so reject empty or whitespace-only stdin explicitly.
[[ "$input" == *[![:space:]]* ]] || block "stdin is empty"
printf '%s' "$input" | jq -e . >/dev/null 2>&1 || block "stdin is not valid JSON"

root="${CLAUDE_PROJECT_DIR:-}"
if [[ -z "$root" ]]; then
  root="$(git rev-parse --show-toplevel 2>/dev/null)" || block "cannot determine the repository root"
fi
[[ -n "$root" && -d "$root" ]] || block "cannot determine the repository root"

active_file="$root/.claude/active-task"
[[ -f "$active_file" ]] || exit 0
task="$(tr -d '[:space:]' < "$active_file")"
[[ -n "$task" ]] || block "$active_file is empty"

file="$(printf '%s' "$input" | jq -r '.tool_input.file_path // .tool_input.notebook_path // empty')" \
  || block "jq failed to parse the tool input"
[[ -n "$file" ]] || exit 0

[[ -f "$root/$task" ]] || block "active task spec not found: $task"

task_abs="$(realpath -m "$root/$task")"
file_abs="$(realpath -m "$file")"
if [[ "$file_abs" == "$task_abs" ]]; then
  cat >&2 <<MSG
scope-guard: blocked edit to the active task spec '$task': it is read-only during a task.
Status and Review log are updated by the orchestrator with scripts/agentic/task-meta.sh. If the Allowed paths are
wrong, stop and report "SCOPE_BLOCKED: <file> — <why>" so the planner can amend the spec.
MSG
  exit 2
fi

rc=0
(cd "$root" && scripts/agentic/scope.sh allowed "$task" "$file") || rc=$?
case "$rc" in
  0) exit 0 ;;
  1) ;;
  *) block "scope.sh failed with exit $rc" ;;
esac

cat >&2 <<MSG
scope-guard: blocked edit to '$file': it is outside the allowed paths of $task.
Do not work around this. If you noticed an unrelated problem, record it in docs/backlog/ (see docs/backlog/README.md)
and continue with the task. If the task genuinely cannot be completed without this file, stop and report
"SCOPE_BLOCKED: <file> — <why>" so the planner can amend the spec.
MSG
exit 2
