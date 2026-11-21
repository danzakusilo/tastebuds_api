#!/usr/bin/env bash
# PreToolUse hook (Edit|Write|MultiEdit|NotebookEdit): while a task is active, edits outside its allowed paths are
# blocked (exit 2 feeds the message back to the agent). With no .claude/active-task nothing is restricted.
# Two allow cases only:
# A) no active task (whatever stdin holds, jq or not); B) scripts/agentic/scope.sh allowed says the path is in scope.
# .claude/active-task (git-ignored) holds the spec path. The run-task skill writes it in preflight and deletes it at
# handoff. After a crash, an abort or a branch switch it can be stale: only the user deletes it
# (rm .claude/active-task). The hook never writes or deletes it. The active spec is read-only; the orchestrator
# uses scripts/agentic/task-meta.sh.
# jq is needed only while a task is active.
# Fails closed: the EXIT trap turns every status other than 0 and 2 into 2, because Claude Code treats other codes as
# "allow".
set -euo pipefail

hint=""   # set once a task is active; printed under every later block message
say_block() {
  echo "scope-guard: $*; blocking the edit (fail closed)" >&2
  [[ -z "$hint" ]] || echo "$hint" >&2
}
# A failing echo (closed stderr) must not turn a block into an allow: a failing command in an EXIT trap ends the shell
# with its own status.
block() { say_block "$@" || true; exit 2; }
# shellcheck disable=SC2317,SC2329  # invoked only by the EXIT trap
on_exit() {
  local rc=$?
  if (( rc != 0 && rc != 2 )); then
    say_block "internal error (exit $rc)" || true
    exit 2
  fi
}
trap on_exit EXIT

# Read stdin once, before any exit, so Claude Code never writes a large input into a closed pipe.
input="$(cat)"

root="${CLAUDE_PROJECT_DIR:-}"
if [[ -z "$root" ]]; then
  root="$(git rev-parse --show-toplevel 2>/dev/null)" || block "cannot determine the repository root"
fi
[[ -n "$root" && -d "$root" ]] || block "cannot determine the repository root"

active_file="$root/.claude/active-task"
if [[ ! -e "$active_file" && ! -L "$active_file" ]]; then
  exit 0
fi
hint="If no /run-task is in progress (it ended, crashed, or the branch changed), .claude/active-task is stale. Only the user deletes a stale file; the run-task orchestrator deletes it only at handoff. Do not delete it to get past this block: report this message (implementers: SCOPE_BLOCKED)."

[[ -f "$active_file" ]] || block ".claude/active-task is not a regular file"
task="$(tr -d '[:space:]' < "$active_file")"
[[ -n "$task" ]] || block ".claude/active-task is empty"
[[ -f "$root/$task" ]] || block "active task spec not found: $task (named in .claude/active-task)"

command -v jq >/dev/null 2>&1 || block "jq is not installed (it is required while a task is active)"
# jq 1.6 accepts empty input, so reject empty or whitespace-only stdin explicitly.
[[ "$input" == *[![:space:]]* ]] || block "stdin is empty"
printf '%s' "$input" | jq -e -s 'length == 1 and (.[0] | type) == "object"' >/dev/null 2>&1 \
  || block "stdin is not a single JSON object"

file="$(printf '%s' "$input" | jq -r '[.tool_input.file_path, .tool_input.notebook_path] | map(select(. != null))
  | if length == 1 and (.[0] | type) == "string" and (.[0] | startswith("/")) and (.[0] | explode | all(. >= 32))
    then .[0] else empty end')" \
  || block "jq failed to read the tool input"
[[ -n "$file" ]] \
  || block "the tool input has no usable path (exactly one of file_path or notebook_path, absolute, without control characters)"

task_abs="$(realpath -m "$root/$task")"
file_abs="$(realpath -m "$file")"
if [[ "$file_abs" == "$task_abs" ]]; then
  cat >&2 <<MSG
scope-guard: blocked edit to the active task spec '$task': it is read-only during a task.
Status and Review log are updated by the orchestrator with scripts/agentic/task-meta.sh. If the Allowed paths are
wrong, stop and report "SCOPE_BLOCKED: <file> — <why>" so the planner can amend the spec.
$hint
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
$hint
MSG
exit 2
