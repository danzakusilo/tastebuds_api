#!/usr/bin/env bash
# Orchestrator-only edits of an active task spec (the scope-guard hook blocks file-tool edits to it).
#
#   task-meta.sh status <task-file> <STATUS>   STATUS is one of DRAFT READY IN_PROGRESS IN_REVIEW HANDOFF DONE
#   task-meta.sh log    <task-file> <file|->   append the file (or stdin) to the end of the spec, under "## Review log"
#
# Touches only the "- **Status:**" line (status) or the end of the file (log). Never any other line.
set -euo pipefail

die() { echo "task-meta: $*" >&2; exit 2; }

cmd="${1:-}"; task="${2:-}"; arg="${3:-}"
[[ -n "$cmd" && -n "$task" && -n "$arg" ]] || { sed -n '2,6p' "$0" >&2; exit 2; }

root="$(git rev-parse --show-toplevel)" || die "not in a git repository"
[[ -f "$task" ]] || die "task file not found: $task"
rel="$(realpath -m --relative-to="$root" "$task")"
[[ "$rel" == docs/tasks/* && "$rel" != docs/tasks/*/* ]] || die "not inside docs/tasks/: $rel"
case "$(basename "$rel")" in
  TEMPLATE.md | README.md) die "not a task spec: $rel" ;;
esac
file="$root/$rel"

[[ "$(grep -c '^- \*\*Status:\*\* ' "$file" || true)" == 1 ]] || die "spec must contain exactly one Status line"
last_heading="$(grep '^## ' "$file" | tail -n 1 || true)"
[[ "$last_heading" == "## Review log" ]] || die "'## Review log' must be the last '## ' heading"

tmp="$(mktemp "$(dirname "$file")/.task-meta.XXXXXX")"
trap 'rm -f "$tmp"' EXIT

case "$cmd" in
  status)
    case "$arg" in
      DRAFT | READY | IN_PROGRESS | IN_REVIEW | HANDOFF | DONE) ;;
      *) die "invalid status '$arg'" ;;
    esac
    awk -v s="$arg" '/^- \*\*Status:\*\* /{print "- **Status:** " s; next} {print}' "$file" > "$tmp"
    ;;
  log)
    if [[ "$arg" != "-" ]]; then
      [[ -f "$arg" ]] || die "log file not found: $arg"
    fi
    { cat "$file"; echo; cat "$arg"; } > "$tmp"
    ;;
  *) die "unknown command '$cmd'" ;;
esac
chmod --reference="$file" "$tmp"
mv "$tmp" "$file"
