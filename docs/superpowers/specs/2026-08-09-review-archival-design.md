# Deleting a review archives it; LLM usage is never destroyed

**Status:** design approved 2026-08-09, revised the same day after review, not yet implemented.
**Supersedes:** the hard-delete behaviour introduced with ADR-023's ledger work.

## Problem

`ReviewProjection.deleteReview` is a true hard delete. In one transaction it removes six things: the
review row, its scoped timeline, the underlying event stream, the worker's idempotency claims, the
worker's context blob (inside `deleteWorkerClaims`, `ReviewProjection.java:800-810`) — and, as of the
ADR-023 review, **its charge ledger**.

That last one destroys real, paid AI usage. A review deleted for being clutter takes its token counts,
its model and its cost with it, and no query can reconstruct them. On the dev deployment a single
delete removed four charge lines and 11,454 millicents of genuine spend.

This contradicts the principle ADR-023 was built on. Snapshotting the rate onto each charge line
exists so **a later price edit cannot rewrite history**. That made cost immune to price changes and
left it fully exposed to deletion — the same history, erased by a different mechanism.

Worth recording because it changes how much the current behaviour can be trusted: **delete was never
actually a clean slate.** `review_thread` is deleted nowhere in the codebase, so a re-registered PR
already inherited stale thread rows. The clean-slate property the ledger deletion was defending was
already incomplete.

### Why removing the ledger deletion is safe

The deletion closed a real defect. `review_id` is `ReviewIds.reviewId(repo, pr)` — stable per PR, not
per run — and `llm_charge.review_id` is plain `TEXT` with no foreign key. Left behind, orphaned rows
were not merely unreachable: `costOf` / `listSummaries` / `latestModelFor` key on `review_id` alone, so
**a re-registered PR rendered the deleted run's money and model as its own**, and the new run's
`call_ref` collided with the orphan, discarding a real charge.

Every part of that requires the review row to be **gone** so the PR can be registered afresh. Archiving
keeps the row, so the PR cannot be re-registered and there is no second review to inherit anything. The
hazard is closed by keeping the row, not by destroying the evidence.

## Decisions

**1. Archived is a third dimension, not a status value.** `review_status` already carries two
orthogonal facts: `status` (the review outcome — `completed`, `failed`) and `pr_state` (`OPEN` /
`MERGED` / `CLOSED`), deliberately split in July 2026 because a merged PR and a passed review are
different facts one badge could not carry. Archival is a third. Overwriting `status` would destroy
whether the run completed or failed — precisely the statistic the data is retained for.

**2. One table stays the source of truth.** A parallel archive schema was considered and rejected: every
future migration would have to mirror into it or drift silently; "all reviews ever" would become a
`UNION`; and `event_log` is the **append-only versioned source of truth** (`V1__event_store.sql`) that
`JdbcEventStore.load` reads to rehydrate aggregates, so relocating its rows mutates the event store
rather than archiving a projection. A read-only `live_review` view was also rejected — archived reviews
must be visible in the same table behind a toggle, which a permanently-excluding view fights.

**3. An archived PR is retired**, and the reason is **spend control**, not retention safety. No new
review is created for that PR by a push, a `/review`, a reopen, a re-run or a scheduled retry.

The earlier draft justified this as what makes retention safe. That was wrong, and the correction
matters because the bad reason would have collapsed under the first person to notice it: with nothing
deleted, a resurrected PR's old charges are genuinely its own history and `ReviewRuns.currentRun` stays
correct, so retention does not need retirement at all. The real reason is that an author pushing a
commit must not silently re-bill an operator who archived to be done with it. Retirement is a **cost
boundary**.

It also keeps `review_status`'s `PRIMARY KEY (review_id)` intact, since a per-run review identity would
mean changing the aggregate stream id — an event-store contract.

**4. Unarchive exists, admin-only.** Reversing an archive is one `UPDATE` clearing
`review_status.archived_at` (see Decision 5 — no charge rows need unstamping), so the alternative was
not "no unarchive" but "unarchive by manual SQL", which is a designed-in surgery escape hatch rather
than an absence of a feature.

**5. Charges are stamped at purge, not at archive.** `llm_charge.archived_at` is set by the future
cleanup that hard-deletes a `review_status` row — never by archiving.

Stamping at archive was the first draft and it was self-defeating: the per-review cost reads are keyed
by `review_id` alone and are exactly the reads that serve the **archived review's own** detail page
(`loadDetail` → `chargeLines(reviewId)` + `costOf(reviewId)`, `ReviewProjection.java:994`) and its
Show-archived list row. Filtering them would have shown an archived review a cost of zero and no model,
contradicting both "fully working detail pages" and the entire purpose of retaining the data.

Stamping at purge gives every property wanted: an archived review keeps its own cost visible; a purged
review's orphans are excluded from the re-registered PR that inherits its `review_id`; and statistics
ignore the filter and see everything ever spent.

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

### Which reads filter, and which must not

**`llm_charge` reads filter `archived_at IS NULL`** — their sole purpose is to stop a re-registered PR
inheriting a *purged* review's charges. Under Decision 5 these rows are only stamped after the review
row is gone, so the filter never hides a live or archived review's own data:

