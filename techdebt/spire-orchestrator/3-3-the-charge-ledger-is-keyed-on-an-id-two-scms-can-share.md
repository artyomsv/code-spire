# The charge ledger is keyed on a review id that two SCM platforms can share

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Medium |
| Location | `spire-orchestrator/.../readmodel/ReviewProjection.java` (`costOf`, `listSummaries`, `cumulativeCost`, `latestModelFor`), `.../attention/AttentionQueries.java` (`costRows`), `llm_charge.review_id` (V30) |
| Found during | ADR-023 LLM cost accounting — PR #40 security review |
| Date | 2026-08-07 |

## Issue

`reviewId` is `review::{workspace}/{slug}#{prId}` (`ReviewIds.reviewId`) and carries **no SCM or provider
component**. Its own javadoc states the assumption: *"the id IS the address; nothing else is needed."*

That assumption is already known to be false in this deployment's reality, and the project has the
incident on record. The 2026-07-25 parity run found workspace `artyomsv` registered as **both** a GitHub
org and a Bitbucket workspace, which cross-wired the two SCMs. The fix at the time was to store
`provider_type` on the review row and disambiguate at each *resolution* point — the conversation saga,
the self-loop guard, the thread-refetch endpoint and the credential path all now share
`ReviewProviderResolver`. **The id itself was deliberately left ambiguous**, with disambiguation added
around it.

`llm_charge.review_id` (V30) inherits that address, and every money read keys on it alone:
`costOf`, `listSummaries`, `cumulativeCost`, `latestModelFor` and `AttentionQueries.costRows`. So for a
workspace name registered on two platforms, PR #7 on the GitHub repo and PR #7 on the same-named
Bitbucket repo are **one row set**: a shared cost total, and a model badge taken from whichever platform
wrote last.

## Risks

Scoped honestly, because it is narrower than it first sounds:

- **Charges are not dropped.** Commit shas differ across platforms, so the `REVIEW`/`RECONCILE`
  `call_ref`s do not collide. The damage is a *shared total* and a *wrong model attribution*, not a lost
  charge line.
- **It is not attacker-driven.** Cross-repo pollution requires the operator to have registered both
  repositories themselves. This is a correctness bug under a supported configuration, not an exploit.
- **It is pre-existing.** The id scheme predates this branch by months.

What is genuinely new — and the reason this is filed rather than shrugged off — is that **this is the
first table where the collision costs money and cannot be reconciled afterwards.** Every prior
consequence of the shared id was a display or routing fault that the `provider_type` disambiguation
fixed in place. A ledger is different: once two platforms' spend is summed under one key, no later query
can separate them, because the rows never recorded which platform they belonged to. Reviews can be
re-run; a spend history cannot be reconstructed.

It also matters for what comes next. The fleet cost/abuse caps task is the immediate successor to
ADR-023, and a per-repo daily spend cap reading these same queries would inherit the collision exactly:
two unrelated repositories on two unrelated platforms would share one budget, and the first to spend
would throttle the second. A cap is precisely the feature where this stops being cosmetic.

Filed at Medium, not High, because it needs a specific dual-registration to manifest and loses no data
when it does not.

## Suggested Solutions

1. **Add `provider_type` to the ledger** (the fix, and deliberately the narrow one): a column on
   `llm_charge`, included in `call_ref`, and added to every `WHERE review_id = ?` above. The review row
   already stores `provider_type`, so the value is available at write time with no new plumbing, and the
   change is confined to one table and its five readers.
2. **Put the provider in `reviewId` itself.** This is the root fix and it is **not** recommended here:
   `reviewId` is the `ReviewLifecycle` aggregate stream id (CONTRACT §2), so changing its shape rewrites
   every event-store stream, every Kafka key and `ReviewIds.parse`'s contract. If it is ever done it
   belongs in its own ADR, not inside a ledger fix. Recorded so the next reader knows it was considered
   and why the narrow option was preferred.
3. **Leave it and document the constraint** — state in the operator docs that one workspace name must
   not be registered on two platforms. Weakest option: the registry does not enforce it, nothing warns,
   and the project has already discovered by accident that operators do exactly this.

Whichever is chosen, the `ReviewIds` javadoc claim *"the id IS the address; nothing else is needed"*
should be corrected — it is the sentence that makes this easy to re-derive wrongly, and it has now
misled at least twice.
