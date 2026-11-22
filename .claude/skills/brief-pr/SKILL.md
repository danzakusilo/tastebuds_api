---
name: brief-pr
description: After a task is finalized and its PR is open, reality-check the change for over-engineering and write a short review brief for one reader, with a concept card for every concept that reader lacks (explainer agent, Opus), checked by a cold reader, and followed by teach-back questions. Use when the user says "brief PR <n>" or "explain PR <n>", or invokes /brief-pr <pr-number | task-id> [--reader <known-list path>].
---

# brief-pr: reality check, review brief and teach-back for an open PR

Arguments: a PR number (`7`, `#7`) or a task ID (`007`, three digits) or spec path, and optionally
`--reader <path>`, a known-list for someone other than the owner. A task ID resolves to the PR of branch
`task/<NNN>-*` (`gh pr list --head <branch>`).

This skill never changes the PR, the branch or tracked files. It writes git-ignored files only: the brief, card
drafts, and the reader's known-list.

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
7. Known-list: the `--reader` path if given. Otherwise `.claude/reader.local.md`; if that file is missing, create it
   as a copy of `docs/concepts/reader-seed.md` and tell the user to correct its baseline before the next brief
   (what is listed there is never explained).
8. `mkdir -p build/pr-brief/cards`. Brief: `build/pr-brief/pr-<n>.md`. Card drafts: `build/pr-brief/cards/`
   (both git-ignored; `./gradlew clean` deletes them).

## 2. Run the explainer
Spawn the `explainer` agent with: PR number, URL, title, base branch, head SHA, CI status, spec path (or "none"),
known-list path, source mode, blob URL base `https://github.com/<owner>/<repo>/blob/<sha>/`, the brief path and the
cards directory. Its agent file is already its system prompt: do not tell it to read `.claude/agents/explainer.md`.
Wait for its reply.

## 3. Check the brief
Checks of your own:
- Every file in the PR's `files` appears in the brief's file list.
- Blob links use the head SHA, not a branch name.
- No token, key, real IP address or other personal data. The repo is public and the brief may be posted to it.
- "In three sentences" has no backticks.
- Each card draft in `build/pr-brief/cards/` has the eight headings of `docs/concepts/TEMPLATE.md`, in order.

Then the cold reader. Spawn the `cold-reader` agent with the brief path, the known-list path and the paths of every
card the brief links or embeds (`docs/concepts/*.md` and the drafts). It gets nothing else.

If one of your checks fails or the cold reader answers `FAIL`, send the explainer the failures with SendMessage, then
run the cold reader again. Two rounds at most. What still fails after that goes to the user, at the top of the
report: "still unexplained: <terms>".

## 4. Report to the user, in this order
1. ❗ likely bugs or spec mismatches, if any. These outrank everything else.
2. Over the concept budget, or more than three decisions: say so, with the explainer's proposed split. The user
   decides whether to split now or to take the PR as it is.
3. Reality check verdict and findings, each with origin and recommendation. The user decides what happens next:
   - `simplify before merge` → only if the user agrees: a new implementer round on the task branch with the finding.
   - `backlog` → only if the user agrees: a `docs/backlog/` file, committed on its own branch, not on the PR branch.
   - findings and gaps with origin `spec`: name the `docs/agentic/SPEC_CHECKLIST.md` rule that should have caught
     each one, or propose a candidate line (a promotion to a rule if a candidate already matches). Change the
     checklist only after a yes, on its own branch, not on the PR branch.
4. The decision titles, the new concepts, and the brief's absolute path. Ask the user to read the brief, then to
   say "ready".

## 5. Teach-back (skip with `--reader`)
The point is that the user can explain the change without the brief. So:
- Ask the explainer's questions one at a time. The user answers in their own words, two or three sentences.
- Judge each answer against the points the explainer listed, the card and the code. Reply in one of three ways:
  - **Right:** say so in one line. For a card question, add `- <card slug> (<YYYY-MM-DD>)` to
    "Known (cards passed)" in `.claude/reader.local.md`.
  - **Partly:** name what is missing in one or two plain sentences, without giving the full answer, and ask once
    more.
  - **Not yet:** the explanation failed, not the user. Ask which word or step lost them. Add
    `- <term>: <what was tried>` to "Did not land", send the explainer that term with SendMessage for a second
    explanation by another route, and show the user the new card.
- Do not lecture and do not answer for the user. Never mark a card passed without a right answer.
- If the user says a term is not known to them after all, move it out of the baseline. If they say they knew a new
  concept already, add it to the baseline.

## 6. Offer, and do only after a yes
- Post the brief as a PR comment: `gh pr comment <n> --body-file build/pr-brief/pr-<n>.md`.
- After the PR is merged: commit the card drafts to `docs/concepts/` with a row each in its README index, on their
  own branch from `main` (`docs/concepts-pr-<n>`), never on the PR branch (the task-scope CI check would fail).
- If an Artifact tool is available, publish the brief as a private page for reading.

## Notes
- The explainer does not run `./gradlew check`; the reviewer and CI already did. It reads the CI status only.
- The cold reader runs on a cheaper model (Sonnet) and sees only the text and the known-list. That is the point: it
  cannot fill a gap from the code.
- The brief is a review aid, not a gate. It never blocks a merge and never edits the PR.
