# Code Review State: global / m2-t45-fix-identity

Last reviewed: 2026-09-04
Rounds completed: 1

Round 1 over `4fa75e1`, `5ff6d67`, `4acff11` on `feat/factory-m2-deliver` (PR #119) — M2 tasks 4, 5
and 5b(i): the fix run's identity, the caps that bound it, and the branch rules for where its output
may land. Nothing dispatches yet.

**qa did not deliver a report.** Three lenses reported; a direct request to qa went unanswered. Its
section is unknown rather than clean, and the mutation work below is the lead's own plus
code-reviewer's. Recorded rather than glossed, because silence is not a clean result.

**The theme of the round: every defect was in a CLAIM.** Three comments asserted a guard the schema
did not provide, one javadoc asserted a floor that was optional, and four mutations survived. The
code itself was sound.

## The reasoning error, twice in one day

A mutation survived, and the conclusion drawn was **"the schema must be guarding it"** rather than
**"my fixture cannot build the row"**. The second reading was correct. This is the same shape as
`FIND_BY_THREAD`'s "newest row wins" claim two commits earlier — assert a guarantee, fail to kill the
mutation, and credit the guarantee rather than doubt the test.

It has an unusually clean epilogue. code-reviewer proved the row legal:
`(kind = 'FIX') = (review_id IS NOT NULL AND finding_ref IS NOT NULL)` has an **AND** on the right, so
a non-FIX row satisfies it by failing either conjunct. Then security found blank ids slipped through
the same CHECK (`'' IS NOT NULL` is true), and closing THAT meant rewriting it as two explicit arms —
which, as a side effect nobody set out to produce, forbids a non-fix row from carrying a review at
all. So the original claim is true again, for a reason unrelated to the original argument, and the
filter is belt-and-braces **until the constraint is relaxed for SPEC and PLAN runs**. All of that is
now in the code, with its expiry.

## Resolved (fixed in code; do not re-raise)

- [sec/H1] **The destination floor was optional exactly when it was needed.** The check ran only
  `if (destination != null && !destination.isBlank())`, while the class javadoc said the destination
  is "refused in EVERY mode". A dispatch that forgets one map entry skipped it silently, and a trunk
  called `develop` — which the `main`/`master` convention list does not cover — would be
  fast-forwarded. `existing` mode now refuses to start without `SPIRE_PROTECTED_BRANCH`, blank
  included, because `review_status.dest_branch` defaults to `''` and copying it through
  unconditionally yields blank rather than missing — round 1
- [sec/M1] **V54's CHECK admitted blank ids.** `'' IS NOT NULL` is true, so a FIX row with empty-string
  ids passed and was counted by neither cap for any real id — the cap failing OPEN for exactly that
  row. Not hypothetical: this schema already uses blank-not-null for `source_branch` and
  `dest_branch`. Rewritten as two explicit arms with `btrim(...) <> ''`, because `kind` is NOT NULL
  and a CHECK evaluating to NULL passes — round 1
- [cr/C1] **Three comments claimed the CHECK made a legal row impossible.** See above — round 1
- [cr/I2] **`nextAttempt` read the wrong axis and every test agreed with both.** Swapping
  `forFinding` for `forReview` passed all 13, because the only case calling it seeded one run for one
  finding on one review. Per-review numbering would report "attempt 3" for a finding's FIRST fix,
  contradicting the per-finding refusal message in the same class — round 1
- [cr/I3] **`isBlank()` → `isEmpty()` passed all 5**, because nothing seeded whitespace — so the
  distinction the javadoc argues for was asserted by nothing. The `!= null` beside it was provably
  dead (`source_branch` is `NOT NULL DEFAULT ''`) and is gone — round 1
- [cr/I4] **The "both modes" property was pinned for trunks and not for destinations.** Moving the
  `SPIRE_PROTECTED_BRANCH` block inside the existing-mode branch passed the whole suite. The new case
  asserts the phrase rather than the branch value, because the namespace refusal contains the same
  value and would satisfy a value assertion — round 1
- [cr/I5] **`commit` had the identical hazard and no guard.** `commit_sha` carries the same
  `NOT NULL DEFAULT ''` as `sourceBranch` and the same failure — the publisher's `Env.required`
  refuses a blank inside the container, after the agent has been paid. The class documented that
  hazard at length for one of the two columns — round 1
- [cr/I6] **ADR-040 §3 asks for a `provider_type` and repository match that nothing performed.**
  `belongsTo` added, with the reason it is separate from `isPushable` — round 1
- [sec/L1] **`NEVER_PUSHED` was exact-match.** Measured against the pinned JGit: `Main`, `MAIN`,
  `HEAD`, `refs/heads/main`, `heads/main`, `-main`, and names carrying a zero-width space or a
  Cyrillic `а` all pass `isValidRefName` and the floor. **None reaches `refs/heads/main`** — forge
  refs are case-sensitive — so this is not a bypass; it is a machine creating a branch a person reads
  as the trunk. Refused on that ground alone. Invisible characters are refused, ordinary non-ASCII is
  not, and a test asserts the second half — round 1
- [rules/1] **`SMOKE-TEST.md`'s `PUBLISHER_MISCONFIGURED` row** was the one place a reader learns what
  makes the publisher refuse, and it still described the branch rules as namespace-only — round 1
- [rules/2, rules/3] **ADR-040 overclaimed and under-named.** It said the refusal covers "the
  repository default branch"; the code refuses two literal names, and its own javadoc calls that "a
  convention list, not a truth". And it never named `SPIRE_PROTECTED_BRANCH`, the variable carrying
  the half it does describe — round 1
- [rules/6] A redundant partial index on `(review_id)`: the `(review_id, finding_ref)` index already
  serves that lookup on its leading column under the same predicate — round 1
- [rules/7, cr/S16] **V54 claimed two vocabularies "cannot drift apart".** They are two independent
  literals in two files and nothing enforces agreement — round 1
- [cr/S9] A `{@link #NAMESPACE}` reference to a member that does not exist — round 1
- [cr/S12] `FixTargetsTest.exec`'s `startsWith("INSERT")` branch silently shifted every binding offset
  for any other statement; every parameter is bound explicitly now — round 1
- [cr/S13] `answersEmptyForAReviewItHasNeverSeen` seeded nothing, so it could not tell a working
  WHERE clause from an empty table — round 1
- [cr/S14, cr/S15] `destination.strip()` was untested, and one case tested two behaviours under a name
  describing only the second — round 1
- [cr/S18] `FixTargets` applies no `archived_at` filter where `AttentionQueries` applies one three
  times. It is gated upstream — the saga stops an archived review before the command switch — and
  that is now recorded in the class javadoc, in the style `SpendWindow` uses for its own deliberate
  omissions, rather than a second filter that would read as the guard — round 1
- [sec/L2] `pr_state` is reset to OPEN by every pull-request event, so a redelivery after a merge
  flips a closed pull request back to pushable. Recorded on the class: the row is the KEY, not the
  proof — round 1

## Deferred to the dispatch slice (recorded, not forgotten)

- [sec/H2, cr] **The fork gap.** Security VERIFIED the claim, confirmed High is right, and confirmed
  "unreachable today" is true (`git grep SPIRE_BRANCH_MODE` hits only the config, its test and the
  ADR). One refinement worth having: today the init clone would usually fail first, because
  `WorkspaceClone` fetches `refs/heads/*` only and a fork's head is normally unreachable from a base
  branch — **an accident of the default refspec, not a control**. The bad case survives when the fork
  branch tip IS a base-repo branch tip. Recommendation adopted: record `fromFork` at ingress AND
  re-read the pull request from the forge at dispatch, which closes this, `sec/L2` and the
  crafted-row case together. Phrased well by the reviewer: *the row is the key, the forge is the
  proof.*
- [sec/L3] **The per-review cap is check-then-act.** The per-finding axis has a natural guard —
  `nextAttempt` gives concurrent racers the same attempt, so `RunIds.of` gives the same id and the
  `run_id` primary key drops the second, **provided the dispatch derives the id from the same count
  and reuses `projection.queued(...)`**. Per-review has no such guard. Either an advisory lock in the
  transaction that counts and inserts, or one sentence saying the overshoot is accepted.
- [sec/L4] `0` means unlimited in `decide`. Fine for a slice with no caller; a hole once one exists,
  because FR-F32 bounds a runaway loop rather than being an operator opt-in like ADR-025's spend cap.
- [sec, forward] `ExecuteRun` needs `branchMode` and `protectedBranch` components — and the shorter
  constructors will keep compiling while dropping them. Use withers.

## Dismissed (acknowledged, will not fix; agents may escalate with explicit justification)

- [rules/V-1] **Add the two new variables to `.env.example`.** My own suspicion, falsified by the
  reviewer: none of the eight pre-existing publisher variables is there either, because they are
  per-run container environment computed by `RunUnitBuilder`, not operator-set deployment config.
  Adding them would invent a contract.
- [cr/S10] Replace `decide`'s `int` sentinel with a `Caps` carrier. Good, and it belongs with the
  caller that reads configuration — introducing the type now fixes the shape of a decision the
  dispatch slice has not made.
- [cr/S11] Return a reason from `isPushable()` rather than a boolean, mirroring `FixRuns.Decision`.
  Right, and same timing: the reason's wording is the caller's, and there is no caller.
- [cr/S17, sec/L5] No FK from `factory_run.review_id` to `review_status`. Integrity rather than
  security, and `archive-not-delete` makes it feasible — but it is a schema decision worth making
  when the writer exists, not ahead of it.
- [cr/S19] Extract the two floor checks out of `branch(...)`. It is 34 lines against a 30-line
  guideline and most of the body is exception text. Worth doing when the method next changes; doing
  it inside a review round means reviewing the same logic twice.
- [rules/8] `decide` takes 4 parameters against a "max 3" rule. House practice — 20+ methods in
  `spire-orchestrator/src/main` take 4 or more.
- [rules/9] No Conventional-Commits prefix. House style across the whole history.
