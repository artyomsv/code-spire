# Learned memory and review analytics — design

**Status:** proposed
**Date:** 2026-08-29
**Roadmap item:** P4 (FR-10 learned memory, FR-11 per-author analytics)
**Proposed ADR:** ADR-027 — findings are retained as a queryable projection, and a learned
preference filters visibly rather than steering invisibly

---

## 1. What this builds, in one paragraph

Reviews today produce findings, post them, reconcile them on the next round, and then forget them.
This adds the durable record of that work — `review_finding`, a queryable projection with one row
per finding per round — and two readers of it. **Analytics** answers what the reviewer has actually
been doing, per repository and per author. **Learned memory** notices when a team keeps dismissing
the same kind of finding, proposes that as a preference an operator approves in the dashboard, and
then filters matching findings out of future reviews — visibly, with a count and a link to what was
hidden. No model is trained, no embedding is computed, and nothing is suppressed that an operator
did not approve.

## 2. The three things that are not true today

Each of these was verified against the shipped schema and code, not inferred from the docs. Two of
them contradict documents this repository treats as the source of truth, which is why they lead.

**2.1 `review_finding` does not exist.** `DATA-MODEL.md` §143 specifies it in full — *"the review
output — persisted for dashboard / analytics / memory"*, with `severity`, `path`, `start_line`, and
an encrypted `message` — and no migration creates it. `DECISIONS.md` (ADR-026) and
`spire-review-worker`'s `V5__code_symbol.sql` both cite it as an existing precedent for the
"coordinates in clear, content encrypted" split. It is not one. There are 23 tables across the three
services and this is not among them. (`review_thread` *is* real and does carry `path`/`line`/
`resolved` in clear, so that half of the ADR-026 sentence stands.)

**2.2 Findings are not retained anywhere durable, so there is no corpus to learn from.** The
persisted domain stream carries `ReviewOutcomeRecorded(commit, findingsCount, summaryDigest)` — a
count and a digest. The findings themselves ride inline on integration events (`ReviewGenerated`,
`CommentsPosted`), which live on Kafka under ADR-014's deliberately short retention.
`review_status.posted_findings_json` holds only the **last posted round**, encrypted, overwritten
every round. This is ADR-011 working exactly as specified: diffs are never persisted, findings ride
inline, the bus forgets. The consequence nobody had drawn is that the corpus P4 was going to learn
from is being discarded continuously.

**2.3 `projection_checkpoint` is dead schema.** Declared in `V1__event_store.sql`, referenced by zero
lines of Java. `ReviewProjection` is driven imperatively from the sagas (`ResultSaga`,
`IntegrationSaga`, `DomainEventSink`, `ConversationSaga`) rather than by replaying the log, so the
read model's "rebuildable, not a source of truth" comment describes an intention, not a mechanism.
This spec does not build that mechanism; it records the debt so the next reader does not plan around
a rebuild that cannot happen.

## 3. Consequences of 2.2, stated up front

**The corpus starts empty and accrues from the day the projection ships.** There is no backfill.
This is a deliberate choice, and it is the same shape as the symbol index (ADR-026 rung 2), which
had 46% recall after one review and grows with traffic. A partial salvage was considered — reading
`posted_findings_json` for the last round of each existing review — and rejected: it would produce
exactly one round per review, with no verdicts, and rows that look like history while being a
single unrepresentative snapshot. An empty dashboard that fills honestly is better than a populated
one whose numbers mean something different from the numbers beside them.

**Therefore FR-10 cannot be measured on the day it ships**, and the spec must not pretend otherwise.
The first learned preference worth approving needs enough reviews to cross a support threshold, and
on a low-traffic deployment that is weeks. This is written into §8's exit criteria rather than
discovered later — the rung 2 evidence gate returned a null nobody could interpret because the
corpus was too thin, and that was only discovered after the money was spent.

## 4. The projection

### 4.1 Shape

`review_finding`, in the orchestrator schema (it is projected from events the orchestrator already
consumes, and it is read by the dashboard the orchestrator serves).

