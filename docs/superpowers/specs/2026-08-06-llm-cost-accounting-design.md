# LLM cost accounting — design

**Date:** 2026-08-06
**Status:** approved in brainstorming, not yet implemented
**Wants:** ADR-023, orchestrator migration V30
**Precedes:** fleet cost/abuse caps (`docs/ROADMAP.md` "Explicitly deferred"), which is a separate spec

## Goal

Make a review's LLM cost **trustworthy enough to build a spend guarantee on**: recorded per token
type, priced at the rate in force when the call happened, immune to later price edits, and incapable
of confusing *"this was free"* with *"nobody told us the price"*.

## Why this comes first

The fleet cost/abuse caps item was the intended work. Reading the accounting it would have to sit on
showed it cannot be built honestly yet: a spend cap reads a ledger that silently records `0` for any
call it could not price, so the cap would install cleanly, look correct, and never fire. That is the
same failure shape as the LLM circuit breaker recording a failed future as a success — a control that
is present, inert, and trusted.

So the work splits. This spec fixes the accounting, which is independently valuable (statistics,
auditability, correct pricing under prompt caching) and ships on its own. The caps follow, and read
this ledger.

## What is broken today

Four places turn *unknown* into *zero*. Each verified in the current source.

| # | Hole | Location | Effect |
|---|---|---|---|
| 1 | A blank price becomes a **zero** price across three layers, none of which is individually wrong | see below | "operator did not enter a price" is persisted as "free", permanently |
| 2 | A provider's `model` is free text, never checked against the catalog | `LlmProviderResource.java:153` — `requireField` checks non-blank only | a provider can reference a model that cannot be priced; the Settings dropdown is a courtesy, not a control |
| 3 | Catalog `delete` has no referential check | `LlmModelRegistry.java:123` | deleting an entry silently orphans every provider using it, defeating #2 *after* it passed |
| 4 | `costMillicents` returns `0L` on `SQLException` | `LlmModelRegistry.java:155` | a transient DB blip prices a real, paid call at zero — permanently, behind a `WARN` |

The database was never at fault: V9 already declares both price columns `NOT NULL`. Absence is
unrepresentable in storage and gets manufactured on the way in.

**Hole #1 in full, because the mechanism matters more than the symptom.** Three layers hand the value
along, and each is defensible alone:

| Layer | Code | What it does |
|---|---|---|
| UI | `SettingsLlmProviders.tsx:602,603` | `dollarsToMillicentsPerMillion(Number(inputPrice) \|\| 0)` — a blank field becomes `0` |
| REST | `LlmModelResource.java:81,82,91` | `requireNonNegative` rejects `null` but **accepts `0`** as valid |
| Registry | `LlmModelRegistry.java:78,79,106,107` | `null → 0L` coercion — unreachable from REST, since null never gets past validation |

So the API's null-check is real and the registry's coercion is dead code; the laundering happens in the
**gap between the layers**, where the UI's convenience default meets a validator that has no way to
tell a deliberate zero from an empty box. Fixing any single layer would not close it: the UI must stop
defaulting, *and* the server must reject zero when the model is metered, because the UI is not the
control. This is why the fix is `pricing_mode` rather than a stricter number check — no amount of
validating a number distinguishes "free" from "unknown" when both arrive as `0`.

**#4 is the one to understand.** #1–#3 are configuration faults: wrong state persists until someone
fixes the config, and the damage is bounded by how long that takes. #4 is a *transient* fault causing
*permanent* loss — the connection blip lasts a second, the zero it writes lasts forever, and the only
trace is a log line that has since rotated. Any `catch` returning a plausible value instead of failing
converts a momentary outage into silent corruption.

Two further gaps, not bugs but limits:

- **Only two token buckets.** `LangChain4jLlmProvider.java:150` reads `inputTokenCount()` /
  `outputTokenCount()` and hardcodes cost `0L`. Cached-input reads, cache writes and reasoning tokens
  collapse into those two at the wrong rates.
- **The rate is not recorded.** Cost is stored without the price it came from, so no historical figure
  is reproducible and a change in the numbers is unattributable — usage moved, or a price was edited?

## What is *not* broken

