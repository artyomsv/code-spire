# A one-line revert at the follow-up emit site silently re-breaks the charge ledger

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Trivial |
| Location | `spire-review-worker/src/main/java/dev/codespire/worker/pipeline/FollowUpWorker.java:136` |
| Found during | ADR-023 LLM cost accounting — the whole-branch fix wave's re-review |
| Date | 2026-08-07 |

## Issue

A charge's identity in `llm_charge` is `call_ref`, **derived** in the orchestrator rather than transmitted,
deliberately mirroring the idempotency claim the worker takes before it spends. For a follow-up the worker
claims per **triggering comment** (`FollowUpWorker.java:121-122`), so the orchestrator's slot must carry that
component too.

It does now — `FollowUpGenerated` gained a `triggeringCommentId` (`IntegrationEvent.java:254-255`) and
`CallRefs.followUpSlot` builds `threadRef + ':' + triggeringCommentId`. That fixed a Critical: before it,
turns 2..N of one conversation all resolved to a single `call_ref`, and `recordCharges`'
`ON CONFLICT … DO NOTHING` discarded every later turn's charges with no row, no log and no attention row.

**The defect is reachable again by one line.** `FollowUpWorker.java:136` is the sole site that populates the
new component. Pass a constant, a null, or the thread ref there and every later turn collapses onto one key
again — and **all 1150 tests stay green**, because no test asserts what the worker puts in that field. The
orchestrator-side tests drive `FollowUpGenerated` directly with their own values, so they pin the
*derivation* and not the *emit*.

## Risks

Low, because the code is correct today and the field is unlikely to be edited casually. Recorded because of
the failure mode rather than the likelihood: it is **silent in every channel**. No exception, no log line, no
dead-letter, no attention row — the charges simply are not there, and a review's reported cost is lower than
its real spend with nothing anywhere indicating a loss. That is the exact shape ADR-023 exists to eliminate,
and it would be reintroduced by an edit that looks harmless and passes CI.

Note the asymmetry with the review path: `REVIEW` and `RECONCILE` derive their slot from the commit, which is
carried on the event for other reasons and would break loudly if wrong. Only the follow-up slot depends on a
field that exists *solely* for this purpose, which is why only this one is unpinned.

## Suggested Solutions

1. **Assert the emit** (the fix): a worker-side test that a `FollowUpGenerated` published for a given
   triggering comment carries that id, and that two turns on one thread publish two different values. The
   worker already has `results` and `idempotency` fakes in place for the turn-cap path, so this is a small
   addition to an existing harness rather than new scaffolding.
2. **Make the field structurally hard to get wrong** — pass the whole claim key rather than the id, so the
   worker's claim and the event's identity are literally the same expression instead of two expressions that
   must agree. More invasive; only worth it if this recurs.
3. **Leave it.** Defensible while nothing edits that line, which is exactly the assumption a debt entry
   exists to stop relying on.
