# CLAUDE.md layout does not mention `docs/deploy/`
- **Found during:** 008 (run-task round 1 review)
- **Location:** CLAUDE.md (Layout section)
- **Severity:** low
- **What:** Task 008 adds `docs/deploy/DATABASE.md` (role contract, `migrate` command, env vars); the Layout line lists
  the docs folders without it.
- **Why it matters:** Agents look in CLAUDE.md first and may miss the role and migration contract.
- **Suggested fix:** Add `docs/deploy/` to the docs list; after tasks 009–011, also add the `scripts/k8s/*.sh` commands.
