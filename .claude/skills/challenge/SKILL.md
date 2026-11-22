---
name: challenge
description: Get a second opinion on one large backend decision that is already recorded (challenger agent, Opus). It argues for the best alternative, answers that case, and returns a verdict with one question for the owner. Use when the user says "challenge D16", "why not <alternative>?" about a recorded decision, or invokes /challenge <PLAN decision id | task-id/decision-number>.
---

# challenge: second opinion on a recorded decision

Argument: a `docs/PLAN.md` §2 id (`D16`) or a task decision (`012/1`: decision 1 of task 012). What counts as a
large decision, the verdicts and the record format are in `docs/challenges/README.md`.

This skill never changes code, a task spec or `docs/PLAN.md`. It writes one git-ignored file and reports.

## 1. Find the decision
1. `D<n>`: the row in `docs/PLAN.md` §2. `<task>/<n>`: "Decision <n>" in the "Decisions" section of
   `docs/tasks/<task>-*.md`. An older spec without that section: the matching part of its Design notes.
2. If you cannot find it, or the argument is free text that matches no record, ask the user which decision is meant.
3. If the decision is not large by the README's list, say so and ask whether to continue.
4. If `docs/challenges/<id>.md` exists, show its verdict and date and ask whether to challenge again.

## 2. Run the challenger
Spawn the `challenger` agent with: the decision as one sentence, where it is recorded, mode `recorded`, and the
known-list path (`.claude/reader.local.md`, or `docs/concepts/reader-seed.md` if that file is missing). Tell it to
follow `.claude/agents/challenger.md`. Give it nothing from this conversation: it reads the record as a newcomer.

`mkdir -p build/challenge` and write its reply unchanged to `build/challenge/<id>.md` (`D16.md`, `012-1.md`).

## 3. Report to the user
1. The verdict, then the reply as it is. Do not shorten the strongest point or the answer to it.
2. Ask the turning question and wait for the user's answer. Do not answer it for them.

## 4. After the answer
- **The decision stays.** Offer to commit the record: `docs/challenges/<id>.md` in the README's format, with the
  user's answer and its date, plus a row in the README index, on its own branch from `main` (`docs/challenge-<id>`).
  Only after a yes.
- **The decision should change.** Changing a `docs/PLAN.md` §2 decision needs the user's explicit agreement. With
  it, offer `/plan-task` for the change, with the challenge as input. The record is committed the same way, with
  "Outcome: changed by task <id>" once that task exists.
- **The user cannot answer yet.** Say what would have to be found out, and stop. Offer a `docs/backlog/` file.

## Notes
- One decision per run. If the challenger names another large decision that this one depends on, tell the user and
  offer to challenge that one next.
- A challenge is advice. It never blocks work that is already in progress.
