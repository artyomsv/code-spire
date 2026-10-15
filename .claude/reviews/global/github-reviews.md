# GitHub reviews — branch review state (PR #196)

Base `2757caa3` (master), branch `feat/github-reviews`. Spec `docs/superpowers/specs/2026-10-15-github-reviews-design.md`,
plan `docs/superpowers/plans/2026-10-15-github-reviews.md`.

## Rounds

(none yet)

## Round 1 — whole-branch review of `158cba16` (Fable, 2026-10-16), resolved in `8040b8b6`, `710e31e6`

| Finding | Resolution |
|---|---|
| I-1 a new push kept the old head's "Posted as" line | Resolved: `registerHeader` clears the three posted columns; `aNewHeadClearsThePostedVerdict` |
| I-2 mutation checks not run | Resolved: 26 mutants, `github-reviews-mutations.md`; 3 survivors became tests |
| I-3a a crash after a body-only review lost the inline fallback | Resolved: `postedWithoutItsComments` → inline fallback; `aCrashAfterABodyOnlyPostFallsBackToInline` |
| I-3b a comment whose line GitHub nulled was unmatched | Resolved: `original_line` / `original_start_line` fallback; `aCommentWhoseLineMovedIsMatchedOnItsOriginalLine` |
| M-4 redelivery with an unknown bot login emits a null posted verdict | Open, Minor |
| M-5 a transient fault on the reviewer request is a permanent receipt | Open, Minor (spec D9 says fault → receipt) |
| M-6 every 422 on requested_reviewers is named not-a-collaborator | Open, Minor |
| M-7 reviewUrl rendered without a scheme check | Open, Minor (value comes from the forge API) |
| M-8 repository_unknown replaces open_blockers=N in the timeline reason | Open, Minor |
| M-9 fully qualified java.time.Instant in reviewTranscript | Open, Minor |
| M-10 a legacy summary issue comment stays stale after the switch (D2) | Open, Minor (docs) |

CI on `710e31e6`: all checks green (run 38816833062).
