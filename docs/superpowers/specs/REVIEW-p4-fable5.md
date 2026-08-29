# Review — P4 learned memory and analytics design (2026-08-29)

**Spec:** `docs/superpowers/specs/2026-08-29-learned-memory-and-analytics-design.md`
**Reviewer stance:** adversarial; every factual claim checked against the shipped code and migrations.

## Verdict

Not safe to implement as written. The overall direction is sound — the projection-plus-visible-filter
shape, the closed category enum, the nullable verdict, the rejection of username matching and of
prompt injection are all well argued and consistent with the project's own history. But §4.2's write
rules are wrong in three independent ways (the verdict-matching rule misses every carried-forward
finding, the UNIQUE constraint provides no idempotency for exactly the rows it must protect, and
`thread_ref` is never written by any specified write), and §8's Milestone 1 exit criteria assert
behavior the pipeline cannot produce: two of the three named "never posted" cases (observe mode,
refused runs) generate no findings at all, and the third (anchor collisions) is dropped in the worker
before the event the projection reads is ever emitted. §5.2 and §6.4 each leave a load-bearing
mechanism undefined (how identity mappings come to exist; where the suppression filter runs and what
wire change it needs). Fix the six blocking issues below and Milestone 1 is buildable against this
codebase; Milestone 2 is thinner than one milestone's worth of spec and should get its own pass after
the corpus exists.

## Factual errors

The seven claims I was asked to verify are **all correct**:

1. `review_finding` does not exist. No migration creates it; the only references are a comment in
   `spire-review-worker/src/main/resources/db/migration/V5__code_symbol.sql:5` and the
   `docs/DATA-MODEL.md:143` specification the spec cites.
2. The persisted domain stream carries no finding lists. `DomainEvent.ReviewOutcomeRecorded(commit,
   findingsCount, summaryDigest)` — `spire-contract/src/main/java/dev/codespire/contract/event/DomainEvent.java:20-21`.
3. `projection_checkpoint` is dead schema: declared at
   `spire-orchestrator/src/main/resources/db/migration/V1__event_store.sql:26`, referenced by zero Java.
4. `ReviewGenerated(reviewId, prId, commit, result, verdicts, reconcileUsage)` matches the spec
   exactly — `spire-contract/.../event/IntegrationEvent.java:220-230`, with the empty-verdicts
   convenience constructor for first reviews at :227-229.
5. `Finding(path, range, severity, message, suggestion)` has no category —
   `spire-contract/.../review/Finding.java:9`. One production construction site
   (`spire-llm/.../FindingsParser.java:81`) plus 7 test files. The `withCategory` wither requirement
   is correct per the 2026-08-28 lesson.
6. `/api/me` exposes no SCM link: `AuthResource.java:54-62` returns
   `(authEnabled, signedIn, principalName, roles)` and nothing else. (`provider_author` is a per-SCM
   allowlist, not an OIDC link.)
7. `ReviewRuns.currentRun` counts `ReviewRequested` in `event_log` —
   `spire-orchestrator/.../llm/ReviewRuns.java:52-69` — and each `RequestReview` appends one
   (`ReviewLifecycle.java:98`), so it is a valid round number. But see G7 on its failure fallback.

The spec's factual errors are elsewhere, in its claims about what the pipeline produces:

- **FE-1 — observe mode generates no findings.** §4.1 and §8 claim observe-mode findings land in the
  corpus with null `thread_ref`. `ReviewPolicy` observe is "register only, no diff/LLM/comments"
  (`ReviewPolicy.java:14-21`, boot log at :68), and `IntegrationSaga.java:515-548` never starts the
  pipeline in observe mode. There is nothing to record.
- **FE-2 — refused runs generate no findings.** Refusal (ADR-025) happens at `DiffFetched` (diff
  size) or pre-spend in `ResultSaga` — both *before* `GenerateReview` is dispatched. A refused
  review never reaches the LLM, so `ReviewGenerated` never fires and no findings exist. (Degraded
  runs do reach the event — with an empty or partial finding list — and are the legitimate example.)
- **FE-3 — anchor-collision-dropped findings never reach the orchestrator.** `ReviewWorker.java:207-218`:
  `dropAnchorCollisions` runs on the result *before* `results.emit(new ReviewGenerated(...))`, and
  the dropped findings are simply absent from `result.findings()`. A projection fed from
  `ReviewGenerated` cannot record them without a wire change the spec does not propose.
