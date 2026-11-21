---
name: brief-pr
description: After a task is finalized and its PR is open, reality-check the change for over-engineering and write a review brief that traces its main ideas across files in plain language for a peer from another field (explainer agent, Opus). Use when the user says "brief PR <n>" or "explain PR <n>", or invokes /brief-pr <pr-number | task-id> [--for "<audience>"].
---

# brief-pr: reality check and review brief for an open PR

Arguments: a PR number (`7`, `#7`) or a task ID (`007`, three digits) or spec path, and optionally
`--for "<audience>"`. A task ID resolves to the PR of branch `task/<NNN>-*` (`gh pr list --head <branch>`).

This skill never changes the PR, the branch or the working tree. It reads, writes one git-ignored file, and reports.

## 1. Preflight (stop and say why on failure)
1. PR metadata: `gh pr view <n> --json number,title,url,state,isDraft,headRefName,baseRefName,body,files`, and the
   head SHA: `gh api repos/{owner}/{repo}/pulls/<n> --jq .head.sha`.
2. State must be `OPEN` and not draft. For a draft or merged PR, ask the user whether to continue.
3. `.claude/active-task` must be absent: a task that is still running is not in PR state.
4. Task spec: from a `task/<NNN>-*` head branch, `docs/tasks/<NNN>-*.md` at the head SHA. Its status should be
   `HANDOFF` or `DONE`; otherwise ask the user whether to continue. A PR without a task branch has no spec; the
   explainer then uses the PR description as the statement of intent.
5. Make the head readable without touching the working tree: `git fetch origin <headRefName>` (never checkout or
   switch), then `git cat-file -e <sha>`. Source mode is `worktree` when `git rev-parse HEAD` equals the SHA and
   `git status --porcelain` is empty; otherwise `git`.
6. CI status from `gh pr checks <n>`: passing, failing (with job names) or pending.
7. Audience: the `--for` value if given. Otherwise from the changed paths: main code under `services/` or `libs/` →
   "senior android developer"; only build, CI or `deploy/` → "senior application developer who does not do
   DevOps"; only docs or agentic tooling → "senior engineer who is new to this repository".
8. `mkdir -p build/pr-brief`. Output path: `build/pr-brief/pr-<n>.md` (git-ignored; `./gradlew clean` deletes it).

## 2. Run the explainer
Spawn the `explainer` agent with: PR number, URL, title, base branch, head SHA, CI status, spec path (or "none"),
audience, source mode, blob URL base `https://github.com/<owner>/<repo>/blob/<sha>/`, and the output path. Tell it to
follow `.claude/agents/explainer.md`. Wait for its reply.

## 3. Check the brief
- Every file in the PR's `files` appears in the File map.
- Blob links use the head SHA, not a branch name.
- No token, key, real IP address or other personal data. The repo is public and the brief may be posted to it.
If a check fails, send the explainer the failure with SendMessage once; if it still fails, report it to the user.

## 4. Report to the user, in this order
1. ❗ likely bugs or spec mismatches, if any. These outrank everything else.
2. Reality check verdict and findings, each with origin and recommendation. The user decides what happens next:
   - `simplify before merge` → only if the user agrees: a new implementer round on the task branch with the finding.
   - `backlog` → only if the user agrees: a `docs/backlog/` file, committed on its own branch, not on the PR branch.
   - findings with origin `spec` are also a hint for the planner prompt; mention the pattern if it repeats.
3. The main idea titles and the brief's absolute path.
4. Offer, and do only after a yes:
   - post the brief as a PR comment: `gh pr comment <n> --body-file build/pr-brief/pr-<n>.md`;
   - if an Artifact tool is available, publish the brief as a private page for reading.

## Notes
- The explainer does not run `./gradlew check`; the reviewer and CI already did. It reads the CI status only.
- The brief is a review aid, not a gate. It never blocks a merge and never edits the PR.
