# scope_test.sh: cases that pass with the rule removed, and a broad shellcheck disable
- **Found during:** 005 (review round 3, mutation check)
- **Location:** scripts/agentic/scope_test.sh (fixture spec, line 5)
- **Severity:** low
- **What:** (1) "backlog is always allowed" and "path outside the repo is denied" pass even when those rules are
  deleted from scope.sh, because the fixture's `docs/**` / `src/a/**` globs already give the same answer.
  (2) No case drives the hook's "scope.sh exits ≥ 2 → block" branch on its own. (3) A file-level
  `# shellcheck disable=SC2317` hides real unreachable code anywhere in the file; only five helpers need it.
- **Why it matters:** Regressions in those rules or branches would go unnoticed by CI.
- **Suggested fix:** A second fixture spec without `docs/**` and with a leading-wildcard glob (e.g. `**/x/**`); a hook
  case with a non-executable or fake `scope.sh`; per-function SC2317 directives on `out_has`, `out_lacks`, `scope`,
  `scope_fake_git`, `hook` (verified to pass).