Worth stating, because it was the initial concern and it changed the design once falsified: **cost is
already frozen at write time.** `ResultSaga.priced()` runs once, when `ReviewGenerated` is handled,
and `recordLlmCall` inserts that number; both readers (`listSummaries`, `llmCalls()`) sum stored
values, and `llmModels.costMillicents(...)` is reachable from exactly two places, both on the result
path. Editing a model's price tomorrow does not change yesterday's rows.

The problem was never re-pricing. It is that the frozen number is sometimes a fabrication, and never
carries the evidence for itself.

## Token types are a partition

`spire-llm` maps vendor-specific usage onto one neutral vocabulary. Verified present in LangChain4j
1.18.1 by inspecting the jars — the base `TokenUsage` carries only input/output/total, and the detail
lives on vendor subclasses:

| Neutral type | OpenAI | Anthropic | Gemini |
|---|---|---|---|
| `INPUT` | `inputTokenCount()` | `inputTokenCount()` | `inputTokenCount()` |
| `OUTPUT` | `outputTokenCount()` | `outputTokenCount()` | `outputTokenCount()` |
| `CACHED_INPUT` | `inputTokensDetails().cachedTokens()` | `cacheReadInputTokens()` | `cachedContentTokenCount()` |
| `CACHE_WRITE` | — | `cacheCreationInputTokens()` | — |
| `REASONING` | `outputTokensDetails().reasoningTokens()` | — (billed as output) | `thoughtsTokenCount()` |

**The vendors disagree on whether detail counts are included in or additional to the headline
numbers.** OpenAI's `prompt_tokens` includes `cached_tokens` (a subset); Anthropic's `input_tokens`
excludes cache reads (additive line items). Summing the buckets naively would double-count on one and
undercount on the other, and both produce a plausible-looking number.

The design does not rely on getting those semantics right from memory:

> **Every token lands in exactly one bucket.** Each vendor mapping performs whatever subtraction it
> needs to produce disjoint counts, and the invariant `Σ(per-type tokens) == totalTokenCount()` is
> asserted per vendor.

That invariant cross-checks against arithmetic the vendor computed independently and sent us, so it
holds regardless of what we believed about caching. It is also how an *unmapped* new bucket announces
itself: reconciliation fails rather than a token quietly vanishing.

**When reconciliation fails at runtime**, the call is recorded as a single `TOTAL` line carrying the
vendor's own `totalTokenCount`, marked unpriceable, plus an attention row. Nothing is lost, nothing is
invented, and it is loud.

## Pricing: mode, not just numbers

A form that merely demands a number does not fix #1 — an operator who has not looked up the price
types `0` to get past validation, and the ambiguity returns as their assertion rather than our
coercion. So zero becomes a **category**:

```
llm_model.pricing_mode = 'METERED' | 'UNMETERED'
  METERED    → rates required for INPUT and OUTPUT, each > 0; rates for
               CACHED_INPUT / CACHE_WRITE / REASONING optional
  UNMETERED  → self-hosted / own inference; no rates, cost is asserted zero
```

**Which rates are mandatory, and why only two.** `INPUT` and `OUTPUT` are reported by every vendor on
every call, so a `METERED` model with either unpriced is certainly unpriceable and is rejected at save
time. The other three occur only under caching or reasoning, are not billed by every vendor
(`CACHE_WRITE` has no OpenAI or Gemini counterpart at all), and an operator cannot be asked to price a
dimension their model does not have.

Pricing is therefore **per line, not per call**: a token type that arrives with `tokens > 0` and no
rate row makes *that line* `UNKNOWN` while the rest of the call prices normally. The call's total is
then knowingly partial, which the attention panel reports — a partial total that says so is honest; a
complete-looking total built from a fabricated zero is not.

Nobody reaches a saved model without stating which world it is in. `0` under `METERED` is rejected;
`0` under `UNMETERED` is the entire point.

The payoff is in reporting. Instead of `$0.00 total` — which reads as either "nothing happened" or
"everything is free" — the dashboard can say *"3,200 calls · 2,900 unmetered (self-hosted) · 300
metered · $4.18"*, which is a statement an operator can act on.

Rates move out of `llm_model` into a child table, one row per token type, because five fixed columns
would need a migration per vendor billing change and cannot express "this model does not bill for
cache writes".

## The ledger: one table, grain = charge line