- **FE-4 (trivial)** — "23 tables across the three services" counts `review_llm_call` (dropped by
  V30:107) and the two `_pre_v30` backup snapshots; live tables number 20. Immaterial to any
  conclusion.

## Blocking issues

**B-1. The §4.2 verdict-matching rule is wrong for every carried-forward finding.**
Matching verdicts to `(review_id, round-1, path, start_line)` assumes verdicts judge the previous
round's findings. They do not: `GenerateReview.priorRun` is built from
`review_status.posted_findings_json`, which is `COALESCE(open_findings_json, findings_json)`
(`ReviewProjection.java:458-462`) — the *carried-forward open set* (V20), spanning every earlier
round. A finding raised in round 1, `STILL_OPEN` in rounds 2–3, fixed in round 4: its row sits at
round 1; the round-4 handler updates round 3 and the `RESOLVED` verdict never lands. "Median rounds
from raised to RESOLVED" and the dismissal rate that drives §6's proposals are then systematically
wrong — quietly, since a missed UPDATE affects zero rows and throws nothing. Two aggravators: an
intermediate round that generated but never posted (degraded) shifts what "round-1" even points at,
and verdicts carry the *remapped* paths on renames (`ReviewWorker.java` reconcile-claim comment:
"the persisted verdicts carry the FIRST run's remapped NEW paths"), so even the path half of the key
can miss. **Fix:** match the newest not-yet-judged row per `(path, start_line)` across *all* prior
rounds, preferring the verdict's `threadRef` where present, newest-row-wins — the V26 lesson
(insertion order, no id arithmetic) applied here on day one rather than after the GitLab replay of it.

**B-2. `UNIQUE (review_id, round, path, start_line, category)` protects nothing it needs to.**
Two defects in one constraint. (a) Postgres treats NULLs as distinct in unique indexes, and
`category` is nullable *by design* (§4.3, customized prompts). A redelivered `ReviewGenerated`
passes `ifCurrentRun` in the window before `ReviewCompleted` (`ResultSaga.java:657-664` — it checks
`isReviewing()` + commit, the exact window the V30 double-charge lived in), so the handler re-runs
and every uncategorized row duplicates. The rows most likely to lack a category (customized-prompt
repos) are exactly the ones the constraint silently fails to deduplicate — the ADR-023 shape again.
(b) The constraint is simultaneously too strong: two distinct findings of the same category on the
same line in one round are legitimate model output, and one of them is silently dropped. **Fix:**
make redelivery idempotency explicit in the handler — delete-then-insert all rows for
`(review_id, round)` in one transaction — and either drop the UNIQUE or declare it
`NULLS NOT DISTINCT` (available: both compose files pin `postgres:18.4-alpine`) with an explicit
statement of which duplicate is intentionally collapsed. The spec must state the redelivery story
either way; today it has none.

**B-3. `thread_ref` is never written.** §4.2 says "both writes hang off one event" and specifies
only the insert (at `ReviewGenerated`, before posting — no thread refs exist yet) and the verdict
update. Thread refs are born at `CommentsPosted.inline` (`ResultSaga.java:216+`, where
`markFindingThread` already consumes them). As specified, the column is null on every row and §4.1's
posted/never-posted distinction — plus Milestone 1's exit criterion depending on it — is
unimplementable. **Fix:** specify the third write: on `CommentsPosted`, update the current round's
rows by `(path, line)` from `PostedInline`, acknowledging the anchor-aliasing hazard (V24/V26) and
that the partial-retry branch emits `(anchorKey, 0)` rows that will not match — both already
documented at that call site.

**B-4. §8 Milestone 1 exit criteria assert the impossible; the stale-run posture is undecided.**
Per FE-1/2/3, "including findings that were never posted (observe mode, refused runs, anchor
collisions) — asserted directly" cannot be asserted: none of the three produces a finding the
projection can see. The real never-posted cases are: degraded runs (empty/partial list), per-finding
post failures (generated, insert succeeds, no thread ref lands), and **stale runs** — which the spec
never mentions and which are genuinely undecided: `chargeGeneratedCalls` runs *outside*
`ifCurrentRun` (`ResultSaga.java:202-214`, deliberately — the money was spent), so a superseded
run's spend is in `llm_charge` while its findings would be dropped by the guard. §5.1 joins both in
one dashboard; they will disagree. **Fix:** rewrite the exit criterion around the reachable cases,
and state explicitly whether stale-run findings are recorded (recommend: not recorded, one sentence
saying spend-per-repo therefore includes runs with no finding rows — mirroring how the archived
filter split is documented for `llm_charge`).

