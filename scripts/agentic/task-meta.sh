#!/usr/bin/env bash
# Orchestrator-only edits of an active task spec (the scope-guard hook blocks file-tool edits to it).
#
#   task-meta.sh status <task-file> <STATUS>   STATUS is one of DRAFT READY IN_PROGRESS IN_REVIEW HANDOFF DONE
#   task-meta.sh log    <task-file> <file|->   append the file (or stdin) to the end of the spec, under "## Review log"
#
# log rejects text with a line that starts with "## " or "- **Status:** " (exit 2, spec unchanged): scope.sh and this
# script would read such a line as spec structure.
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

entry=""
tmp="$(mktemp "$(dirname "$file")/.task-meta.XXXXXX")"
trap 'rm -f -- "$tmp" ${entry:+"$entry"}' EXIT

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
    entry="$(mktemp)"
    cat -- "$arg" > "$entry"
    # The line patterns scope.sh and this script read as structure: "## " headings and the Status line.
    # If a parser pattern changes (scope.sh patterns, the checks above), change this regex with it.
    rc=0
    bad="$(grep -n -m 1 -E '^(## |- \*\*Status:\*\* )' "$entry")" || rc=$?
    case "$rc" in
      0) die "log text line ${bad%%:*} starts with '## ' or '- **Status:** ', which would change the spec structure; indent or reword it" ;;
      1) ;;
      *) die "grep failed while checking the log text" ;;
    esac
    { cat "$file"; echo; cat "$entry"; } > "$tmp"
    ;;
  *) die "unknown command '$cmd'" ;;
esac
chmod --reference="$file" "$tmp"
mv "$tmp" "$file"
