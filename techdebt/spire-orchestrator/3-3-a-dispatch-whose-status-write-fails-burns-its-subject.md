# A dispatch whose own status write fails leaves the run queued for ever

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Medium |
| Location | `spire-orchestrator/.../factory/RunResource.java` (`dispatch`'s catch, both branches), `.../factory/FactoryRunProjection.java` (`dispatchFailed`, `dispatchUncertain`, `queued`, `update`) |
| Found during | M1 Task 9 four-lens review (code quality, QA) |
| Date | 2026-09-03 |

## Issue

Every publish failure is recorded by writing the run's row — `dispatchFailed` for a definite miss,
`dispatchUncertain` for an ambiguous one. Both go through `update`, which wraps a `SQLException` in
an `IllegalStateException`. Those calls sit **inside** `dispatch`'s own catch block, so a database
fault there escapes as a plain 500 with the row still `queued`.

A `queued` row has no exit. `queued()` re-arms only the `failed`/`DISPATCH_FAILED` shape, so an
identical retry matches nothing and `alreadyExists` answers 409. `resolveDispatch` guards on
`dispatch_uncertain`, so the resolution endpoint refuses it too. No attention row fires — the
uncertain row is what raises one, and it was never written. The subject is burned: that repository
and subject can never be dispatched again, and the only remedy is editing the table by hand.

It needs a database fault concurrent with a broker fault, which is why this is Medium rather than
High. But those two are correlated in practice — a host under memory pressure, a network partition
that takes out both the broker and the connection pool — so "two independent faults" overstates how
unlikely the pairing is.

This is pre-existing: `dispatchFailed` had exactly this exposure before Task 9, which added a second
call with the same shape. It is filed now because the review that found it also established that the
run's class javadoc already names the hazard ("a throw between the row and the dispatch would leave a
queued row that nothing re-arms") without noticing that the recovery path has it too.

## Risks

- A subject becomes permanently undispatchable, and the operator's only signal is one 500.
- The failure is invisible to the attention panel by construction: the row that would raise it is the
  row that could not be written.
- The 409 an operator meets on retry says "already exists (status queued)", which describes a run
  about to start — the opposite of what has happened.

## Suggested Solutions

- Let `queued()` re-arm a `queued` row whose `started_at` is older than some multiple of the ack
  timeout. That is the smallest change and it needs care: a genuinely queued run the worker has not
  reached yet must not be re-armed underneath it, so the threshold has to exceed the worst dispatch
  latency, and the claim store is the backstop if it does not.
- Or raise an attention row for a `queued` run older than a threshold — the `REVIEW_STUCK` shape,
  which this schema already has a precedent for. It does not fix the row, but it stops the failure
  being silent, and silence is the worse half.
- Do **not** delete the row on a failed status write. A deleted row is the unrecorded run the write
  order exists to prevent, and the record may well be on the topic.