One row per token type per call. A call is the set of rows sharing `call_ref`.

```sql
CREATE TABLE llm_charge (
    id            UUID         PRIMARY KEY,
    review_id     TEXT         NOT NULL,
    call_ref      TEXT         NOT NULL,   -- groups one call's lines; the dedupe key
    kind          VARCHAR(16)  NOT NULL,   -- review | reconcile | followup
    model         VARCHAR(255) NOT NULL,
    pricing_mode  VARCHAR(16)  NOT NULL,   -- METERED | UNMETERED | UNKNOWN
    token_type    VARCHAR(32)  NOT NULL,   -- INPUT | CACHED_INPUT | CACHE_WRITE | OUTPUT | REASONING | TOTAL
    tokens        INT          NOT NULL CHECK (tokens >= 0),
    rate_millicents_per_million BIGINT,    -- the rate IN FORCE at priced_at
    cost_millicents             BIGINT,    -- NULL only when pricing_mode = 'UNKNOWN'
    priced_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (call_ref, token_type),
    CHECK ((pricing_mode = 'UNKNOWN') = (cost_millicents IS NULL)),
    CHECK (pricing_mode <> 'UNKNOWN'   OR rate_millicents_per_million IS NULL),
    CHECK (pricing_mode <> 'METERED'   OR rate_millicents_per_million IS NOT NULL),
    CHECK (pricing_mode <> 'UNMETERED'
           OR (rate_millicents_per_million = 0 AND cost_millicents = 0)),
    -- An unreconciled call has no per-type split, so a METERED rate cannot be applied to it.
    -- UNMETERED is still valid: cost is zero whatever the split turns out to be.
    CHECK (token_type <> 'TOTAL' OR pricing_mode <> 'METERED')
);
CREATE INDEX llm_charge_review_idx  ON llm_charge (review_id, priced_at);
CREATE INDEX llm_charge_priced_idx  ON llm_charge (priced_at);  -- fleet/daily aggregates
```

**Which lines get written.** One line per token type with `tokens > 0`, so a call without caching does
not carry three zero rows. A call whose usage is entirely zero or absent still writes exactly one line
— `TOTAL`, `tokens = 0` — because a call that happened must be countable even when the vendor reported
no usage, and `SUM` over zero rows is indistinguishable from a call that never occurred.

Why each piece earns its place:

- **One place, as asked.** Cost of a call, a review, a day, or the fleet is the same `SUM` with a
  different `WHERE`. Per-type statistics are a `GROUP BY token_type`.
- **The rate travels with the charge**, so every figure is reproducible as `tokens × rate ÷ 1e6`. A
  later price edit is invisible to history by construction rather than by convention.
- **`priced_at`** records when the rate was captured — what makes a frozen number auditable rather
  than merely old.
- **`UNIQUE (call_ref, token_type)`** closes a real double-count window. `recordLlmCall` is an
  `INSERT` with a fresh `UUID` and no uniqueness, guarded only by `ifCurrentRun`
  (`ResultSaga.java:398`), which checks `isReviewing() && commit == currentCommit` — a *staleness*
  guard, not an at-most-once guard. Between `ReviewGenerated` and `ReviewCompleted` the review is
  still `REVIEWING` at the same commit, so a redelivered result passes and inserts a second row for a
  call that happened once. Today that inflates a dashboard figure; under a spend cap it corrupts a
  control.
- **The `CHECK`s make the illegal states unrepresentable** at the storage layer, not just in the
  service that writes them.

`call_ref` must be stable across redelivery. The worker already holds an LLM idempotency claim per
paid call; **its key is the intended source and has not yet been confirmed to be stable and exposed
on the emitted event.** Verifying that is the first implementation task, and if it is unsuitable the
fallback is a deterministic `(reviewId, commit, kind, attempt)` composite.

`review_status`'s `model` / `tokens_in` / `tokens_out` / `cost_millicents` columns stop being written.
They are dropped rather than kept as a fallback, since the ledger they were a rollup of is being
dropped with them (see Migration).

## Guards, at the three places a decision is still possible

Pricing is **post-hoc** — `ResultSaga` prices when the result event returns, by which point the money
is spent. Failing there would waste the spend *and* lose the review. So the guards sit where a
decision can still change an outcome:

