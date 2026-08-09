# Deleting a review archives it; LLM usage is never destroyed

**Status:** design approved 2026-08-09, not yet implemented.
**Supersedes:** the hard-delete behaviour introduced with ADR-023's ledger work.

## Problem

`ReviewProjection.deleteReview` is a true hard delete. In one transaction it removes the review row,
its scoped timeline, the underlying event stream, the worker's idempotency claims — and, as of the
ADR-023 review, **its charge ledger**:

```java
deleteBy(c, "DELETE FROM review_status WHERE review_id = ?", reviewId);
deleteBy(c, "DELETE FROM review_event  WHERE review_id = ?", reviewId);
deleteBy(c, "DELETE FROM event_log     WHERE stream_id = ?", reviewId);
deleteBy(c, "DELETE FROM llm_charge    WHERE review_id = ?", reviewId);
deleteWorkerClaims(c, reviewId);
```

That last line destroys real, paid AI usage. A review deleted for being clutter takes its token
counts, its model and its cost with it, and no query can reconstruct them. On the dev deployment a
single delete removed four charge lines and 11,454 millicents of genuine spend.

This contradicts the principle ADR-023 was built on. Snapshotting the rate onto each charge line
exists so **a later price edit cannot rewrite history**. That made cost immune to price changes and
left it fully exposed to deletion — the same history, erased by a different mechanism.

### Why the ledger deletion was added, and why removing it is safe

The deletion closed a real defect. `review_id` is `ReviewIds.reviewId(repo, pr)` — stable per PR, not
per run — and `llm_charge.review_id` is plain `TEXT` with no foreign key. Left behind, orphaned rows
were not merely unreachable: `costOf` / `listSummaries` / `latestModelFor` key on `review_id` alone,
so **a re-registered PR rendered the deleted run's money and model as its own**, and the new run's
`call_ref` collided with the orphan, discarding a real charge.

Every part of that requires the PR to be **re-registered**. This design retires the PR instead (see
Decision 3), so there is no second review to inherit anything. The hazard is removed by closing the
path, not by destroying the evidence.

## Decisions

**1. Archived is a third dimension, not a status value.** `review_status` already carries two
orthogonal facts: `status` (the review outcome — `completed`, `failed`) and `pr_state` (`OPEN` /
`MERGED` / `CLOSED`). Those were deliberately split in July 2026 because a merged PR and a passed
review are different facts that one badge could not carry. Archival is a third such fact. Overwriting
`status` with `archived` would destroy whether the run completed or failed, which is precisely the
statistic the data is being retained for.

**2. One table stays the source of truth.** A parallel archive schema was considered and rejected. Its
real advantage is that a forgotten `WHERE` cannot leak archived rows into a live view — a genuine
concern in this codebase. It loses on three counts: every future migration must mirror into the
archive tables or drift silently; "all reviews ever" (the reason for retention) becomes a `UNION`; and
`event_log` is the **append-only versioned source of truth** (`V1__event_store.sql`) that
`JdbcEventStore.load` reads to rehydrate aggregates, so relocating its rows mutates the event store
rather than archiving a projection. A read-only `live_review` view was also considered and rejected,
because archived reviews are meant to be visible in the same table behind a toggle — a view that
permanently excludes them fights that requirement.

**3. An archived PR is retired.** No new review is ever created for that PR: not by a push, not by
`/review`, not by reopening. Any inbound event produces a one-time notice instead. This keeps
`review_status`'s `PRIMARY KEY (review_id)` intact — a per-run review identity would mean changing the
aggregate stream id, which is an event-store contract — and it is what makes Decision 1's retention
safe. The cost is accepted and real: archiving a PR that is still being worked on retires it
permanently, and the only way back is a manual database edit.

**4. No unarchive.** Not in this design.

## Data model — migration `V32`

```sql
ALTER TABLE review_status ADD COLUMN archived_at TIMESTAMPTZ;
ALTER TABLE llm_charge    ADD COLUMN archived_at TIMESTAMPTZ;

-- The reviews list reads live rows ordered by recency; keep that path off the archived rows.
CREATE INDEX review_status_live_updated
    ON review_status (updated_at DESC) WHERE archived_at IS NULL;
```

