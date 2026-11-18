# .gitignore `build/` also ignores docs/build/
- **Found during:** 005 (review round 2)
- **Location:** .gitignore:3
- **Severity:** medium
- **What:** The unanchored `build/` pattern matches `docs/build/`, so `docs/build/DEPENDENCY_LOCKING.md` is invisible
  to `git status`, `git add -A` and `scope.sh diff`. Task 005 force-adds the file; later new files in that folder
  would still be silently skipped.
- **Why it matters:** Docs referenced from build scripts may never be committed; scope checks cannot see them.
- **Suggested fix:** Anchor the pattern (`/build/` plus `**/build/` for modules) with `!/docs/build/`, or move the doc.