| Column | Type | Notes |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `review_id` | VARCHAR(512) | `ReviewIds` form; carries workspace/slug/pr |
| `round` | INT | 1-based; from `ReviewRuns` (counts `ReviewRequested` in the stream) |
| `commit_sha` | VARCHAR(64) | the head the finding was generated against |
| `path` | TEXT | **clear** — aggregation key |
| `start_line` · `end_line` | INT | **clear** |
| `severity` | VARCHAR(16) | **clear** — `BLOCKER`/`MAJOR`/`MINOR`/`INFO`/`NIT` |
| `category` | VARCHAR(32) | **clear**, nullable — see §4.3 |
| `origin` | VARCHAR(16) | `review` \| `conversation` (a `/finding` command) |
| `message` | TEXT | **Tink-encrypted**, AAD = `review_id` — may quote source |
| `suggestion` | TEXT | **Tink-encrypted**, nullable |
| `thread_ref` | TEXT | nullable; null when the finding was generated but never posted |
| `verdict` | VARCHAR(16) | **nullable** — `RESOLVED`/`STILL_OPEN`/`ACKNOWLEDGED`/`SUPERSEDED`/`UNCHANGED` |
| `verdict_at` | TIMESTAMPTZ | nullable |
| `suppressed_by` | BIGINT | nullable FK to `learned_preference` — see §6.4 |
| `created_at` | TIMESTAMPTZ | |

`UNIQUE (review_id, round, path, start_line, category)`.

Three of these are load-bearing in ways that are not obvious:

**`round` is in the key** because `review_status` overwrites per round. That overwriting is what made
the reviews list read "1 → 2 findings" as though a fix had made things worse (the ADR-023 review
round), and a corpus with the same amnesia cannot answer "did this get fixed" at all.

**`verdict` is nullable, and `NULL` means *not yet judged*** — genuinely distinct from "judged and
unchanged". A column that conflated them would count every never-reconciled finding as a dismissal
and inflate the rate that drives §6's proposals. This is the ADR-023 lesson applied before it bites:
four separate places turned *unknown* into *zero* there, each individually defensible.

**`thread_ref` is nullable** because a finding can be generated and never posted — observe mode, a
refused or degraded run, or an anchor collision dropping it. Those findings are real output and
belong in the corpus; a projection fed only from `CommentsPosted` would silently under-count exactly
the runs an operator most wants to understand.

### 4.2 How rows are written

Both writes hang off **one** event. There is no separate reconciliation event: `ReviewGenerated`
carries `(reviewId, prId, commit, ReviewResult result, List<FindingVerdict> verdicts, ModelUsage
reconcileUsage)`, where `verdicts` is empty on a first review and populated by the ADR-019 reconcile
flow on every later round. So one handler does both halves:

1. **Insert** one row per finding in `result`, at the current round, `verdict` null. This is the
   generation record, written independently of whether posting later succeeded.
2. **Update** the *prior* round's rows from `verdicts`, setting `verdict` and `verdict_at`. Matching
   is by `(review_id, round-1, path, start_line)`, preferring the verdict's own `threadRef` where it
   has one — several `review_thread` rows can share an anchor across rounds, and picking an older one
   is the ADR-019 defect exactly.

The round for both comes from `ReviewRuns`, which counts `ReviewRequested` events in the review's own
stream. **Not `review_status.attempt`**, which is the auto-retry counter: reusing it would give one
paid review several round numbers, the same trap that made `CallRefs` double-charge.

`ConversationFindingRaised` (a `/finding` command) inserts directly with `origin='conversation'`, and
carries no message — that event deliberately omits it per `DATA-MODEL.md` §5, so the column stays
null for these rows rather than being invented.

Archived reviews keep their rows. ADR-024 deletes nothing, and the analytics reads filter
`archived_at` for "what is live" while ignoring it for "what has happened" — the same split
`llm_charge` already draws between its per-review reads and the spend-window read.

### 4.3 `category`, and why it is a closed enum

`Finding` today is `(path, range, severity, message, suggestion)`. Severity alone cannot express a
preference anyone would recognise as theirs: *"the team dismissed 40 MINORs"* is not actionable,
*"the team dismisses naming findings in test files"* is.

`Finding` gains `category`, emitted by the model from a **closed** set:

```
NAMING · ERROR_HANDLING · TEST_COVERAGE · PERFORMANCE · SECURITY
CORRECTNESS · STYLE · DOCS · COMPLEXITY · OTHER
```

