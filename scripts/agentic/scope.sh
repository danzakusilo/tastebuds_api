#!/usr/bin/env bash
# Task scope checks shared by the PreToolUse hook, the reviewer agent and CI.
#
#   scope.sh allowed <task-file> <path>    exit 0 if <path> may be changed under <task-file>, 1 if denied, 2 on error
#   scope.sh diff    <task-file> [base]    list files changed since the merge base with base (default origin/main)
#                                          outside the scope; exit 1 if any, exit 2 if git fails
#
# `diff` reports both paths of a rename. Allowed paths come from the first "## Allowed paths" section only, one
# "- `glob`" per line. In globs `*` also matches `/`,
# so `services/core/src/main/kotlin/**/catalog/**` covers everything below catalog/.
# Always allowed: docs/backlog/** (where out-of-scope findings are recorded).
# The task spec itself is never allowed for `allowed` (it is read-only during a task; the orchestrator edits Status and
# Review log with task-meta.sh). For `diff` the spec may change, except its "## Allowed paths" section, which must be
# identical to the version at the merge base (a spec that is new on the branch is not compared).
set -euo pipefail

root="$(git rev-parse --show-toplevel)"

patterns() {
  # First "## Allowed paths" section only; no early exit, so `git show | patterns` never gets SIGPIPE under pipefail.
  awk '/^## Allowed paths/{f=!seen; seen=1; next} /^## /{f=0} f && /^- `/{sub(/^- `/,""); sub(/`.*$/,""); print}'
}

is_allowed() {
  local task_rel="$1" path="$2" pattern
  [[ "$path" == "$task_rel" ]] && return 1
  [[ "$path" == docs/backlog/* ]] && return 0
  while IFS= read -r pattern; do
    [[ -n "$pattern" ]] || continue
    # shellcheck disable=SC2053  # unquoted on purpose: glob match
    [[ "$path" == $pattern ]] && return 0
  done < <(patterns < "$root/$task_rel")
  return 1
}

cmd="${1:-}"; task="${2:-}"
[[ -n "$cmd" && -n "$task" ]] || { sed -n '2,6p' "$0" >&2; exit 2; }
task_rel="$(realpath -m --relative-to="$root" "$task")"
[[ -f "$root/$task_rel" ]] || { echo "scope: task file not found: $task_rel" >&2; exit 2; }

case "$cmd" in
  allowed)
    path="$(realpath -m --relative-to="$root" "${3:?path required}")"
    [[ "$path" != ../* ]] && is_allowed "$task_rel" "$path"
    ;;
  diff)
    base="${3:-origin/main}"
    merge_base="$(git -C "$root" merge-base "$base" HEAD)" \
      || { echo "scope: cannot determine merge base of '$base' and HEAD" >&2; exit 2; }
    # Each listing is its own assignment: in a command group only the last command's status would count.
    # --no-renames lists a rename as the deleted old path plus the added new path, so moving a file out of a
    # protected path is reported.
    committed="$(git -C "$root" diff --name-only --no-renames "$merge_base" HEAD)" \
      || { echo "scope: git failed while listing committed changes" >&2; exit 2; }
    uncommitted="$(git -C "$root" diff --name-only --no-renames HEAD)" \
      || { echo "scope: git failed while listing uncommitted changes" >&2; exit 2; }
    untracked="$(git -C "$root" ls-files --others --exclude-standard)" \
      || { echo "scope: git failed while listing untracked files" >&2; exit 2; }
    changes="$committed"$'\n'"$uncommitted"$'\n'"$untracked"
    violations=0
    while IFS= read -r changed; do
      [[ -n "$changed" ]] || continue
      if [[ "$changed" == "$task_rel" ]]; then
        if git -C "$root" cat-file -e "$merge_base:$task_rel" 2>/dev/null; then
          old="$(git -C "$root" show "$merge_base:$task_rel" | patterns)" \
            || { echo "scope: git failed while reading the spec at the merge base" >&2; exit 2; }
          new="$(patterns < "$root/$task_rel")"
          if [[ "$old" != "$new" ]]; then
            echo "OUT OF SCOPE: $task_rel (Allowed paths changed since $(git -C "$root" rev-parse --short "$merge_base"))"
            violations=$((violations + 1))
          fi
        fi
      elif ! is_allowed "$task_rel" "$changed"; then
        echo "OUT OF SCOPE: $changed"
        violations=$((violations + 1))
      fi
    done < <(sort -u <<< "$changes")
    if (( violations > 0 )); then
      echo "$violations file(s) outside the allowed paths of $task_rel" >&2
      exit 1
    fi
    echo "scope: all changes within $task_rel"
    ;;
  *) echo "scope: unknown command '$cmd'" >&2; exit 2 ;;
esac