`NULL` means live. `status` and `pr_state` are untouched, so an archived review still reports that it
completed with four findings.

### Why `llm_charge.archived_at` exists

It changes nothing today: Decision 3 already prevents a second review from existing, so nothing can
inherit an archived review's charges.

It exists for the cleanup that was named as a future intention. If a cleanup ever hard-deletes an
archived `review_status` row, that PR becomes registrable again and the orphan hazard returns in full.
The stamp is what makes it survivable: **live per-review reads filter `archived_at IS NULL`, while
statistics ignore the filter and see everything ever spent.** Without this column, "usage survives
cleanup" is an intention; with it, it is a property.

Concretely, `WHERE archived_at IS NULL` is added to `costOf`, `cumulativeCost`, `unpricedCallsFor`,
`latestModelFor` and the per-review aggregate inside `listSummaries`. `AttentionQueries.costRows` also
filters it, so an archived review cannot keep an unpriced-call warning raised. Any future statistics
query deliberately omits the filter.

## The archive operation

`ReviewProjection.deleteReview` becomes `archiveReview(workspace, slug, pr)`. One transaction, two
`UPDATE`s, **no deletes**:

```java
UPDATE review_status SET archived_at = now() WHERE review_id = ? AND archived_at IS NULL;
UPDATE llm_charge    SET archived_at = now() WHERE review_id = ? AND archived_at IS NULL;
```

The operation is idempotent, and the endpoint must be able to tell the two failure modes apart — but
the `UPDATE` cannot, because `AND archived_at IS NULL` matches zero rows both for a review that does
not exist and for one already archived. A boolean return would collapse them, so `archiveReview`
returns an enum:

```java
enum ArchiveOutcome { ARCHIVED, ALREADY_ARCHIVED, NOT_FOUND }
```

resolved by reading `archived_at` inside the same transaction when the `UPDATE` matches nothing. The
resource maps `ARCHIVED` to 204, `ALREADY_ARCHIVED` to 409 and `NOT_FOUND` to 404. It broadcasts on
`ARCHIVED` only, the same way the delete did, so live clients update once and a repeated call is silent.

Nothing else is touched. `review_event`, `event_log` and the worker's `comment_idempotency` claims all
stay. Retaining `event_log` additionally keeps `ReviewRuns.currentRun` correct, since it counts
`ReviewRequested` events in that table.

`deleteWorkerClaims` is retained as a method — the re-run path still uses it — but the archive path
does not call it. A retired PR never runs again, so a stale claim can never be resurrected.

### API and UI surface

`DELETE /api/reviews/{workspace}/{slug}/{pr}` becomes `POST /api/reviews/{workspace}/{slug}/{pr}/archive`,
and the UI button changes from **Delete** to **Archive** with matching confirmation copy. A `DELETE`
verb that destroys nothing misdescribes the operation to every future reader of the API. The endpoint
keeps its existing `@RolesAllowed` admin restriction.

## Retirement and the one-time notice

Every inbound integration event whose review is archived stops at the saga and emits a new command
instead of its normal handling. This covers `AuthorReplied`, `ManualCommandReceived` and
`PullRequestEventReceived`.

**An archived review is frozen, including its `pr_state`.** If the PR is later merged or closed, the
badge keeps whatever it read at archival. The alternative — keep updating `pr_state` while refusing
everything else — was considered and rejected: it makes "retired" mean two different things depending
on which column you look at, and a row that still moves is one a future reader will reasonably assume
is still live. The archived review is a record of what happened up to the moment it was archived, and
the PR's later life is not part of it.

`ActionCommand.NotifyArchived` is modelled directly on `NotifyTurnCap`, which exists for the same
shape of problem — a decision to stay silent that a human would otherwise read as a lost webhook:

```java
record NotifyArchived(String reviewId, RepoRef repo, long prId, ThreadRef threadRef,
                      String scmCredential) implements ActionCommand {}
```

- **No LLM credential.** The notice is fixed text, so retirement costs no tokens and always says the
  same thing.
- **`threadRef` is nullable.** Non-null routes to `CommentSink.replyInThread`; null routes to
  `CommentSink.postSummary`, which is the top-level PR comment. The notice posts where the event
  arrived.
