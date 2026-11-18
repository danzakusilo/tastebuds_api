# Agentic scripts: GNU-only tools and small task-meta.sh edge cases
- **Found during:** 005 (review round 2)
- **Location:** .claude/hooks/scope-guard.sh, scripts/agentic/scope.sh, scripts/agentic/task-meta.sh
- **Severity:** low
- **What:** (1) `realpath -m --relative-to` and `chmod --reference` are GNU coreutils; on macOS the fail-closed hook
  would block every edit during a task. (2) `task-meta.sh log` adds no blank separator when the spec lacks a trailing
  newline. (3) `task-meta.sh` fails closed when run from a subdirectory with a repo-relative path.
- **Why it matters:** Contributors on macOS cannot run tasks; minor formatting/usability rough edges.
- **Suggested fix:** Document the GNU coreutils requirement (or detect `grealpath`); normalise the trailing newline;
  resolve paths against the repo root.