Closed, because a free-text category from a language model produces a long tail of near-duplicate
labels (`naming`, `Naming`, `variable naming`, `poor name`) that groups nothing — and grouping is the
entire purpose. `OTHER` exists so the model always has a valid answer and never invents an eleventh.

**Nullable, and that is a compatibility requirement rather than laziness.** Prompts are
operator-customizable per repository since E16, so a customized `REVIEW` template will not ask for
the new field and its findings will arrive without one. Those rows degrade to
severity-plus-path grouping rather than failing. The V33 default-drift banner already exists to tell
that operator their template is behind — the same mitigation ADR-026 recorded when `{{code_context}}`
was added.

Mechanically this is: one line in `PromptCatalog`'s declared output schema, one in `FindingsParser`,
one component on the `Finding` record. **The record change needs a `withCategory` wither**, for the
reason the 2026-08-28 work recorded: adding a component to a wire record compiles at every rebuild
site because the shorter convenience constructors stay valid, and the field is silently dropped.

## 5. Analytics (FR-11)

### 5.1 What is shown

One projection, two lenses, both derived by SQL over the clear columns — nothing decrypts to build a
chart.

**Per repository:** findings over time by severity and category; dismissal rate
(`ACKNOWLEDGED + UNCHANGED` over judged findings); median rounds from raised to `RESOLVED`; share of
runs that were degraded or refused; spend, joined from `llm_charge`.

**Per author:** the same, scoped to `review_status.author_id`.

Explicitly not built: a leaderboard, a composite "quality score", or any ranking of one person
against another. FR-11 asks for aggregate activity and quality per author; it does not ask for a
comparison, and a single score is the form most likely to be read as a verdict on a person while
being mostly a function of what they happened to be working on.

### 5.2 Identity, which is the riskiest part of P4

`/api/me` returns `{authEnabled, authenticated, user, roles}`, where `user` is the OIDC principal
name. Nothing links it to `review_status.author_id` (the SCM `providerUserId`). Self-visible metrics
require that link, and it must be **explicit and admin-managed**:

```
operator_identity (oidc_subject TEXT, provider_type VARCHAR(64),
                   author_id VARCHAR(255), PRIMARY KEY (oidc_subject, provider_type))
```

**Matching on username strings is ruled out, not offered as a cheap first version.** A coincidental
match between an OIDC `preferred_username` and an SCM handle shows one person another person's
performance data, and nothing in the UI would look wrong — the failure is silent, it is about a named
individual, and it is exactly the class ADR-022 was built to prevent when it made cookie scoping a
real mechanism instead of a convention.

An operator with no mapping sees the repository views and an explicit *"your SCM identity isn't
linked — ask an admin"* state. They never see a default, and they never see someone else's numbers by
accident.

Authorization follows ADR-022's existing rules: a viewer sees repository analytics and their own
author view; an admin sees any author's. The mapping table itself is configuration, so it is
admin-only including its reads, per the third rule (*a listing is an inventory*).

## 6. Learned memory (FR-10)

### 6.1 The loop

```
review_finding ──(§6.2 support threshold)──> proposal
     ▲                                          │
     │                                    operator approves
     │                                          ▼
     └──(§6.4 filter, counted)────────── learned_preference
```

### 6.2 What generates a proposal

A nightly job groups judged findings by `(category, path-shape, severity)` within a scope
(repository, or workspace) and proposes a preference when a group has **at least N judged findings**
and **at least P% of them dismissed** (`ACKNOWLEDGED` or `UNCHANGED`). `path-shape` is a normalised
glob (`**/test/**`, `**/*.test.ts`, a directory prefix), not a literal path — a preference about one
file is not a preference.

Both thresholds are operator-visible settings and both are rendered on the card:

> **14 of 16** `NAMING` findings under `**/test/**` in `acme/widgets` were dismissed.
> *Threshold: 10 findings, 75% dismissed. [View the 16 findings] [Approve] [Reject] [Scope to repo]*

Showing the evidence and the threshold on the card is not decoration. A proposal from eleven data
points is the rung 2 gate's failure recurring — a null produced by a corpus too thin to speak, which
nobody could see was thin because the numbers were not on screen.

### 6.3 Approval

`learned_preference (id, scope_type, scope_value, category, path_glob, severity, action,
state, proposed_at, decided_at, decided_by, evidence_json)`.