**B-5. Per-author analytics keyed on `author_id` alone merges different humans.**
§5.1 scopes "to `review_status.author_id`" — a bare `providerUserId`. The same numeric id on GitHub
and GitLab is two unrelated people, and one workspace name registered on two SCMs is the collision
this project has been bitten by twice (`ReviewProviderResolver`; the symbol-index key gained
`scmType:` for exactly this). `review_status` has carried `provider_type` since V4, and the spec's
own `operator_identity` PK is `(oidc_subject, provider_type)` — so the spec already knows, and then
drops the qualifier in the analytics section. **Fix:** every per-author read groups on
`(provider_type, author_id)`. Note also `techdebt/spire-orchestrator/3-3`: `llm_charge` keys on a
`reviewId` carrying no provider, so the per-repo *spend* lens inherits that known cross-SCM
summing — the spec should cite the debt rather than let analytics rediscover it.

**B-6. The suppression filter (§6.4) has no home and needs a wire change the spec omits.**
`learned_preference` lives in the orchestrator DB; comments are posted by the worker off a
`PostComments` command that `ResultSaga` builds. So either the filter runs in `ResultSaga` between
`ReviewGenerated` and `PostComments` (the natural site — findings and preferences are both in hand,
and `suppressed_by` can be set in the same transaction as the insert), and then `PostComments` must
*carry the suppressed count* for the worker to render "3 findings hidden" into the summary — an
`ActionCommand` change, which per the 2026-08-28 lesson needs its rebuild-site wither and a note
that the contract snapshot will not see it (`techdebt/spire-contract/3-2`); or the filter runs in
the worker, and then the approved preferences must be command-carried like prompts and credentials
(ADR-015 pattern), a larger change. **Fix:** name the site (recommend orchestrator/`ResultSaga`) and
the exact command-shape change, in the spec.

## Gaps to fill

- **G-1. How `operator_identity` rows come to exist.** The mapping is keyed on `oidc_subject`, but
  `/api/me` returns the principal *name* (`AuthResource.java:60`) and no surface anywhere lists
  operators or their subjects. An admin cannot type a subject they cannot see. Specify: `/api/me`
  grows the subject (safe — it describes the caller), plus the admin CRUD endpoint and screen, or a
  link-request flow. Without this M1's "your SCM identity isn't linked" state is permanent for everyone.
- **G-2. "A viewer sees their own author view" is row-level authorization**, which `@RolesAllowed` —
  ADR-022's stated control — cannot express. The endpoint must read the caller's subject server-side
  and filter; the spec should say so and state the failure mode (no mapping → the explicit unlinked
  state, never an empty chart that looks like zero activity).
