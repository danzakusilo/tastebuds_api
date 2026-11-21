# NNN — <imperative title>

- **Status:** DRAFT | READY | IN_PROGRESS | IN_REVIEW | HANDOFF | DONE
- **Phase:** <0–5, see docs/PLAN.md §11>
- **Planned by:** <model> on <YYYY-MM-DD>
- **Depends on:** <task ids or "none">

## Goal
<One paragraph: the outcome and why it matters. Link the PLAN.md section it implements.>

## Non-goals
<Things a reasonable implementer might be tempted to do, explicitly out of scope.>
- ...

## Allowed paths
<!-- One glob per line in backticks. `*` also matches `/`. The task file and docs/backlog/** are always allowed.
     Anything not listed is blocked by .claude/hooks/scope-guard.sh and flagged by scripts/agentic/scope.sh. -->
- `services/core/src/main/kotlin/dev/danya/tastebuds/core/<module>/**`
- `services/core/src/test/kotlin/dev/danya/tastebuds/core/<module>/**`

## Design notes
<Interfaces, data shapes, key decisions already made by the planner, so the implementer doesn't have to make them.
 Include signatures for public types, table DDL, event schemas, config keys.>

## Assumptions verified
<!-- docs/agentic/SPEC_CHECKLIST.md rule 1. Each claim about tool, library or platform behaviour the design relies on,
     and how the planner checked it. Mark a claim that could not be run "UNVERIFIED: implementer checks first".
     Delete the section if there are none. -->
| Claim | How checked |
|---|---|
| ... | ... |

## Limits
<!-- SPEC_CHECKLIST.md rule 4. Every timeout, deadline, retry budget or size limit the change sets or depends on,
     including defaults of the tools that wrap it. Delete the section if there are none. -->
| Limit | Value | Wrapped by (must be larger) |
|---|---|---|
| ... | ... | ... |

## Acceptance criteria
<Numbered, each objectively checkable. The reviewer verifies every one.>
1. ...

## Required tests
<Name the test classes and the behaviours each must cover. Integration vs unit is the planner's call.>
- `XxxTest`: ...

## Quality gates
- `./gradlew check` passes (ktlint, detekt, unit + integration tests, ArchUnit, Spring Modulith verification).
- Kover: new/changed code line coverage ≥ **80%** (global floor: 70%).
- Wide event fields added for new behaviour are documented in docs/observability/WIDE_EVENT_SCHEMA.md (if that path is allowed).
- No new dependency unless listed in Design notes.

## Review log
<!-- Appended by the run-task orchestrator. One entry per round: verdict + numbered findings + resolution. -->
