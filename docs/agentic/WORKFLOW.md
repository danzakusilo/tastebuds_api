# Agentic development workflow

**Goal:** every agent-made change is planned by a strong model, written by a cheaper one, and reviewed again by a
strong one, with scope enforced by tooling rather than by trust. A human sees only reviewed, green, in-scope changes.

```
 request / backlog item / PLAN phase
            │
            ▼
   ┌──────────────────┐   docs/tasks/NNN-slug.md (READY)
   │ planner  (Opus)  │──────────────────────────────┐
   └──────────────────┘                              │
                                                     ▼
            ┌──────────────── run-task orchestrator (max 3 rounds) ────────────────┐
            │  .claude/active-task = spec  →  scope guard active for every agent   │
            │                                                                      │
            │   ┌───────────────────────┐  report   ┌───────────────────────┐      │
            │   │ implementer (Sonnet)  │──────────▶│ reviewer (Opus)       │      │
            │   │ edits allowed paths   │◀──────────│ APPROVE / CHANGES_REQ │      │
            │   └───────────────────────┘ findings  └───────────────────────┘      │
            └──────────────────────────────────────────────────────────────────────┘
                                                     │
                                                     ▼
                                     human handoff (diff, evidence, commit msg)
                                                     │  human opens the PR
                                                     ▼
                              /brief-pr: explainer (Opus) → reality check + review brief
```

## Artifacts
| Path | Purpose |
|---|---|
| `docs/tasks/NNN-*.md` | Task spec: goal, non-goals, **allowed paths**, design notes, acceptance criteria, required tests, gates, review log |
| `docs/backlog/*.md` | Out-of-scope findings. Never fixed in place |
| `.claude/agents/{planner,implementer,reviewer,explainer}.md` | Agent roles, models, tool limits |
| `.claude/skills/{plan-task,run-task,brief-pr}/SKILL.md` | Orchestration entry points (`/plan-task`, `/run-task`, `/brief-pr`) |
| `build/pr-brief/pr-N.md` | (git-ignored) Review brief of PR N from `/brief-pr`: over-engineering check, main ideas traced across files |
| `.claude/hooks/scope-guard.sh` | PreToolUse hook: blocks Edit/Write outside the active task's allowed paths |
| `scripts/agentic/scope.sh` | Same rules over a git diff; used by the reviewer and CI |
| `scripts/agentic/task-meta.sh` | Orchestrator-only edits of the active spec's Status and Review log (the hook blocks file-tool edits to it) |
| `scripts/agentic/scope_test.sh` | Tests for `scope.sh`, `task-meta.sh` and the hook; run in CI (`agentic-scripts` job) |
| `.claude/active-task` | (git-ignored) Path of the spec currently being worked on |

## Scope enforcement, in layers
1. **Spec:** non-goals and an explicit allow-list of paths.
2. **Hook:** edits through Claude's file tools outside the allow-list are rejected with instructions to file a backlog item.
   The active spec is read-only, and the hook fails closed (blocks) on its own errors.
3. **Diff check:** `scope.sh diff` catches changes made by any other means (e.g. shell). The reviewer must run it.
   It fails on git errors and flags any change to the spec's Allowed paths since the merge base.
4. **Reviewer:** catches in-scope-path but out-of-intent changes (drive-by refactors, reformatting untouched code).
5. **CI:** on branches named `task/NNN-*`, the `scope` job runs `scope.sh diff` against `main` and fails the PR on violations.

An amended Allowed paths section must be merged to `main` before the task branch is re-run (rebase), because CI
compares against the merge base.

## Why these models
- Planning and review are where judgement matters and mistakes compound, so they get the strongest model (Opus).
- The explainer judges over-engineering and explains a change to a human, so it also gets Opus. It runs in a fresh
  context, so it sees the change without the loop's history.
- Implementation of a well-specified task is mostly execution, so a cheaper model (Sonnet) is enough and keeps cost down.
- To try Haiku as implementer, change `model:` in `.claude/agents/implementer.md`. Track approval-in-round-1 rate
  in the task review logs to compare.

## Quality gates (same locally and in CI)
`./gradlew check` = compile (warnings are errors) + ktlint + detekt + unit/integration tests (Testcontainers) +
ArchUnit + Spring Modulith boundary verification + Kover coverage floor (70% global; task specs set ≥80% for new code).

## Human checkpoints
- Approving a planner spec (READY) when it raised open questions.
- Handoff: reading the diff and evidence, then committing / opening the PR.
- After the PR is open: `/brief-pr <PR#>`, then deciding on its reality-check findings (simplify, backlog or keep)
  and whether to post the brief as a PR comment.
- Anything that changes `docs/PLAN.md` decisions.
