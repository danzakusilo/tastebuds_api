# scope.sh diff defaults to origin/main, which can lag local main
- **Found during:** 002 (run-task round 1)
- **Location:** scripts/agentic/scope.sh:40
- **Severity:** medium
- **What:** `diff` compares against `origin/main` unless a base is passed. When local `main` has unpushed commits
  (true right now: two commits ahead), every file from those commits is reported out of scope (70 false positives in 002).
- **Why it matters:** Noise trains agents and humans to ignore the scope check; the run-task orchestrator had to pass
  `main` by hand. Task 005 makes git failures fatal but does not change the default.
- **Suggested fix:** Default to the merge base with local `main` (the branch run-task creates from), falling back to
  `origin/main` only in CI where local `main` may not exist; have run-task pass the base explicitly. Fold into 005 if it
  hasn't run yet.