| Layer | Where | What it does |
|---|---|---|
| **Config time** | model save, provider save, catalog delete | makes an unpriced-but-selectable model unrepresentable |
| **Pre-spend** | `ResultSaga`, before emitting `GenerateReview` | skips with an actionable note when the model is not priceable, mirroring the existing `llmCred.isEmpty()` skip at `ResultSaga.java:143` |
| **Post-hoc** | `costMillicents` | records `pricing_mode='UNKNOWN'` and raises attention. Never fabricates `0`, never fails a review whose money is already gone |

Concretely:

1. **Reject a zero rate under `METERED`, at all three layers of hole #1.** The UI stops defaulting a
   blank field to `0` (`SettingsLlmProviders.tsx:602,603`) and surfaces it as a validation error; the
   REST layer rejects `0` for a `METERED` model rather than merely rejecting negatives
   (`LlmModelResource.java:91`); and the now-dead `null → 0L` coercions at
   `LlmModelRegistry.java:78,79,106,107` are deleted rather than left to mislead the next reader.
2. **Reject a provider whose `model` is not in the catalog** — `LlmProviderResource.java:153`
   currently checks non-blank only.
3. **Refuse to delete a catalogued model a provider references.** Without this, guard 2 is defeated
   after the fact.
4. **Remove `return 0L` from the `SQLException` path** (`LlmModelRegistry.java:155`). A lookup failure
   yields `UNKNOWN`, never a price.
5. **Pre-spend skip** when the resolved model is not `UNMETERED` and lacks an `INPUT` or `OUTPUT` rate
   — the two dimensions every call certainly incurs — with the reason on the dashboard. Optional
   dimensions are deliberately not checked here: refusing a review because a cache-write rate is
   missing would block work over a line item that may never occur.

The METERED-requires-rates rule spans two tables, so it cannot be a single `CHECK`; it is enforced in
the registry and covered by a test that fails when the enforcement is removed.

## Wire contract

