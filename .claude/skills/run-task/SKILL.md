---
name: run-task
description: Run a READY task spec through the implement → review loop (Sonnet implementer, Opus reviewer, max 3 rounds) and hand off to the human. Use when the user says "run task NNN" or invokes /run-task <path-or-id>.
---

# run-task: orchestrate implement → review → handoff

Argument: a task spec path or ID (e.g. `001` → `docs/tasks/001-*.md`).

## 1. Preflight
- Resolve the spec file. Its status must be `READY` (or `IN_REVIEW` when resuming). Otherwise stop and say why.
- Working tree must be clean (`git status --porcelain` empty). Otherwise stop and ask the user.
- Create and switch to branch `task/<NNN>-<slug>` from `main` (reuse it if resuming).
- Write the spec path (repo-relative) to `.claude/active-task`. From now on the scope guard applies to every agent,
  including you. Set the spec status with `scripts/agentic/task-meta.sh status <spec> IN_PROGRESS` (the hook blocks
  file-tool edits to the active spec, so use this script for every status and log change).

## 2. Loop (round = 1..3)
1. Spawn the `implementer` agent with: the spec path, the round number, and (round ≥ 2) the reviewer's numbered
   findings verbatim. Wait for its report.
2. If the report contains `SPEC_PROBLEM` or `SCOPE_BLOCKED`, stop the loop and go to Handoff with that as the reason.
3. Run `scripts/agentic/task-meta.sh status <spec> IN_REVIEW`. Spawn the `reviewer` agent with: spec path, round number, implementer report.
4. Append to the spec's `## Review log` by piping the text via stdin from a Bash heredoc:
   `scripts/agentic/task-meta.sh log <spec> - <<'EOF' … EOF`, where the text is `### Round N: <VERDICT>` followed by
   the findings (or "none") and the reviewer's "Spec gaps" (if any). Do not write a temp file with Write/Edit: the
   hook blocks file-tool edits outside the repo. Create `docs/backlog/` files for any "Backlog suggestions".
5. `APPROVE` → Handoff. `CHANGES_REQUESTED` → next round. `SPEC_PROBLEM` → Handoff.
6. After round 3 without approval → Handoff with the open findings.

Do not edit production code yourself during the loop: the implementer owns code changes and the reviewer owns
judgement. Your edits are limited to the spec's status and review log (via `task-meta.sh`), and backlog files.

## 3. Handoff
- Delete `.claude/active-task`.
- Set status `HANDOFF` with `task-meta.sh status` (approved) or leave `IN_REVIEW` with a note (not approved).
- Report to the user:
  - verdict and number of rounds
  - files changed (`git diff --stat main...HEAD` plus untracked)
  - acceptance criteria → evidence (from the final review)
  - `./gradlew check` result and coverage of the changed module
  - backlog items created
  - spec gaps from the reviews and implementer deviations caused by a wrong spec: name the
    `docs/agentic/SPEC_CHECKLIST.md` rule that should have caught each one, or propose a candidate line (a promotion if
    a candidate already matches). Change the checklist only after a yes, on its own branch, never on the task branch.
  - a suggested commit message (Conventional Commits, referencing the task ID)
- Do not commit, push or open a PR unless the user asks.
- Once the PR is open, suggest `/brief-pr <PR#>` (reality check and review brief).
