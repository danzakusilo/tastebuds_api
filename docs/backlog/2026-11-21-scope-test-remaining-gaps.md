# scope_test.sh: two script branches still have no pinning case
- **Found during:** 015 (review round 1, extra mutants)
- **Location:** scripts/agentic/scope_test.sh; scripts/agentic/scope.sh (`patterns` awk); scripts/agentic/task-meta.sh:55
- **Severity:** low
- **What:** Two mutants survive all cases: (1) an early `exit` in the first-section-only awk of `scope.sh patterns`
  (no case has a spec at the merge base larger than a pipe buffer, about 64 KiB, so SIGPIPE never shows up); (2) the
  `grep` failure branch (rc >= 2) in `task-meta.sh log`. (1) fails closed (a false exit 2); (2) would fail open (the
  text is appended unchecked), but a grep failure there is close to unreachable, because `entry` was written one line
  earlier.
- **Why it matters:** A later edit could reintroduce the early exit and turn large specs into false scope failures, or
  weaken the grep branch so that a review-log entry can change the spec's structure without a test noticing.
- **Suggested fix:** Add a `diff` case whose spec at the merge base is larger than 64 KiB; test the grep branch with the
  fake-command pattern the suite already uses for git and jq.
