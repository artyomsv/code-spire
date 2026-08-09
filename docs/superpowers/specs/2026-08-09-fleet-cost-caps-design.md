# Fleet cost and abuse caps

**Status:** draft, under review. Not approved, not implemented.
**Builds on:** ADR-023 (the `llm_charge` ledger) and ADR-024 (archival).

## Problem

The ROADMAP records this as a deferred, **known operator-facing gap**: no per-repo rate limit, no
daily spend cap, no hard giant-PR skip. A search of the codebase confirms it — there is **no rate
limiting of any kind anywhere**. The only bounds that exist are:

- `maxTokens` per LLM call, set per provider — bounds one call's output, nothing aggregate
- `SPIRE_REVIEW_MAX_ATTEMPTS` — the ADR-016 auto-retry budget, not a spend budget
- the conversation turn cap — per thread, and follow-ups only
- the diff token clip — truncates a large diff rather than refusing it

None of these bound how much a repository can spend, how often it can be reviewed, or how large a
pull request can be before reviewing it stops being worthwhile.

The four things this must protect against, all of which the owner named as real:

1. **Own runaway spend** — normal use that accidentally costs a lot.
2. **Abuse by anyone who can comment** — `/review` is now allowlisted, but an allowlisted author can
   still force unlimited re-runs, each a genuinely paid call.
3. **A future multi-tenant deployment** — one bot serving a workspace whose members the operator does
   not personally vouch for.
4. **Pathological input** — a generated-code dump or vendored directory, where the review is both
   worthless and expensive.

## Why this comes after ADR-023, not before

The caps were deferred once already, and reading what they would build on is what found four separate
places where *unknown* became *zero*. A cap reading those numbers would have installed cleanly and
never fired for exactly the calls it exists to stop. ADR-023 fixed that; this design is the feature it
was clearing the way for.

## Decisions

### 1. Three gates, each where its inputs already are

| Gate | Location | Checks |
|---|---|---|
| **Admission** | `IntegrationSaga`, at review request | rate limit |
| **Post-diff** | `ResultSaga`, on `DiffFetched` | diff size |
| **Pre-spend** | `ResultSaga`, before `GenerateReview` | spend / usage cap |

A two-gate variant was considered — folding the diff-size check into the pre-spend gate — and
rejected on evidence. `DiffFetched` carries `changedFiles`, `sizeBytes` and `truncated`;
`ContextAssembled` carries none of them, and `review_status` persists none of them. Checking size at
the pre-spend point would therefore require either new columns plus a write and a read, or new fields
on `ContextAssembled` — **a Kafka wire contract**, needing a snapshot update and an `eventVersion`
consideration. It would also perform `GatherContext` first: per-issue Jira/Confluence/GitHub API
calls, a bounded 20-second fan-out and an encrypted blob write, all discarded moments later. For a
5,000-file dump that is precisely the work least worth doing.

