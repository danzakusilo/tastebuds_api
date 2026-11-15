#!/usr/bin/env bash
# Task scope checks shared by the PreToolUse hook, the reviewer agent and CI.
#
#   scope.sh allowed <task-file> <path>    exit 0 if <path> may be changed under <task-file>, 1 otherwise
#   scope.sh diff    <task-file> [base]    list changed files (vs base, default origin/main) outside the scope; exit 1 if any
#
# Allowed paths come from the task's "## Allowed paths" section, one "- `glob`" per line. In globs `*` also matches `/`,
# so `services/core/src/main/kotlin/**/catalog/**` covers everything below catalog/.
# Always allowed: the task file itself and docs/backlog/** (where out-of-scope findings are recorded).
set -euo pipefail

root="$(git rev-parse --show-toplevel)"

patterns() {
  awk '/^## Allowed paths/{f=1;next} /^## /{f=0} f && /^- `/{sub(/^- `/,""); sub(/`.*$/,""); print}' "$1"
}

is_allowed() {
  local task_rel="$1" path="$2" pattern
  [[ "$path" == "$task_rel" || "$path" == docs/backlog/* ]] && return 0
  while IFS= read -r pattern; do
    [[ -n "$pattern" ]] || continue
    # shellcheck disable=SC2053  # unquoted on purpose: glob match
    [[ "$path" == $pattern ]] && return 0
  done < <(patterns "$root/$task_rel")
  return 1
}

cmd="${1:-}"; task="${2:-}"
[[ -n "$cmd" && -n "$task" ]] || { sed -n '2,9p' "$0" >&2; exit 2; }
task_rel="$(realpath -m --relative-to="$root" "$task")"
[[ -f "$root/$task_rel" ]] || { echo "scope: task file not found: $task_rel" >&2; exit 2; }

case "$cmd" in
  allowed)
    path="$(realpath -m --relative-to="$root" "${3:?path required}")"
    [[ "$path" != ../* ]] && is_allowed "$task_rel" "$path"
    ;;
  diff)
    base="${3:-origin/main}"
    violations=0
    while IFS= read -r changed; do
      [[ -n "$changed" ]] || continue
      if ! is_allowed "$task_rel" "$changed"; then
        echo "OUT OF SCOPE: $changed"
        violations=$((violations + 1))
      fi
    done < <({ git -C "$root" diff --name-only "$base"...HEAD 2>/dev/null || true
               git -C "$root" diff --name-only HEAD
               git -C "$root" ls-files --others --exclude-standard; } | sort -u)
    if (( violations > 0 )); then
      echo "$violations file(s) outside the allowed paths of $task_rel" >&2
      exit 1
    fi
    echo "scope: all changes within $task_rel"
    ;;
  *) echo "scope: unknown command '$cmd'" >&2; exit 2 ;;
esac