- `costOf`, `cumulativeCost`, `unpricedCallsFor`, `latestModelFor`, `chargeLines`
- **all four ledger subqueries inside `listSummaries`** — the cost SUM, the model badge, the unpriced
  count and the carried-over count. Filtering only the SUM leaves an archived model name and an
  unpriced-call count leaking into a re-registered PR's row.
- both queries in the `CostAttentionRow` enum (the cost attention rows live there, not in
  `AttentionQueries.costRows`)

**`review_status` reads filter `archived_at IS NULL`** where they describe live work:

- `listSummaries` (unless the caller asks for archived — see UI)
- `AttentionQueries.reviewRows` — otherwise an archived failed review keeps raising `REVIEW_FAILED`
  forever, the first permanently-lit row in a panel whose contract is "fixing the cause removes the row"

**Point reads by `review_id` must NOT filter** — `commitOf`, `loadDetail` and friends. They answer
"what is this specific review", and an archived review must still answer.

Statistics queries deliberately omit every filter.

## The archive operation

`ReviewProjection.deleteReview` is replaced by `archiveReview(workspace, slug, pr)`. One transaction,
**no deletes**:

```sql
UPDATE review_status
   SET archived_at = now(), retry_at = NULL, answering = false
 WHERE review_id = ? AND archived_at IS NULL AND status <> 'reviewing';
```

`retry_at` is cleared because `ReviewRetryScheduler` sweeps every 5 seconds for due retries
(`claimDueRetries`) and would otherwise resurrect the review minutes after archival. `answering` is
cleared so an archived review does not display a "responding…" pill forever.

**Archiving a running review is refused.** `ResultSaga.ifCurrentRun` guards on commit alone, so an
in-flight worker's results would still write status, findings and charges to a row the spec promises is
frozen — and those late charges would carry `archived_at IS NULL` into a purge, becoming exactly the
orphan the column exists to prevent. Cancel or wait, then archive.

The three outcomes must be distinguishable, and the `UPDATE` cannot distinguish them because its
`WHERE` matches zero rows for all of "no such review", "already archived" and "still running". So
`archiveReview` returns an enum resolved by reading the row inside the same transaction when the
`UPDATE` matches nothing:

```java
enum ArchiveOutcome { ARCHIVED, ALREADY_ARCHIVED, STILL_RUNNING, NOT_FOUND }
```

The resource maps these to 204 / 409 / 409-with-distinct-message / 404. It broadcasts on `ARCHIVED`
only. The broadcast must be a **row update**, not the removal message the delete used — a client with
Show archived enabled needs the row to change in place rather than vanish.

Nothing else is touched: `review_event`, `event_log`, `review_thread`, the worker's
`comment_idempotency` claims and `worker.context_blob` all stay. Retaining `event_log` additionally
keeps `ReviewRuns.currentRun` correct, since it counts `ReviewRequested` rows there.

`deleteWorkerClaims` remains as a method — the re-run path still uses it — but archive does not call it.

### Unarchive

`unarchiveReview` clears `archived_at`, and releases the notice's idempotency claim so that a later
re-archive notifies again. No charge rows are touched, because none were stamped. Admin-only, same
`@RolesAllowed` as archive.

### API and UI surface

`DELETE /api/reviews/{workspace}/{slug}/{pr}` becomes `POST …/archive`, plus `POST …/unarchive`. A
`DELETE` verb that destroys nothing misdescribes the operation to every future reader. The list endpoint
gains `?includeArchived=true`. The UI button changes from **Delete** to **Archive** with matching
confirmation copy.

## Retirement and the one-time notice

Four integration events must consult `archived_at` before their normal handling: `AuthorReplied`,
`ManualCommandReceived`, `PullRequestEventReceived` and **`PullRequestClosed`**. The last was missed in
the first draft and is the one that breaks Decision 1's frozen-`pr_state` promise, because it calls
`projection.setPrState(...)` (`IntegrationSaga.java:96-100`). For `AuthorReplied` the check must run
**before** `threads.markThreadLocation`, which otherwise writes to an archived review.

Two non-event paths also need the gate, and neither is reachable from the saga:

- **`ReviewRerunService`** — driven by `POST …/rerun` (`ReviewsResource.java:142`), REST rather than an
  integration event. Its first act is `clearWorkerIdempotency`, which deletes **all** claims for the
  review including the archived-notice claim, so an ungated re-run both resurrects the review and
  re-arms a notice that is supposed to fire once ever.
- **`ManualRegisterResource`** — returns 200 with a reviewId before the saga drops the event
  (`:111-121`), so an admin sees success and nothing happens. It must answer **409**. A silent
  non-response reading as a lost webhook is the exact failure this project already fixed once, for the
  conversation turn cap.

`ActionCommand.NotifyArchived` is modelled on `NotifyTurnCap`, which exists for the same shape of
problem — a decision to stay silent that a human reads as a lost webhook:

```java
record NotifyArchived(String reviewId, RepoRef repo, long prId, ThreadRef threadRef,
                      String scmCredential) implements ActionCommand {}
```