The apparent advantage of fewer gates — that this codebase was once bitten by two emit sites
describing one refusal differently — does not survive inspection. That was fixed by giving the
refusal **one vocabulary** (`DefaultLlm`'s `Refusal` + `detail()` + `note()`), not by co-locating the
sites. Three call sites sharing one type have the same guarantee as two.

### 2. One refusal vocabulary, in a new type

All three gates describe a refusal through a single type carrying an enum reason plus `detail()` (one
timeline line) and `note()` (the review's note field), modelled on `DefaultLlm`.

Deliberately **not** an extension of `DefaultLlm.Refusal`. That type answers "can this LLM be used",
and its existing value is `MODEL_NOT_PRICEABLE`. A cap refusal is a policy decision about budget, and
folding it in would drag budget logic into credential resolution — two concerns that change for
different reasons.

### 3. State: one new table, and the ledger

**Diff size** needs no state. It compares event fields against configuration.

**The spend cap** reads `llm_charge` directly: `SUM(cost_millicents)` and `COUNT(DISTINCT call_ref)`
over a window. No new storage — this is the query the ledger was built to make trustworthy.

**The rate limit** needs a purpose-built counter, written at admission. The ledger cannot serve it:
charges are recorded *after* a call completes, so a burst of requests would all pass the check before
any of them appeared in the ledger. A counter incremented at the moment of admission is the only
pre-hoc source, and pre-hoc is the whole point of a rate limit.

### 4. The counter is keyed by provider, not just by repo

`techdebt/spire-orchestrator/3-3-the-charge-ledger-is-keyed-on-an-id-two-scms-can-share.md` records
that `reviewId` is `review::{workspace}/{slug}#{pr}` with **no provider component**, so one workspace
name registered on two SCMs collides. That entry explicitly predicts this feature inheriting the
problem: *"a per-repo daily spend cap reading these same queries would inherit the collision exactly
— two unrelated repositories on two unrelated platforms would share one budget."*

So the admission counter keys on **`(provider_type, workspace, slug)`**. `review_status` already
stores `provider_type` and the integration events carry it, so the value is available; it simply has
to be in the key.

The **spend cap is deployment-wide** in v1, so it does not inherit the collision — there is one
budget and every charge counts toward it. A future per-repo spend cap would need the ledger itself to
carry `provider_type`, which is the debt entry's own recommendation and stays out of scope here.

### 5. Every cap has a non-money axis

ADR-023 established that a money-denominated cap is **inert by design on an `UNMETERED` deployment**,
where every charge is an asserted zero. A money-only cap would therefore be a control that installs
cleanly, looks correct, and never fires — the failure shape this project has now met three times.

So each cap is *"money **or** calls, whichever trips first"*. On an unmetered deployment the money
limit never fires and the call-count limit still does. On a metered one, both are live and either can
trip.

### 6. Refusals are loud

A tripped cap writes a timeline entry, sets the review's note, and raises an **attention row**.

Silence is the failure this project has already paid for twice: the conversation turn cap recorded a
dashboard note and posted nothing, so the bot simply stopped replying — indistinguishable from a lost
webhook; and `/review` on a deleted review refused correctly to a surface with no page to appear on.

The attention panel's contract — *a row cannot outlive the state that produced it* — fits caps
naturally: the row clears when the window rolls or the operator raises the limit. Unlike the two cost
rows added by ADR-023, no acknowledgement watermark is needed, because a cap describes **current
state** rather than a past event.

### 7. Scope: per-repo in v1, per-actor deferred

Per-repo rate limiting bounds total volume regardless of who triggers it, which covers the abuse case
in a deployment where `/review` is already allowlisted. Per-actor limits add isolation that matters
when many legitimate actors share a repo and one must be throttled without affecting the rest — real
for multi-tenant, speculative at three repositories.

The counter table's key is chosen so adding an actor dimension later is a migration, not a redesign.

## Configuration

Limits live in the existing `app_setting` store, surfaced under **Settings → General** beside the
review and conversation settings, which is where an operator already goes to change how much work the
bot does.

Every limit is **optional**: unset means unlimited, and that is the default. A deployment that sets
nothing behaves exactly as it does today. This matters because the alternative — shipping defaults —
would silently change the behaviour of a running deployment on upgrade, which is the mistake V30 made
by leaving legacy models rateless and is still the most operator-visible consequence of ADR-023.

## Open question for the reviewer

**Rolling window or fixed bucket?** A rolling window ("20 reviews in any 60 minutes") is fairer and
harder to game, but an operator cannot easily predict when capacity returns. A fixed bucket
("20 reviews per calendar hour", "$5 per calendar day") is trivially predictable and trivially gamed
at the boundary — twice the limit across two adjacent minutes.

The attention row's wording depends on the answer: a rolling window can only say "capacity returns as
older requests age out", while a fixed bucket can say "resets at 14:00".

## Testing

1. **A cap that is unset never refuses** — the default must be a no-op, or every existing deployment
   changes behaviour on upgrade.
2. **Each gate refuses for its own reason and says so** — three refusals, three distinct messages,
   asserted on the text rather than on a boolean.
3. **The rate counter is keyed by provider** — two repos with the same `workspace/slug` on different
   SCM types must not share a budget. Without this the `techdebt/3-3` collision ships into the caps.
4. **The call-count cap fires on an `UNMETERED` deployment where the money cap cannot.** This is the
   test for the whole dual-axis decision, and it is the one that fails if someone later "simplifies"
   the cap to a single money figure.
5. **A refused review raises an attention row, and the row clears when the window rolls.**
6. **The rate limit counts admissions, not charges** — a burst of requests inside one window must be
   refused even though none of them has been charged yet. This is the test that fails if someone
   re-implements the counter over the ledger.

## Deliberately not built

- **Per-actor limits.** Section 7.
- **Per-repo spend caps.** They need `provider_type` on `llm_charge`, which is `techdebt/3-3`'s own
  recommendation and belongs with that entry rather than here.
- **Queuing or deferral.** A refused review is refused, not held for later. Holding introduces a
  backlog that must itself be bounded, and nothing yet needs it.
- **Cost estimation before the call.** Refusing based on a *predicted* price would need a token
  estimate the system does not have and cannot validate, which is the fabricated-number problem
  ADR-023 exists to prevent. The caps refuse on measured history and measured input size only.