- **G-3. REST surface unspecified**: paths (under the orchestrator's `/api` prefix), verb/role per
  endpoint (analytics read = viewer; identity mapping and preference CRUD = admin including reads,
  per ADR-022's third rule — the spec says this for the mapping but names no endpoints), and REST vs
  WebSocket for the dashboard (recommend plain REST; nothing here needs live push).
- **G-4. Migration numbers and doc updates.** Orchestrator is at V35; `review_finding`,
  `operator_identity`, `learned_preference` are presumably V36–V38. And `docs/DATA-MODEL.md:143-147`
  already specifies a *different* `review_finding` (has `pr_id`/`comment_id`/BYTEA, lacks
  `round`/`category`/`verdict`/`origin`/`suppressed_by`) — the spec must update DATA-MODEL.md,
  DECISIONS.md (ADR-027), and ROADMAP.md, or the source-of-truth docs contradict the shipped schema
  the day this merges. The spec's own §2 complains about exactly this class of drift.
- **G-5. Retention of `review_finding`.** Unbounded growth, no purge, and ADR-024's future-purge note
  now has a second table to cover. One sentence — "no retention in this milestone, purge rides with
  the ADR-024 purge when it exists" — is enough; silence is not.
- **G-6. Parser behavior on a bad category.** Closed enum, but the model *will* emit an eleventh
  label eventually. Map unknown → `OTHER`, or → null? (Recommend null — `OTHER` is an answer the
  model gave; an unparseable label is *unknown*, and the spec's own §4.1 argues those must differ.)
  Also: old encrypted `findings_json`/`posted_findings_json` blobs and command-carried `PriorRun`
  predate the field — state that lenient parse defaults it null (it will, but say it).
- **G-7. `ReviewRuns` failure fallback is wrong-direction for this consumer.**
  `currentRun` answers `FIRST_RUN` on a read failure (`ReviewRuns.java:58-69`) — the safe direction
  *for the ledger*. For the projection it writes round-N findings under round 1, colliding with or
  silently merging into round 1's rows. Decide: skip the projection write on a round-read failure
  (the corpus loses a round and says so in the log) rather than mis-attribute.
- **G-8. §6.2's `path-shape` normalization is unspecified** — the examples imply an algorithm
  (when does a path generalize to `**/test/**` vs a directory prefix vs `**/*.test.ts`?) that must be
  deterministic across nights, because "a rejected proposal is not regenerated" depends on the group
  identity being stable. Also: threshold defaults (N, P) are never given.
- **G-9. Suppressed findings recur every round.** A suppressed finding is never posted, so it never
  enters `posted_findings_json`, so the next round's exclusion list does not contain it — the model
  regenerates it, the filter re-suppresses it, and a new row lands at every round. Suppression
  counts and category totals inflate per round for as long as the preference holds. Either accept
  and document (the counts measure "suppressions", not "findings"), or add suppressed priors to the
  exclusion list — which changes §6.4's "revocation restores them on the next review" and must be
  reconciled with it.
- **G-10. `FindingVerdict.note` is discarded.** The reconcile call's explanation of a `STILL_OPEN`
  gap (encrypted-worthy per its own javadoc, `FindingVerdict.java:4-6`) has no column. Probably fine
  — but state that it is dropped on purpose.
- **G-11. UI is not planned to this project's own standard.** "The dashboard screens" and one card
  mock; no routes, nav placement, viewer/admin gating, or empty states beyond one line. The ADR-025
  `refused` incident is the standing reason UI vagueness is expensive here. At minimum: where
  analytics lives in the nav, the two lenses' screens, the Settings → Memory screen, and the
  unlinked-identity state.

## Non-blocking observations

- §2's three verified negatives are accurate and valuable — 2.1 and 2.3 in particular are drift
  between the source-of-truth docs and the schema that needed recording. The honesty about the empty
  corpus (§3) and the refusal to backfill are right, and the §8 "not an exit criterion" paragraph is
  the rung-2 lesson correctly applied.
- The filter-over-prompt-injection argument (§6.4) is the strongest section: it correctly
  generalizes the two "looked installed, was not" incidents, and a counted filter genuinely cannot
  fail that way.
- Conversation-raised rows (`ConversationFindingRaised`, `DomainEvent.java:45-47`) have null
  category *and* null message, and customized-prompt repos have null category on every row — so
  learned memory is structurally inert for both. Acceptable, but worth one sentence in §4.3 so an
  operator with a customized template knows why no proposal ever appears.
- The projection write site the spec implies (orchestrator, driven imperatively from
  `ResultSaga.onReviewGenerated` → a `ReviewProjection`-style class) is real and has precedent, as
  does the encryption helper (`EncryptionService.encryptString(value, reviewId)` used at
  `ReviewProjection.java:423`) and the nightly job (`@Scheduled` precedents:
  `SymbolIndexRetention.java:24`, `AttentionBroadcaster.java:85`). None of these blocks anything.
- Scope: this is one-and-a-half specs. M1 is nearly complete once B-1..B-5 are fixed; M2 (§6) is a
  direction statement with its load-bearing mechanics (G-8, B-6, G-9) unspecified. Given M2 is
  explicitly gated behind a validated corpus that will take weeks to accrue, cutting §6 down to the
  schema-and-intent it already is and writing the M2 spec when the corpus exists would match how
  this project has sequenced everything since the rung gates — and avoids speccing a proposal engine
  against data nobody has seen.
- `origin` VARCHAR values (`review`/`conversation`) vs the UI's existing `'conversation'` tag — fine,
  just keep the literal identical to what `PriorFinding`/the findings card already uses.
