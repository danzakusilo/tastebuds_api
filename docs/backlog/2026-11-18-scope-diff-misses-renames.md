# scope.sh diff does not see the source path of a rename
- **Found during:** 005 (review round 2)
- **Location:** scripts/agentic/scope.sh (`diff` subcommand, the two `git diff --name-only` calls)
- **Severity:** security
- **What:** With rename detection on (git's default), `git diff --name-only` lists only the new path. `git mv
  protected/Security.kt src/a/Security.kt` (staged or committed) is reported as "all changes within" when `src/a/**`
  is allowed. A plain deletion is caught. Pre-existing on `main`; task 005 prescribed the same commands.
- **Why it matters:** The diff check is the last scope layer (reviewer and CI); a rename moves a protected file out of
  its place without a violation.
- **Suggested fix:** Pass `--no-renames` to both `git diff` calls and add a `scope_test.sh` case for a rename out of a
  protected path.
