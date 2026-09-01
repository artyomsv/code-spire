# `llm_charge` still has a review-shaped spine, so a run cannot be charged

**Criticality:** 2 (High) — **Complexity:** 3 (Medium)

## What

M0 Task 9 was to give the charge ledger a neutral subject: rename `review_id` to `subject_id`,
add `subject_kind` (`REVIEW` | `RUN`), add `capability` and `credential_ref`, and widen the `kind`
CHECK to admit the factory's call kinds (`SPEC`, `PLAN`, `BUILD`, `FIX`).

**It was written, it worked, and it was reverted.** The reason is recorded below rather than the
change being left in place, because the alternative was committing a branch with six failing tests.

## Why it matters

Until this lands, a factory run cannot record what it spent. A run id in a column named `review_id`
would be the shape ARCHITECTURE §7 calls out — a name that lies — and the `kind` CHECK would refuse
every factory row outright, so the charge write fails rather than being merely mislabelled.

The `capability` column in particular cannot be added later without losing data: a row that did not
record which capability pack caused the spend cannot have one inferred afterwards (ADR-034), the
same reasoning by which ADR-023 snapshots a rate onto the row rather than re-deriving it.

## What was learned, and is worth keeping

The migration itself is correct and was verified against a real Postgres. Two findings from writing
it are worth carrying into the next attempt:

- **The `kind` CHECK cannot be dropped by name.** `V30__llm_charge_ledger.sql` declares it INLINE
  and unnamed, so Postgres generated `llm_charge_check`, `llm_charge_check1`, … in creation order.
  A migration saying `DROP CONSTRAINT IF EXISTS llm_charge_kind_check` — which is what it looks like
  it should be called — succeeds having dropped nothing, adds the new constraint alongside the old
  one, and every factory INSERT then fails against a constraint the migration believed it removed.
  The working version finds it by DEFINITION (`pg_get_constraintdef(oid) LIKE '%RECONCILE%'`) and
  raises if it finds none, so a changed V30 is a loud failure rather than a silent one.
- **`UNMETERED` requires `rate = 0 AND cost = 0`, not nulls.** A test fixture inserting an unmetered
  charge with null rate and cost violates a V30 CHECK. That is the schema working correctly, and it
  cost a debugging cycle to recognise as such.

## Why it was reverted

With the migration and every read-site rename in place, `LlmChargeSubjectTest` (5 tests, new) and
`LlmChargeProjectionIT` (existing, exercises `recordCharges` end to end) both PASS — so the ledger
itself is correct.

But six `ConversationFindingSagaTest` cases fail. Verified reproducible:

- whole change stashed → the six pass
- change applied → the six fail, including in isolation

The failure is `openFindingsFor` returning an empty list, and no warning from any of
`ReviewProjection`'s eleven warn sites fires — so `addConversationFinding` is never reached and the
saga is returning earlier, before the line that files the finding. Nothing in that path reads
`llm_charge`.

**The mechanism is not understood.** That is the whole reason for this entry: a change whose effect
cannot be explained is not one to commit, however green its own tests are.

## Where to start

The saga path is `IntegrationSaga` around line 405 — everything before the
`projection.addConversationFinding` call is a guard that can return early. Instrument those guards
rather than the projection, since the projection is demonstrably not being called. The fixture chain
is `ConversationFindingSagaTest.liveReview()` →
`ReviewFixtures.seedCompletedReviewWithCharges` → `projection.recordCharges`, which is the only part
of that test touching the ledger at all.

`CallRefs.forRun` was kept: it is additive, depends on no schema, and carries the reasoning about
why a re-run and an auto-retry need opposite treatment of the same key.

## Discovered

2026-09-01, during M0 Task 9.
