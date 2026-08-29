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
"coordinates in clear, content encrypted" split. It is not one. There are 20 live tables across the
three services and this is not among them (a raw count says 23 — it includes `review_llm_call`,
dropped by V30, and two `_pre_v30` backup snapshots). (`review_thread` *is* real and does carry `path`/`line`/
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

**No unique constraint. Idempotency is the handler's job** — see §4.2. A key over
`(review_id, round, path, start_line, category)` was specified first and is wrong twice over:
Postgres treats NULLs as distinct, so it would silently fail to deduplicate exactly the rows
§4.3 leaves uncategorized (a customized prompt), which is the ADR-023 shape again; and it is
simultaneously too strong, because two distinct findings of the same category on the same line
are legitimate model output and one would vanish. `NULLS NOT DISTINCT` fixes only the first half
(verified on the deployment's own Postgres 18.4: the plain form accepts a second `(1, NULL)`, the
`NULLS NOT DISTINCT` form rejects it) and is therefore not used.
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

**Three writes, from two events**, and the round number comes from `ReviewRuns` (which counts
`ReviewRequested` in the review's own stream — **not** `review_status.attempt`, the auto-retry
counter, which would give one paid review several round numbers).

**Write 1 — insert, on `ReviewGenerated`.** One row per finding in `result`, at the current round,
`verdict` and `thread_ref` null. This is the generation record, written independently of whether
posting later succeeds.

*Redelivery is handled by delete-then-insert of all rows for `(review_id, round)` in one
transaction*, not by a unique constraint. A redelivered `ReviewGenerated` passes `ifCurrentRun` in
the window between generation and `ReviewCompleted` — it checks `isReviewing()` plus the commit,
which is the exact window the V30 double-charge lived in — so the handler genuinely re-runs and
must be safe when it does.

*On a round-read failure the write is skipped and logged.* `ReviewRuns.currentRun` answers
`FIRST_RUN` when it cannot read, which is the safe direction for the ledger and the wrong one here:
it would file round-N findings under round 1 and merge them into round 1's rows. Losing a round
from the corpus is recoverable; mis-attributing one is not.

**Write 2 — thread refs, on `CommentsPosted`.** `CommentsPosted.inline` is a
`List<PostedInline(threadRef, path, line)>`; each entry updates the current round's row matching
`(path, start_line)`. Two hazards, both already documented at that call site: several rows can
share an anchor across rounds (V24/V26 — newest row per location wins, never id arithmetic), and
the partial-retry branch emits `(anchorKey, 0)` entries that match nothing and are skipped rather
than written as line 0.

**Write 3 — verdicts, on the *next* round's `ReviewGenerated`.** `ReviewGenerated` carries
`List<FindingVerdict>` (empty on a first review, populated by the ADR-019 reconcile flow),
so this rides the same event as write 1.

**The matching rule is the subtle part, and the obvious rule is wrong.** Verdicts do not judge the
previous round's findings. `GenerateReview.priorRun` is built from `posted_findings_json`, which is
`COALESCE(open_findings_json, findings_json)` — the *carried-forward open set* (V20), spanning every
earlier round. A finding raised in round 1, `STILL_OPEN` through rounds 2 and 3, fixed in round 4
still has its row at **round 1**; a rule matching `round - 1` would update round 3, and the
`RESOLVED` verdict would never land. A missed `UPDATE` affects zero rows and throws nothing, so
"median rounds to resolved" and the dismissal rate driving §6 would be quietly, systematically
wrong.

So: **match the newest not-yet-judged row per `(path, start_line)` across all prior rounds**,
preferring the verdict's own `threadRef` where it has one, newest row winning. That is the V26
lesson — recency is insertion order, never id arithmetic — applied on day one instead of after a
replay of the GitLab defect. Two aggravators make the naive rule worse than it looks: an
intermediate round that generated but never posted shifts what "the previous round" even points at,
and on a rename the persisted verdicts carry the *first* run's remapped paths, so even the path
half of a round-indexed key can miss.

**Stale runs are not recorded.** `ifCurrentRun` drops a superseded run's result, and this
projection sits inside that guard. The consequence is real and is documented rather than
discovered: `chargeGeneratedCalls` runs *outside* the guard on purpose — the money was spent — so a
repository's spend can include runs that contributed no finding rows, and §5.1's two lenses will
not reconcile exactly. This is the same shape as the archived-filter split already documented for
`llm_charge`.

`ConversationFindingRaised` (a `/finding` command) inserts directly with `origin='conversation'`,
and carries no message — that event deliberately omits it per `DATA-MODEL.md` §5 — so `message`
and `category` stay null rather than being invented.

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

Mechanically this is: one line in `PromptCatalog`'s declared output schema, one in
`FindingsParser`, one component on the `Finding` record — one production construction site, seven
test files. **The record change needs a `withCategory` wither**, for the reason the 2026-08-28 work
recorded: adding a component to a wire record compiles at every rebuild site because the shorter
convenience constructors stay valid, and the field is silently dropped.

**An unrecognised label parses to `null`, not to `OTHER`.** The model will eventually emit an
eleventh word. `OTHER` is an answer the model gave; an unparseable label is *unknown*, and §4.1
already argues those must not be conflated — the same distinction the nullable `verdict` column
rests on. Old encrypted `findings_json` blobs and command-carried `PriorRun` values predate the
field entirely and parse to null by the same lenient path.

**Two populations are structurally inert for learned memory, and an operator should be told why.**
Conversation-raised findings carry neither category nor message, and a repository with a customized
`REVIEW` template carries no category on any row. Neither can ever cross a §6.2 threshold, so no
proposal will ever appear for them. The Memory screen says so explicitly rather than showing an
empty list that looks like "nothing to learn yet".
## 5. Analytics (FR-11)

### 5.1 What is shown

One projection, two lenses, both derived by SQL over the clear columns — nothing decrypts to build
a chart.

**Per repository:** findings over time by severity and category; dismissal rate
(`ACKNOWLEDGED + UNCHANGED` over judged findings); median rounds from raised to `RESOLVED`; share of
runs that were degraded or refused; spend, joined from `llm_charge`.

**Per author:** the same, grouped on **`(provider_type, author_id)`** — never `author_id` alone.
A bare `providerUserId` is not a person: the same id on GitHub and GitLab is two unrelated humans,
and one workspace name registered on two SCMs is the collision this project has already been bitten
by twice (hence `ReviewProviderResolver`, and hence the symbol index key carrying `scmType:`).
`review_status` has carried `provider_type` since V4, so the qualifier costs nothing.

**One known inaccuracy, cited rather than rediscovered.** The spend lens inherits
`techdebt/spire-orchestrator/3-3`: `llm_charge` keys on a `reviewId` that carries no provider, so a
workspace name registered on two SCMs sums two unrelated repositories. Analytics does not fix that
debt; it must not silently inherit it either, so the per-repo spend figure carries the caveat where
it is rendered.

Explicitly not built: a leaderboard, a composite "quality score", or any ranking of one person
against another. FR-11 asks for aggregate activity and quality per author; it does not ask for a
comparison, and a single score is the form most likely to be read as a verdict on a person while
being mostly a function of what they happened to be working on.

### 5.2 Identity, which is the riskiest part of P4

`/api/me` returns `{authEnabled, authenticated, user, roles}`, where `user` is the OIDC principal
name. Nothing links it to `review_status.author_id`. Self-visible metrics require that link, and it
must be **explicit and admin-managed**:

```
operator_identity (oidc_subject TEXT, provider_type VARCHAR(64),
                   author_id VARCHAR(255), created_at TIMESTAMPTZ,
                   PRIMARY KEY (oidc_subject, provider_type))
```

**Matching on username strings is ruled out, not offered as a cheap first version.** A coincidental
match between an OIDC `preferred_username` and an SCM handle shows one person another person's
performance data, and nothing in the UI would look wrong — the failure is silent, it is about a
named individual, and it is exactly the class ADR-022 was built to prevent when it made cookie
scoping a real mechanism instead of a convention.

**`/api/me` grows a `subject` field**, without which the mapping cannot be administered at all: the
table is keyed on the OIDC subject, and no surface anywhere lists operators or their subjects, so an
admin would be asked to type a value they cannot see. Returning the caller their own subject is safe
— it describes the caller and nobody else. An operator reads it from their own profile view and
gives it to an admin, who creates the mapping.

**The per-author endpoint enforces row-level authorization in code, not by annotation.**
`@RolesAllowed` — ADR-022's stated control — cannot express "a viewer may read their own row". The
resource reads the caller's subject server-side, resolves it through `operator_identity`, and
refuses any other author unless the caller is `spire-admin`. An operator with no mapping gets the
explicit *"your SCM identity isn't linked — ask an admin"* state, **never an empty chart**, which
would read as "you have done nothing" rather than "we do not know who you are".

### 5.3 REST surface

All under the orchestrator's `/api` prefix (ADR-022 scopes the session cookie to it), plain REST —
nothing here needs live push, and the reviews socket already carries the only genuinely live state.

| Endpoint | Role |
|---|---|
| `GET /api/analytics/repos` · `GET /api/analytics/repos/{ws}/{slug}` | `spire-viewer` |
| `GET /api/analytics/authors/{providerType}/{authorId}` | viewer **for their own mapped identity only**; `spire-admin` for any |
| `GET /api/me` (gains `subject`) | any signed-in operator |
| `GET·POST·DELETE /api/operator-identities` | `spire-admin`, **including reads** |
| `GET·POST /api/memory/preferences`, `POST /api/memory/preferences/{id}/{approve\|reject}` | `spire-admin`, **including reads** |

The registries are admin-only including their reads because ADR-022's third rule makes every
registry so: a listing is an inventory. `operator_identity` is worse than an inventory — it is a map
from real people to their activity.
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

A nightly `@Scheduled` job (the `SymbolIndexRetention` precedent) groups judged findings by
`(scope, category, path_glob, severity)` and proposes a preference when a group has **at least N**
**judged findings** and **at least P% dismissed** (`ACKNOWLEDGED` or `UNCHANGED`). Defaults
**N = 10, P = 75**, both operator-editable settings.

**`path_glob` is produced by a fixed ladder, not by judgement**, because §6.3 remembers a rejected
group and must be able to recognise the same group tomorrow. Each finding path yields exactly one
glob, by the first rule that matches:

1. a path segment equal to `test`, `tests`, `spec` or `__tests__` → `**/<segment>/**`
2. a filename matching `*.test.*` or `*.spec.*` → `**/*.test.*` (or `.spec.`)
3. otherwise → the directory prefix up to and including the **second** path segment, plus `/**`
   (`spire-ui/src/components/Foo.tsx` → `spire-ui/src/**`)

Rule 3 is deliberately coarse. A glob that resolves to one file is not a preference, and a ladder
that sometimes generalises further would make group identity depend on the corpus's shape on the
night it ran — so a rejected proposal would silently return under a different name.

Both thresholds and the evidence are rendered on the card:

> **14 of 16** `NAMING` findings under `**/test/**` in `acme/widgets` were dismissed.
> *Threshold: 10 findings, 75% dismissed. [View the 16 findings] [Approve] [Reject] [Scope to repo]*

Showing the evidence and the threshold is not decoration. A proposal from eleven data points is the
rung 2 gate's failure recurring — a null produced by a corpus too thin to speak, which nobody could
see was thin because the numbers were not on screen.
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
reviews exactly as it does today; matching findings are dropped before posting, `review_finding`
keeps the row with `suppressed_by` set, and the summary comment plus the dashboard card both say:

> *3 findings hidden by learned preferences. [View]*

**The filter runs in the orchestrator, in `ResultSaga` between `ReviewGenerated` and
`PostComments`.** That is the only site where the findings and the preferences are both in hand:
`learned_preference` lives in the orchestrator's schema, and `suppressed_by` can be set in the same
transaction as the insert. The alternative — filtering in the worker — would require approved
preferences to be command-carried per review the way prompts and credentials are (ADR-015), which is
a strictly larger change for no benefit.

**This needs one wire change, which is called out because its failure mode is silent.**
`ActionCommand.PostComments` gains a `suppressedCount`, so the worker can render the line into the
summary it posts. Per the 2026-08-28 lesson, adding a component to a wire record compiles at every
rebuild site — the shorter convenience constructors stay valid — so the change ships with a wither
that enumerates the components once, beside the record. **The contract snapshot gate caught this, and that is worth recording because an earlier draft of
this spec predicted it would not.** The golden file lists each command's own components, so adding
`suppressedCount` failed it until the snapshot was updated. What it genuinely cannot see is a change
inside a NESTED wire type: `Finding` gaining `category` sits inside `ReviewResult` inside
`ReviewGenerated`, and the golden never described its shape, so that change passed the gate in
silence. Both halves of `techdebt/spire-contract/3-2` were therefore demonstrated live in one
milestone -- the gate holding at the top level and blind one level down.

**A suppressed finding recurs every round, and the counts say "suppressions", not "findings".**
Because it is never posted it never enters `posted_findings_json`, so the next round's exclusion
list does not contain it, so the model raises it again and the filter suppresses it again — a new
row per round for as long as the preference holds. This is accepted rather than fixed: adding
suppressed priors to the exclusion list would stop the recurrence but would also mean revocation no
longer restores the finding on the next review, which is the property that makes a wrong preference
cheap to undo. The dashboard labels the number a suppression count for that reason.

Prompt injection was the alternative and is deliberately not built. It might produce *better*
findings rather than merely fewer, but you cannot tell whether the model honoured the instruction,
and a finding it silently skipped leaves no trace anywhere. This project has twice paid for a
mechanism that looked installed and was not — the LLM circuit breaker recording a failed future as a
success, and the ADR-023 `0` that meant *unknown*. A filter that counts what it removed cannot fail
that way: if a learned rule is wrong, the count is visible, the findings are one click away, and
revocation is immediate.

The suppressed rows stay in the corpus. A preference that starts hiding findings the team would have
acted on is detectable precisely because the rows are still there to be counted.
## 7. Build sequence

**Milestone 1 — corpus and analytics.** `review_finding` (**V36**), `operator_identity` (**V37**),
the three projection writes, `category` on `Finding` through prompt/parser/wire, `subject` on
`/api/me`, the analytics queries and endpoints, and the dashboard screens. Analytics is the first
reader on purpose: it is the only way to tell a correct projection from a wrong one, because a bad
number is visible immediately and a bad row is not. This mirrors ADR-023, where building the ledger
and then reading it back is what exposed four separate places that had turned *unknown* into *zero*.

**Milestone 2 — memory.** `learned_preference` (**V38**), the nightly proposal job, the
Settings → Memory screen, the `PostComments.suppressedCount` wire change, and the filter with its
counted suppression. Deferred behind M1 because a proposal engine over an unvalidated corpus
proposes confident nonsense, and because the approval screen is an analytics view with two buttons —
most of it is built by then.

### 7.1 Documents that must move with the code

Non-optional, because §2 of this spec is a complaint about exactly this drift:

- **`DATA-MODEL.md` §143** currently specifies a *different* `review_finding` (it has
  `pr_id`/`comment_id`/`BYTEA` columns and lacks `round`, `category`, `verdict`, `origin`,
  `suppressed_by`). Rewrite it to the shipped shape, and add `operator_identity` and
  `learned_preference`.
- **`DECISIONS.md`** gains **ADR-027**.
- **`ROADMAP.md`** P4 moves off "later".
- **`SECURITY.md`** gains the `operator_identity` disclosure note — it is a map from real people to
  their measured activity, which is a category of data this deployment has not held before.

### 7.2 UI, planned rather than gestured at

The ADR-025 `refused` incident is the standing reason UI vagueness is expensive here: a new backend
state reached the dashboard, fell through a lookup keyed by status, and rendered a refused review as
five green segments under "done". So:

| Screen | Route | Gating |
|---|---|---|
| Analytics — repositories | `/analytics` (new top-level nav item) | viewer |
| Analytics — one repository | `/analytics/:workspace/:slug` | viewer |
| Analytics — my activity | `/analytics/me` | viewer; renders the unlinked state when unmapped |
| Settings → Operators (identity mapping) | `/settings/operators` | admin, hidden from viewers |
| Settings → Memory (M2) | `/settings/memory` | admin, hidden from viewers |

Every screen needs an explicit **empty state** distinct from its **error state** and, for
`/analytics/me`, from the **unlinked state** — three different sentences, because "no data yet" on a
fresh corpus, "we could not load this" and "we do not know who you are" send an operator to three
different places. `hasRole(null, …)` stays false, per the ADR-022 rule that the UI grants nothing by
default.
## 8. Exit criteria

**Milestone 1.** Every finding a `ReviewGenerated` carries appears in `review_finding` with its
round. Thread refs land from `CommentsPosted` on the rows that were posted, and stay null on the
rows that were not — the reachable never-posted cases being a **degraded run** (empty or partial
finding list) and a **per-finding post failure**, asserted directly.

*Three cases named in an earlier draft are not assertable and are recorded here so nobody re-adds
them:* observe mode never starts the pipeline, so it produces no findings at all; a refused review
(ADR-025) is stopped at `DiffFetched` or pre-spend, before `GenerateReview` is dispatched, so
`ReviewGenerated` never fires; and anchor-collision drops happen in the **worker**, before the event
is emitted, so the orchestrator cannot see them without a wire change this spec does not propose.

A verdict lands on the newest not-yet-judged row for its location across all prior rounds — asserted
with a finding that survives two intermediate rounds, since that is the case the naive `round - 1`
rule gets wrong. An unjudged finding reads `NULL` rather than a default. A redelivered
`ReviewGenerated` leaves the row count unchanged. A round-read failure skips the write instead of
filing it under round 1. An operator with no identity mapping sees the unlinked state and no author
data; an operator with one sees only their own unless they are an admin. The per-repo dashboard
renders correctly with an empty corpus.

**Milestone 2.** A proposal is generated only above both thresholds, and its card shows the
evidence count and the thresholds. An approved preference suppresses matching findings, the count
is shown on the summary comment and the dashboard, the suppressed rows remain in `review_finding`
with `suppressed_by` set, and revocation restores them on the next review. A rejected proposal does
not reappear — asserted across two consecutive job runs, since the guarantee depends on `path_glob`
being deterministic.

**Not an exit criterion, and stated so it is not assumed:** that a learned preference makes reviews
*better*. That needs the corpus §3 says does not exist yet, and it is the same downstream claim
ADR-026 left explicitly unproven for the symbol index. It belongs in a field-verification issue like
[#89](https://github.com/artyomsv/code-spire/issues/89), not in this spec's definition of done.

## 8.1 Deliberately out of scope

- **Retention of `review_finding`.** It grows without bound in this milestone. A purge rides with
  the ADR-024 purge when that exists, which now has a second table to cover. Said rather than left
  silent.
- **`FindingVerdict.note`** — the reconcile call's explanation of a `STILL_OPEN` gap — has no
  column and is dropped on purpose. It is encrypted-worthy prose that nothing aggregates.
- **Rebuilding the projection from `event_log`.** Impossible for historical rows (§2.2) and
  unnecessary for new ones. `projection_checkpoint` stays dead schema.
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