- **One claim per review.** The worker calls
  `idempotency.claim(reviewId, ARCHIVED_SLOT, ARCHIVED_NOTICE_KEY)` with `ARCHIVED_SLOT` a **constant**,
  not the thread ref. That is what makes it once per review rather than once per thread. An
  `AlreadyPosted` claim logs at INFO and returns — INFO because it is the only record that an inbound
  event went unanswered on purpose.
- **Its own result event.** `IntegrationEvent.ArchivedNotified(String reviewId, String commentId)`,
  registered in `EventKeys` and the `@JsonSubTypes` list. Deliberately not `FollowUpPosted`, which
  would bump the conversation turn count for a notice that consumed no turn.

The notice text states that the review is archived and that no further reviews will run for this pull
request. It does not invite an @-mention: unlike the turn cap, no policy overrides retirement.

### Accepted consequence

Once per review means a second person replying in a *different* thread gets silence. `NotifyTurnCap`
made the opposite call (one claim per thread) for exactly this reason. Chosen deliberately and
recorded here so the trade-off is visible if it turns out to be wrong in use.

## UI

The reviews list defaults to live rows. A **Show archived** checkbox includes archived rows inline in
the same table, visually marked as archived. No separate screen and no unarchive control. Archived
reviews keep fully working detail pages, since none of their data was removed.

The existing All / Reviewing / Completed / Needs attention / Closed filter chips continue to operate on
whichever set the checkbox selects.

## Deliberately not built

- **Unarchive.** Decision 4.
- **A cleanup/purge path.** Named as a future intention, not a current need. `llm_charge.archived_at`
  is the groundwork that makes it safe to add later; building the purge itself now would be
  speculative.
- **A statistics screen.** The retained data is what makes one possible; the screen is its own piece
  of work.
- **Backfilling the reviews already hard-deleted.** Their rows are gone and cannot be recovered. The
  dev deployment's pre-V30 archive tables (`review_llm_call_pre_v30`, `review_status_usage_pre_v30`)
  are unaffected by this design and remain the only record of that era.

## Testing

Behavioural tests, each pinning something that would otherwise regress silently:

1. **Archiving keeps every charge row.** The direct guard on the deletion being reverted: archive a
   review with charge lines, assert the rows still exist and their sum is unchanged. Without this,
   re-introducing the `DELETE` passes every other test.
2. **Archiving preserves `status` and `pr_state`.** Asserts Decision 1 — a completed review is still
   `completed` after archiving.
3. **An archived review is absent from the default list and present with the flag.**
4. **A live review's cost reads exclude archived charges.** Pins the `archived_at IS NULL` filter that
   makes future cleanup safe. Requires a fixture with both, since the state is not otherwise reachable.
5. **The notice fires once across two inbound events**, and the second is logged rather than posted.
6. **The notice brokers no LLM credential** — assert the dispatched command carries none, mirroring the
   existing turn-cap coverage.
7. **An inbound event on an archived review creates no new review row**, for all three of
   `AuthorReplied`, `ManualCommandReceived` and `PullRequestEventReceived`.
8. **`archiveReview` distinguishes its outcomes** — archiving twice returns `ALREADY_ARCHIVED` (not
   `NOT_FOUND`) and does not move `archived_at`; archiving an unknown PR returns `NOT_FOUND`. Both
   halves are needed: an enum whose two failure values are never separately asserted is a boolean
   wearing a costume.
9. **An archived review's `pr_state` is frozen.** Merge the PR after archiving and assert the badge did
   not move. This is the test for a decision, not for a mechanism, so it is the one most likely to be
   "fixed" by someone who assumes the missing update is a bug.

## Documentation

A new ADR recording that delete became archive, that AI usage retention outranks the clean-slate
property it replaced, and that the clean slate was only needed because a re-registered PR could
inherit an orphan — which retirement now prevents. ADR-023's ledger section gains a pointer, since its
"delete is a true clean slate" reasoning no longer describes the system.

`techdebt/spire-orchestrator/3-3-the-charge-ledger-is-keyed-on-an-id-two-scms-can-share.md` is
unaffected: a workspace name registered on two SCMs still collides, and this design does not address it.
