# Challenges

A challenge is a second opinion on one large decision. A separate agent, the challenger, makes the strongest honest
case for the best alternative, then answers that case, and says which way it falls. It exists because backend
decisions here are made by the planner and trusted by an owner whose field is elsewhere. Without it, nobody checks
whether a decision was right before it is built.

## Which decisions are large
A decision is large when it is hard to undo or wide in effect. It is large if it does at least one of these:

1. Adds or removes something that runs: a database, a message broker, a cache, a proxy, an outside service. Or adds
   a dependency that shapes the code around it (a framework, not a helper library).
2. Sets or changes the shape of stored data (tables, event formats) or of the public API.
3. Changes who may do what: sign-in, permissions, a trust boundary, what is kept or logged about a person.
4. Changes how the service is deployed, scaled or operated.
5. Adds or changes a decision in `docs/PLAN.md` §2.
6. Adds a cost that recurs every month.

Everything else is local: a name, a class structure, a test level, an internal helper. Local decisions are not
challenged. In doubt, ask: would a new tech lead ask "why did you do it this way?" in a design review, and would
undoing it take more than a day? Two yeses make it large.

## When a challenge runs
- **Before the build:** `/plan-task` challenges each large decision of a new task spec before the spec is READY. The
  result is recorded in the spec, under the decision.
- **On request:** `/challenge <id>` challenges a decision that is already recorded, for example `/challenge D16` for a
  row of `docs/PLAN.md` §2, or `/challenge 012/1` for decision 1 of task 012. The result is recorded here.

## Verdicts
| Verdict | Meaning | What happens |
|---|---|---|
| `HOLDS` | The decision is right for this project now. The alternative's best point has an answer | Recorded. Nothing else |
| `WEAK` | The decision may be right, but it rests on something nobody has checked | The owner answers the turning question |
| `OVERTURN` | The alternative is better for this project now | The planner changes the decision, or the owner decides |

Every challenge ends with a **turning question**: the one fact about the project that decides between the two
options, asked so that the owner can answer it without backend knowledge. Example: "Will more than one copy of the
server run in the next six months?" The owner cannot weigh two technologies, but the owner does know the project.

## A record
One file per decision, `docs/challenges/<id>.md` (`D16.md`, `012-1.md`), committed on its own branch after the owner
has answered the turning question:

```markdown
# <id>: <the decision as one plain sentence>

> Challenged on <YYYY-MM-DD> against `<short sha>`. Verdict: **<HOLDS | WEAK | OVERTURN>**.

## What the decision is for
## The alternative argued
## Its strongest point
## The answer to it
## Turns on
<The turning question, the owner's answer, and the date of the answer.>
## Outcome
<Kept, or changed (with the task that changes it).>
## Not checked
```

Concept cards and review briefs take their "Rejected" and "Wrong if" lines from these records and from the
"Decisions" section of a task spec. That is what makes those lines a record and not a reason made up afterwards.

## Index
| Decision | Verdict | Outcome | Record |
|---|---|---|---|
| none yet | | | |
