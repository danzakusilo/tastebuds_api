# Concept cards

One card per backend concept this codebase relies on, written for a reader who has not met the concept before. A card
is written once and linked from then on. Review briefs (`/brief-pr`) explain a change with cards, so that no
explanation depends on a term the reader was never given.

## Index
| Term | Card |
|---|---|
| Token bucket, Bucket4j, rate limiting, failing open | [An allowance of requests per client](token-bucket.md) |

## The reader
The cards and briefs are written for one reader, described in a known-list:
- [reader-seed.md](reader-seed.md) is the starting list, committed.
- `.claude/reader.local.md` is the working copy, git-ignored. `/brief-pr` creates it from the seed, adds a card to it
  when a teach-back answer is right, and notes explanations that failed.

A concept that is not on the known-list counts as unknown. The author does not judge what the reader "probably knows".

## Rules for a card
1. **Problem first, name third.** The sections keep the order of [TEMPLATE.md](TEMPLATE.md). "The problem" and
   "What we do" use no tool name and no term of art. The name comes after the reader knows what it is a name for.
2. **Only given words.** A card uses plain words, the known-list, terms it has defined itself, and other cards
   (linked at first use). If it needs another unknown concept, that concept gets its own card first.
3. **One concept, as used here.** A card explains what this repo does with the concept, with this repo's numbers. It
   is not an introduction to the whole technology.
4. **Nothing invented.** "Rejected" holds what the task spec or `docs/PLAN.md` records. If they record nothing, the
   card says so. A note by the author is allowed when it is marked as one.
5. **A comparison says where it breaks.** "In your world" names the point where the comparison stops holding.
6. **Short.** A card takes about three minutes to read: 500 words, 600 at most, not counting link targets.
7. **Kept true.** A change that alters what a card says updates the card, and its "Checked against" line.

## How a card gets here
1. `/brief-pr <PR>` finds the concepts the change needs. For each one that is neither on the known-list nor a card,
   the explainer drafts a card in `build/pr-brief/cards/` (git-ignored) and puts it into the brief.
2. The cold reader checks the brief and the drafts for terms that were never given.
3. The reader answers the teach-back question. A right answer puts the card on the known-list.
4. After the PR is merged, the drafts are committed here on their own branch, with a row in the index.

## Concept budget
A PR may need at most **two** concepts that are new to the reader. A third one is a reason to split the PR, not to
write a longer brief. `/brief-pr` reports when a PR is over the budget and proposes the split.