`state ∈ {PROPOSED, APPROVED, REJECTED}`. A rejected proposal is **not regenerated** — the group is
remembered as declined, so the operator is not asked the same question every night. Approval is
admin-only (it changes review behaviour, which is the *can it spend money / is it configuration*
test). A preference can be revoked at any time, and revocation takes effect on the next review with
no rebuild.

### 6.4 What an approved preference does

**It filters after generation, deterministically, and every suppression is recorded.** The model
reviews exactly as it does today; matching findings are dropped before posting, `review_finding` keeps
the row with `suppressed_by` set, and the summary comment plus the dashboard card both say:

> *3 findings hidden by learned preferences. [View]*

Prompt injection was the alternative and is deliberately not built in this milestone. It might
produce *better* findings rather than merely fewer, but you cannot tell whether the model honoured
the instruction, and a finding it silently skipped leaves no trace anywhere. This project has twice
paid for a mechanism that looked installed and was not — the LLM circuit breaker recording a failed
future as a success, and the ADR-023 `0` that meant *unknown*. A filter that counts what it removed
cannot fail that way: if a learned rule is wrong, the count is visible, the findings are one click
away, and revocation is immediate.

The suppressed rows stay in the corpus. A preference that starts hiding findings the team would have
acted on is detectable precisely because the rows are still there to be counted.

## 7. Build sequence

**Milestone 1 — corpus and analytics.** `review_finding` (+ migration), the two projection writes,
`category` on `Finding` through prompt/parser/wire, `operator_identity`, the analytics queries, and
the dashboard screens. Analytics is the first reader on purpose: it is the only way to tell a correct
projection from a wrong one, because a bad number is visible immediately and a bad row is not. This
mirrors ADR-023, where building the ledger and then reading it back is what exposed four separate
places that had turned *unknown* into *zero*.

**Milestone 2 — memory.** The nightly proposal job, `learned_preference`, the Settings → Memory
screen, and the post-generation filter with its counted suppression. Deferred behind M1 because a
proposal engine over an unvalidated corpus proposes confident nonsense, and because the approval
screen is an analytics view with two buttons — most of it is built by then.

## 8. Exit criteria

**Milestone 1.** Every finding a review generates appears in `review_finding` with its round,
including findings that were never posted (observe mode, refused runs, anchor collisions) — asserted
directly, since that is the case a `CommentsPosted`-fed projection would silently miss. A verdict
lands on the prior round's rows when reconciliation runs, and an unjudged finding reads `NULL` rather
than a default. An operator with no identity mapping sees the unlinked state and no author data. The
per-repo dashboard renders correctly with an empty corpus.

**Milestone 2.** A proposal is generated only above both thresholds, and its card shows the evidence
count and the thresholds. An approved preference suppresses matching findings, the count is shown on
the summary comment and the dashboard, the suppressed rows remain in `review_finding` with
`suppressed_by` set, and revocation restores them on the next review. A rejected proposal does not
reappear.

**Not an exit criterion, and stated so it is not assumed:** that a learned preference makes reviews
*better*. That needs the corpus §3 says does not exist yet, and it is the same downstream claim
ADR-026 left explicitly unproven for the symbol index. It belongs in a field-verification issue like
[#89](https://github.com/artyomsv/code-spire/issues/89), not in this spec's definition of done.

## 9. Rejected alternatives

**Backfilling from `posted_findings_json`** — one unrepresentative round per review, no verdicts, and
rows that look like history. §3.

**Deriving categories from message text** — no wire change, but messages are encrypted (so every pass
decrypts the corpus) and clusters shift whenever the model or prompt changes, making a learned
preference unstable for reasons no operator could see.

**Extending `review_thread` instead of a new table** — it is per posted thread, so it misses
everything never posted, and several rows share one anchor across rounds; that aliasing is what
caused the ADR-019 reconciliation defect on GitLab.

**Username-matching for identity** — §5.2. A silent, person-level privacy failure.

**Prompt injection for approved preferences** — §6.4. Deferred, not rejected outright; it wants a
corpus that has already proven the preferences are sound.

**A composite per-author quality score** — §5.1. Mostly a function of what someone was assigned.

**Writing findings to the domain event log** so future rebuilds are complete — a deliberate reversal
of ADR-011's minimize-stored-source posture, needing its own ADR, since findings quote source. The
projection is the durable record; the log stays as it is.
