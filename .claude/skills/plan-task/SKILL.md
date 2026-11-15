---
name: plan-task
description: Turn a feature request, PLAN.md phase item, or backlog file into a READY task spec via the planner agent (Opus). Use when the user says "plan <feature>" or invokes /plan-task <description-or-path>.
---

# plan-task

1. Make sure no task is active (`.claude/active-task` absent). If one is, ask the user whether to finish it first.
2. Spawn the `planner` agent with the user's request verbatim (or the backlog/PLAN reference) and the instruction to
   follow `.claude/agents/planner.md`.
3. Relay the planner's open questions to the user before marking anything READY. Once answered, send them to the
   planner with SendMessage so it can finalize the spec.
4. Show the user the spec path, its Goal, Allowed paths and Acceptance criteria, and suggest `/run-task <id>`.