- **No LLM credential.** Fixed text, so retirement costs no tokens.
- **`threadRef` is nullable.** Non-null routes to `CommentSink.replyInThread`; null routes to
  `CommentSink.postSummary` (the top-level PR comment). The notice posts where the event arrived.
- **One claim per review.** `idempotency.claim(reviewId, ARCHIVED_SLOT, ARCHIVED_NOTICE_KEY)` with
  `ARCHIVED_SLOT` a **constant** in the slot position. Verified sound: the store is
  `PRIMARY KEY (review_id, commit, anchor_key)` with `commit TEXT NOT NULL` and no semantic dependence
  on a real commit — the follow-up path already puts a `threadRef` there — and crash-reclaim via a NULL
  `posted_ref` gives correct retry semantics. An `AlreadyPosted` claim logs at INFO and returns.
- **Its own result event**, `IntegrationEvent.ArchivedNotified(String reviewId, ThreadRef threadRef,
  String commentId)`, registered in `EventKeys` and the `@JsonSubTypes` list. It carries the nullable
  `threadRef` because the `TurnCapNotified` handler uses it for timeline attribution and
  `markAnswerThread` root-linking (`ResultSaga.java:271-279`). Deliberately not `FollowUpPosted`, which
  would bump the conversation turn count for a notice that consumed no turn.

The notice states that the review is archived and that no further reviews will run for this pull
request. It does not invite an @-mention: unlike the turn cap, no policy overrides retirement.

### Accepted consequence

Once per review means a second person replying in a *different* thread gets silence. `NotifyTurnCap`
made the opposite call (one claim per thread) for exactly this reason. Chosen deliberately, recorded so
the trade-off is visible if it proves wrong in use.

## UI

The reviews list defaults to live rows, with a **Show archived** checkbox that includes archived rows
inline in the same table, visually marked. Archived reviews keep fully working detail pages. The
existing filter chips operate on whichever set the checkbox selects. Unarchive is an admin-only action
on the detail page.

## Deliberately not built

- **The purge itself.** Named as a future intention. `llm_charge.archived_at` plus the filter list is
  the groundwork that makes it safe to add; building it now would be speculative. When it is built, it
  must stamp the charges in the same transaction that deletes the review row.
- **A statistics screen.** The retained data makes one possible; the screen is its own work.
- **Backfilling already hard-deleted reviews.** Their rows are gone. The dev deployment's pre-V30
  archive tables remain the only record of that era.

## Testing

1. **Archiving keeps every charge row** — the direct guard on the deletion being reverted. Without it,
   re-introducing the `DELETE` passes everything else.
2. **An archived review's own detail still shows its cost, model and charge lines.** This is the test
   whose absence let the stamp-at-archive error through review.
3. **Archiving preserves `status` and `pr_state`** (Decision 1).
4. **A re-registered PR does not inherit a purged review's charges** — fixture with stamped rows,
   asserting the live review's cost excludes them. The only test that exercises the filter's actual
   purpose.
5. **`listSummaries` filters all four ledger subqueries** — assert the model badge and unpriced count,
   not just the cost SUM.
6. **The notice fires once across two inbound events**, the second logged rather than posted.
7. **The notice brokers no LLM credential.**
8. **Each of the four gated events leaves the archived review unchanged and emits no command.** Phrased
   as "unchanged", not "creates no new row" — the latter cannot fail, since the primary key forbids a
   second row regardless. A vacuous test would have passed here without testing anything.
9. **`PullRequestClosed` does not move `pr_state` on an archived review** (Decision 1's frozen promise).
10. **A re-run of an archived review is refused and its notice claim survives** — covers both halves of
    the `ReviewRerunService` gap.
11. **A scheduled retry does not resurrect an archived review** — archive with `retry_at` set, run the
    sweep, assert nothing dispatched.
12. **`archiveReview` distinguishes all four outcomes.** An enum whose failure values are never
    separately asserted is a boolean wearing a costume.
13. **Archiving a running review is refused** (`STILL_RUNNING`).
14. **Unarchive restores the review and re-arms the notice.**
15. **Register PR on an archived PR answers 409**, not 200.

## Documentation

A new ADR recording that delete became archive; that AI usage retention outranks the clean-slate
property it replaced; that the clean slate was never complete anyway (`review_thread` survived every
delete); and that retirement is a **spend boundary** rather than what makes retention safe. ADR-023's
ledger section gains a pointer, since its "delete is a true clean slate" reasoning no longer describes
the system.

`techdebt/spire-orchestrator/3-3-the-charge-ledger-is-keyed-on-an-id-two-scms-can-share.md` is
unaffected: a workspace name registered on two SCMs still collides, and this design does not address it.

## Scope

One coherent implementation plan. If it needs splitting, the natural seam is **archive core** (V32,
`archiveReview`/`unarchiveReview`, the filters, the gates, the UI) first, and **the notice**
(`NotifyArchived`, `ArchivedNotified`, the worker handler) second — the first is useful without the
second, and the second is meaningless without the first.
