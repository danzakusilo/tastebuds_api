---
name: plan-task
description: Turn a feature request, PLAN.md phase item, or backlog file into a READY task spec via the planner agent (Opus), with each large decision challenged by the challenger agent before the spec is READY. Use when the user says "plan <feature>" or invokes /plan-task <description-or-path>.
---

# plan-task

1. Make sure no task is active (`.claude/active-task` absent). If one is, ask the user whether to finish it first.
2. Spawn the `planner` agent with the user's request verbatim (or the backlog/PLAN reference). Its agent file is
   already its system prompt: do not tell it to read or follow `.claude/agents/planner.md`.
3. Relay the planner's open questions to the user before marking anything READY. Once answered, send them to the
   planner with SendMessage so it can finalize the spec.
4. Challenge the large decisions (the list is in `docs/challenges/README.md`). The planner's reply names them; the
   spec stays `DRAFT` until this step is done.
   1. More than three large decisions in one task: stop and tell the user. The task is too big to defend as one
      change; ask the planner to split it.
   2. For each large decision, spawn one `challenger` agent, in parallel: the decision as one sentence, the spec path
      and decision number, mode `planning`, and the known-list path (`.claude/reader.local.md`, or
      `docs/concepts/reader-seed.md` if that file is missing). Give it nothing from the planner's conversation.
   3. `HOLDS`: send the reply to the planner, which records it under the decision ("Challenge").
   4. `WEAK` or `OVERTURN`: send the reply to the planner once. It either changes the decision and the spec, or
      keeps the decision and answers the challenge. If it keeps the decision, the two disagree, and the user
      decides: show the user both positions (five lines each at most, in plain words) and the turning question,
      and wait for the answer. Do not answer it for them. Send the answer to the planner to record, with its date.
   5. When every large decision is settled, tell the planner to set the spec to `READY`.
5. Show the user the spec path, its Goal, Allowed paths and Acceptance criteria. For each large decision add one
   line: the decision, the verdict, the alternative's strongest point, and who settled it. Suggest `/run-task <id>`.