`ModelUsage` is reshaped **in place** — same name (still accurate, and it avoids an import collision
with LangChain4j's own `TokenUsage` inside `spire-llm`), new shape, **money field removed** so the
worker cannot express a cost even by accident:

```java
// spire-contract — the neutral vocabulary
public enum TokenType { INPUT, CACHED_INPUT, CACHE_WRITE, OUTPUT, REASONING, TOTAL }

public record TokenCount(TokenType type, int tokens) {}

/** What an LLM adapter reports. No money: pricing is the orchestrator's job. */
public record ModelUsage(String model, List<TokenCount> counts, int reportedTotal, boolean reconciled) {}
```

Call sites to update: `Completion`, `ReviewResult.usage`, `IntegrationEvent` lines 184
(`reconcileUsage`) and 245 (`usage`), `LangChain4jLlmProvider.callModel`, `ResultSaga.priced` /
`priceUsage`, `ReviewProjection.recordOutcome` / `recordLlmCall`.

**This is a safe clean break, verified rather than assumed.** `DomainEvent` carries no usage field at
all, so the **event store is untouched** and no upcaster is needed. Only in-flight Kafka messages are
affected, and those live under short retention (ADR-014).

**The ADR-013 snapshot gate does not see this change, and that is a gap in the gate rather than a
property of the change.** An earlier draft of this spec claimed the snapshot would be regenerated and the
gate thereby satisfied. It was not: `ContractSchemaSnapshotTest` renders each component as
`name: TypeName` and never recurses, so the golden file records
`reconcileUsage: dev.codespire.contract.review.ModelUsage` and nothing about that type's own components.
Reshaping it leaves the gate green. So the safety of this break rests entirely on the reasoning above —
that no persisted event carries usage — and not on any automated check. Filed as
`techdebt/spire-contract/3-2-contract-snapshot-does-not-recurse-into-nested-wire-types.md`, because the
same blind spot covers `Finding`, `ReviewResult`, `ContextItem` and every other nested wire type.

Licensing stays consistent with ADR-021: the neutral vocabulary lives in `spire-contract` and the
vendor mapping in `spire-llm`, both Apache-2.0; pricing and the ledger are orchestrator-owned (FSL).
No Apache module gains a dependency on a service.

## Migration (V30) and the legacy data

The development environment's history is not preserved, by decision: every `0` in `review_llm_call`
is ambiguous — the coercion means "was unpriced at the time", not "was free" — and the distinguishing
information was never written, so no migration can recover it. The rows are smoke-test accounting
against test PRs.

- `DROP TABLE review_llm_call`.
- Drop `review_status.model`, `tokens_in`, `tokens_out`, `cost_millicents`.
- Create `llm_model_rate`, `llm_charge`; add `llm_model.pricing_mode`.
- **Catalog rates are preserved only where unambiguous.** A rate `> 0` can only have been
  operator-entered (coercion produces `0`), so those migrate to `llm_model_rate` as `INPUT` / `OUTPUT`
  with `pricing_mode='METERED'`. A model with any zero rate cannot be migrated honestly and is left
  without rates — which the new guards then treat as unpriceable.

**Expected one-time operator action:** any model that was saved with a zero rate must be given
pricing (or marked `UNMETERED`) in Settings → Models before it will run a review. This is the guard
working as specified, stated here so it is not discovered as a bug.

## UI

- **Settings → Models:** a `METERED` / `UNMETERED` choice; under `METERED`, a rate per token type the
  model bills for. Validation mirrors the server's, and the server remains the control.
- **Review detail cost card:** per-type lines (tokens, rate, cost) grouped per call, so a cached-heavy
  reconcile is legible.
- **Reviews list:** unchanged in shape; the total now comes from `llm_charge`.
- **Attention panel:** a row for calls recorded `UNKNOWN`, and one for unreconciled calls. Both are
  conditions true right now and clear when the cause is fixed, matching the panel's existing rule.
- lucide-react icons only; no emoji.

## Testing

- **The partition invariant, per vendor.** Construct each vendor's `TokenUsage` subclass directly and
  assert the mapping yields disjoint buckets summing to `totalTokenCount()`. These are pure-function
  assertions whose values never become user-visible state. Where credentials exist, additionally
  reconcile against a real captured response — real data by definition, and the stronger check.
- **Each guard, mutation-verified** (the standard set by the debt waves): break the production line,
  confirm exactly one test fails. Blank rate rejected; uncatalogued provider model rejected; delete of
  a referenced model rejected; `SQLException` yields `UNKNOWN` and not `0`.
- **Redelivery inserts once** — the double-count window above, driven through `ResultSaga` twice.
- **The `CHECK` constraints reject illegal combinations**, asserted at the SQL layer so the schema is
  proven to be the backstop rather than assumed.
- **Pre-spend skip:** an unpriceable model emits no `GenerateReview` and sets a dashboard note.
- **Aggregate correctness:** a review's total equals the sum of its charge lines, across a run with a
  reconcile call and a follow-up.
- **A partially-priced call** — a priced `INPUT`/`OUTPUT` plus an unpriced `CACHE_WRITE` — prices the
  lines it can, marks the one it cannot `UNKNOWN`, and reports the total as partial rather than
  complete.

## Non-goals

- **The fleet caps.** Separate spec, built on this ledger.
- **A temporal price catalog.** Rejected: it makes every statistics read an interval join and leaves
  "what did we actually charge" contingent on catalog integrity forever, while not even solving the
  case it exists for — an operator entering a price today still has no recorded history for yesterday.
  Snapshotting the rate is simpler and stronger. A retroactive-repair action for `UNKNOWN` rows may be
  added later; it is not needed now.
- **Enabling prompt caching.** The buckets are recorded so the ledger never needs a backfill when
  caching is turned on; turning it on is separate work.
- **Per-author cost attribution.** The author is already data on the review; attributing spend is P4
  analytics.
- **Changing where pricing happens.** It stays in the orchestrator, which owns the catalog. The worker
  holds no rates and, after this change, cannot represent money at all.

## Consequence worth carrying into the caps spec

A legal `UNMETERED` zero means **a money-denominated spend cap is inert on a self-hosted deployment —
correctly, by design.** The operator asserted zero; there is nothing to cap. But their inference
hardware can still be hammered, and every abuse scenario except the bill still applies. The fleet cap
therefore needs an axis that holds regardless of pricing mode: calls or tokens per window, per repo.
That axis is also immune to the pricing-data-entry problem entirely.
