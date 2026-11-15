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
```

## Artifacts
| Path | Purpose |
|---|---|
| `docs/tasks/NNN-*.md` | Task spec: goal, non-goals, **allowed paths**, design notes, acceptance criteria, required tests, gates, review log |
| `docs/backlog/*.md` | Out-of-scope findings. Never fixed in place |
| `.claude/agents/{planner,implementer,reviewer}.md` | Agent roles, models, tool limits |
| `.claude/skills/{plan-task,run-task}/SKILL.md` | Orchestration entry points (`/plan-task`, `/run-task`) |
| `.claude/hooks/scope-guard.sh` | PreToolUse hook: blocks Edit/Write outside the active task's allowed paths |
| `scripts/agentic/scope.sh` | Same rules over a git diff; used by the reviewer and CI |
| `.claude/active-task` | (git-ignored) Path of the spec currently being worked on |

## Scope enforcement, in layers
1. **Spec:** non-goals and an explicit allow-list of paths.
2. **Hook:** edits through Claude's file tools outside the allow-list are rejected with instructions to file a backlog item.
3. **Diff check:** `scope.sh diff` catches changes made by any other means (e.g. shell). The reviewer must run it.
4. **Reviewer:** catches in-scope-path but out-of-intent changes (drive-by refactors, reformatting untouched code).
5. **CI:** on branches named `task/NNN-*`, the `scope` job runs `scope.sh diff` against `main` and fails the PR on violations.

## Why these models
- Planning and review are where judgement matters and mistakes compound, so they get the strongest model (Opus).
- Implementation of a well-specified task is mostly execution, so a cheaper model (Sonnet) is enough and keeps cost down.
- To try Haiku as implementer, change `model:` in `.claude/agents/implementer.md`. Track approval-in-round-1 rate
  in the task review logs to compare.

## Quality gates (same locally and in CI)
`./gradlew check` = compile (warnings are errors) + ktlint + detekt + unit/integration tests (Testcontainers) +
ArchUnit + Spring Modulith boundary verification + Kover coverage floor (70% global; task specs set ≥80% for new code).

## Human checkpoints
- Approving a planner spec (READY) when it raised open questions.
- Handoff: reading the diff and evidence, then committing / opening the PR.
- Anything that changes `docs/PLAN.md` decisions.
