# LLM Cost Accounting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Record every LLM call's token usage per type, priced at the rate in force when the call happened, so cost history is reproducible, immune to later price edits, and never confuses "this was free" with "nobody told us the price".

**Architecture:** The LLM adapter (`spire-llm`) maps each vendor's token-usage subclass onto one neutral partition and reports **no money**. The orchestrator prices that usage against the catalog and appends one **charge line per token type per call** to a single ledger table, snapshotting the rate onto each line. A `METERED`/`UNMETERED` pricing mode makes an asserted zero (self-hosted inference) distinguishable from an absent price, and guards at config time, pre-spend and post-hoc make an unpriceable model impossible to select rather than silently free.

**Tech Stack:** Java 25, Quarkus 3.38.1, Gradle Kotlin DSL, Postgres + Flyway, LangChain4j 1.18.1, React 19 + Vite + vitest.

**Spec:** `docs/superpowers/specs/2026-08-06-llm-cost-accounting-design.md` — read it before Task 1. It carries the reasoning; this plan carries the steps.

**Branch:** `feat/llm-cost-accounting` already exists with the spec committed (`13fb7fc`). Work on it.

## Global Constraints

- **Java:** 4-space indent, explicit types over `var`, **max 3 method parameters**, methods ≤30 lines, classes ≤300 lines, guard clauses over nesting, max 2 levels of nesting.
- **TypeScript:** 2-space indent, `interface` over `type` for object shapes, React components ≤250 lines, ≤8 props.
- **Icons:** lucide-react only. **Never emoji.**
- **Money in millicents** (1/100,000 dollar). Fields carrying it are suffixed `Millicents`.
- **`spire-contract` and `spire-diff` stay framework-free** — build-enforced by `PureModulesAreFrameworkFreeTest`. Only the JDK plus `jackson-annotations` (annotations only, on the sealed `IntegrationEvent`/`ActionCommand` hierarchies). Do not add an import that breaks this.
- **ADR-021:** no Apache-2.0 module may depend on a service module. `spire-contract`/`spire-llm` are Apache-2.0; the three services are FSL. Pricing and the ledger are orchestrator-owned.
- **No synthetic data.** Test fixtures use `example.invalid` hosts, `TEST-`/`CANARY-` prefixes, and obviously-synthetic values. Never a plausible real price or a recalled market figure.
- **No secrets in logs.** Never log a provider response body on an auth failure.
- **Commit messages:** imperative, ≤72 chars on the first line, body for non-trivial changes. **Never mention AI/agentic authoring** — no `Co-Authored-By`, no model or vendor names, no "generated with".
- **Migration:** orchestrator Flyway version **V30**. One migration for the whole schema change.
- **ADR:** this work is **ADR-023**.
- **Verification loop:** `./gradlew testFast` (13 Docker-free modules, ~25s) then `./gradlew testServices` (the 3 deployables, needs Docker). UI: `cd spire-ui && npx vitest run && npx tsc --noEmit`.
- **Every guard added here must be mutation-verified**: break the production line, confirm exactly one test fails, restore. This is the standard set by the 2026-08-02/03 debt waves.

---

## File Structure

**`spire-contract`** (Apache-2.0, framework-free) — the neutral vocabulary only. No money.

| File | Responsibility |
|---|---|
| `review/TokenType.java` (create) | The neutral token-type enum |
| `review/TokenCount.java` (create) | One type's token count |
| `review/ModelUsage.java` (modify) | Reshaped: model + counts + reportedTotal + reconciled. **Cost field removed.** |
| `src/test/resources/contract-schema.txt` (modify) | ADR-013 snapshot, regenerated |

**`spire-llm`** (Apache-2.0) — vendor → neutral mapping.

| File | Responsibility |
|---|---|
| `TokenUsageMapper.java` (create) | Maps each vendor's `TokenUsage` subclass to a disjoint partition |
| `LangChain4jLlmProvider.java` (modify:138-153) | Calls the mapper instead of building a 2-field usage |

**`spire-orchestrator`** (FSL) — pricing, the ledger, the guards.

| File | Responsibility |
|---|---|
| `db/migration/V30__llm_charge_ledger.sql` (create) | Whole schema change |
| `llm/PricingMode.java` (create) | `METERED` / `UNMETERED` / `UNKNOWN` |
| `llm/ChargeLine.java` (create) | One priced token-type line |
| `llm/ChargeCall.java` (create) | One call's lines plus its identity |
| `llm/CallRefs.java` (create) | Deterministic `call_ref` derivation |
| `llm/LlmModelRegistry.java` (modify) | Rates CRUD, pricing mode, `priceCall`, delete guard. **Split if it passes 300 lines** — extract rates into `LlmModelRateRepository`. |
| `llm/LlmModelInput.java` / `LlmModelView.java` (modify) | Pricing mode + per-type rates replace the two price fields |
| `llm/LlmModelResource.java` (modify:70-95) | Reject zero rates under `METERED` |
| `llm/LlmProviderResource.java` (modify:153) | Reject an uncatalogued model |
| `llm/WorkerLlmCredentials.java` (modify) | `defaultModelName()` for the pre-spend guard |
| `readmodel/ReviewProjection.java` (modify:346-395) | Write charge lines; stop writing `review_status` cost; aggregate reads |
| `pipeline/ResultSaga.java` (modify:136-250, 422-444) | Pre-spend guard; record charge lines |
| `attention/AttentionQueries.java` (modify) | `LLM_COST_UNPRICED`, `LLM_USAGE_UNRECONCILED` rows |

**`spire-ui`** — three surfaces.

| File | Responsibility |
|---|---|
| `api.ts` (modify:693-719, 106-111) | Types follow the server |
| `components/SettingsLlmProviders.tsx` (modify:560-680) | Pricing-mode choice, per-type rates, **no blank→0** |
| `components/ReviewCostCard.tsx` (create) | Per-type cost breakdown, extracted because `render.tsx` is already 876 lines |
| `render.tsx` (modify:621-700) | Delete `usageCard`, `llmCallRow`, `LLM_CALL_KIND_LABEL`, `EMPTY_USAGE` |
| `components/ReviewDetail.tsx` (modify:6, 250) | Render the new component instead of importing `usageCard` |

---

## Task 1: The V30 schema

**Files:**
- Create: `spire-orchestrator/src/main/resources/db/migration/V30__llm_charge_ledger.sql`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/llm/LlmChargeSchemaIT.java`

**Interfaces:**
- Produces: tables `llm_model_rate`, `llm_charge`; column `llm_model.pricing_mode`. Drops `review_llm_call`, `llm_model.input_price_millicents_per_million`, `llm_model.output_price_millicents_per_million`, and `review_status.{model,tokens_in,tokens_out,cost_millicents}`.

- [ ] **Step 1: Write the failing test**

`spire-orchestrator/src/test/java/dev/codespire/orchestrator/llm/LlmChargeSchemaIT.java`:

```java
package dev.codespire.orchestrator.llm;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * The ledger's CHECK constraints are the backstop, so they are asserted at the SQL layer rather than
 * assumed from the service that writes them. Four of these are NEGATIVE assertions — "this must be
 * rejected" — and a negative assertion passes trivially if the constraint is simply absent, so each
 * one below is paired with the positive case that proves the insert path works at all.
 */
@QuarkusTest
class LlmChargeSchemaIT {

    @Inject
    DataSource dataSource;

    private void exec(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    private String insert(String mode, String tokenType, String rate, String cost) {
        return "INSERT INTO llm_charge (id, review_id, call_ref, kind, model, pricing_mode, "
                + "token_type, tokens, rate_millicents_per_million, cost_millicents) VALUES "
                + "(gen_random_uuid(), 'review::TEST-WS/TEST-REPO#1', 'CANARY-" + tokenType + mode
                + "', 'review', 'TEST-MODEL', '" + mode + "', '" + tokenType + "', 10, " + rate + ", " + cost + ")";
    }

    @Test
    void aMeteredLineWithARateAndACostIsAccepted() {
        assertDoesNotThrow(() -> exec(insert("METERED", "INPUT", "250000", "2")));
    }

    @Test
    void aMeteredLineWithoutARateIsRejected() {
        assertThrows(SQLException.class, () -> exec(insert("METERED", "OUTPUT", "NULL", "2")));
    }

    @Test
    void anUnknownLineMustCarryNoCostAndNoRate() {
        assertDoesNotThrow(() -> exec(insert("UNKNOWN", "INPUT", "NULL", "NULL")));
        assertThrows(SQLException.class, () -> exec(insert("UNKNOWN", "OUTPUT", "NULL", "0")));
    }

    /** An asserted zero must be exactly zero on both columns — never a stray rate. */
    @Test
    void anUnmeteredLineMustBeZeroRateAndZeroCost() {
        assertDoesNotThrow(() -> exec(insert("UNMETERED", "INPUT", "0", "0")));
        assertThrows(SQLException.class, () -> exec(insert("UNMETERED", "OUTPUT", "250000", "0")));
    }

    /** An unreconciled call has no split, so no metered rate can apply to it. */
    @Test
    void aTotalLineCannotBeMetered() {
        assertThrows(SQLException.class, () -> exec(insert("METERED", "TOTAL", "250000", "2")));
        assertDoesNotThrow(() -> exec(insert("UNMETERED", "TOTAL", "0", "0")));
    }

    /** The redelivery guard: one call's dimension can be charged exactly once. */
    @Test
    void theSameCallAndTokenTypeCannotBeChargedTwice() throws SQLException {
        String sql = insert("METERED", "CACHE_WRITE", "300000", "3");
        exec(sql);
        assertThrows(SQLException.class, () -> exec(sql));
    }

    @Test
    void theDroppedTablesAndColumnsAreGone() {
        assertThrows(SQLException.class, () -> exec("SELECT 1 FROM review_llm_call"));
        assertThrows(SQLException.class, () -> exec("SELECT cost_millicents FROM review_status"));
        assertThrows(SQLException.class,
                () -> exec("SELECT input_price_millicents_per_million FROM llm_model"));
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew :spire-orchestrator:test --tests 'dev.codespire.orchestrator.llm.LlmChargeSchemaIT'`
Expected: FAIL — `llm_charge` does not exist.

- [ ] **Step 3: Write the migration**

`spire-orchestrator/src/main/resources/db/migration/V30__llm_charge_ledger.sql`:

```sql
-- LLM cost accounting (ADR-023): one ledger, one row per token type per call, carrying the rate that
-- priced it.
--
-- Two problems this closes. First, cost was stored WITHOUT the rate it came from, so no historical
-- figure was reproducible and a change in the numbers could not be attributed to usage or to a price
-- edit. Second, and worse: an unpriceable call was recorded as costing ZERO, indistinguishable from a
-- genuinely free one, so a spend cap reading these totals would have installed cleanly and never
-- fired. A pricing MODE fixes that -- an asserted zero for self-hosted inference is now a category,
-- not a value someone typed to get past validation.

-- 1. The catalog states which world a model is in.
ALTER TABLE llm_model ADD COLUMN pricing_mode VARCHAR(16) NOT NULL DEFAULT 'METERED';
ALTER TABLE llm_model ALTER COLUMN pricing_mode DROP DEFAULT;
ALTER TABLE llm_model ADD CONSTRAINT llm_model_pricing_mode_chk
    CHECK (pricing_mode IN ('METERED', 'UNMETERED'));

-- 2. Rates move to a child table: five fixed columns would need a migration per vendor billing
--    change, and could not express "this model does not bill for cache writes" at all.
CREATE TABLE llm_model_rate (
    model_id                    UUID        NOT NULL REFERENCES llm_model(id) ON DELETE CASCADE,
    token_type                  VARCHAR(32) NOT NULL,
    rate_millicents_per_million BIGINT      NOT NULL CHECK (rate_millicents_per_million > 0),
    PRIMARY KEY (model_id, token_type),
    -- TOTAL is deliberately absent: an unreconciled call has no split to price.
    CHECK (token_type IN ('INPUT', 'CACHED_INPUT', 'CACHE_WRITE', 'OUTPUT', 'REASONING'))
);

-- 3. Preserve only UNAMBIGUOUS rates. A rate > 0 can only have been operator-entered, because the
--    old path coerced a blank to 0. A model with any zero rate cannot be migrated honestly, so it is
--    left without rates and the new guards treat it as unpriceable until an operator fixes it.
INSERT INTO llm_model_rate (model_id, token_type, rate_millicents_per_million)
SELECT id, 'INPUT', input_price_millicents_per_million FROM llm_model
 WHERE input_price_millicents_per_million > 0 AND output_price_millicents_per_million > 0;
INSERT INTO llm_model_rate (model_id, token_type, rate_millicents_per_million)
SELECT id, 'OUTPUT', output_price_millicents_per_million FROM llm_model
 WHERE input_price_millicents_per_million > 0 AND output_price_millicents_per_million > 0;

ALTER TABLE llm_model DROP COLUMN input_price_millicents_per_million;
ALTER TABLE llm_model DROP COLUMN output_price_millicents_per_million;

-- 4. The ledger. Grain = charge line; a call is the set of rows sharing call_ref.
CREATE TABLE llm_charge (
    id            UUID         PRIMARY KEY,
    review_id     TEXT         NOT NULL,
    call_ref      TEXT         NOT NULL,
    kind          VARCHAR(16)  NOT NULL,   -- review | reconcile | followup
    model         VARCHAR(255) NOT NULL,
    pricing_mode  VARCHAR(16)  NOT NULL,
    token_type    VARCHAR(32)  NOT NULL,
    tokens        INT          NOT NULL CHECK (tokens >= 0),
    rate_millicents_per_million BIGINT,
    cost_millicents             BIGINT,
    priced_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- One call's dimension is charged exactly once. recordLlmCall used to be an unguarded INSERT
    -- whose only protection was a STALENESS check, so a redelivered result between ReviewGenerated
    -- and ReviewCompleted inserted a second row for a call that happened once.
    UNIQUE (call_ref, token_type),
    CHECK (pricing_mode IN ('METERED', 'UNMETERED', 'UNKNOWN')),
    CHECK ((pricing_mode = 'UNKNOWN') = (cost_millicents IS NULL)),
    CHECK (pricing_mode <> 'UNKNOWN'   OR rate_millicents_per_million IS NULL),
    CHECK (pricing_mode <> 'METERED'   OR rate_millicents_per_million IS NOT NULL),
    CHECK (pricing_mode <> 'UNMETERED'
           OR (rate_millicents_per_million = 0 AND cost_millicents = 0)),
    -- An unreconciled call has no per-type split, so a METERED rate cannot be applied to it.
    -- UNMETERED stays valid: cost is zero whatever the split turns out to be.
    CHECK (token_type <> 'TOTAL' OR pricing_mode <> 'METERED')
);
CREATE INDEX llm_charge_review_idx ON llm_charge (review_id, priced_at);
CREATE INDEX llm_charge_priced_idx ON llm_charge (priced_at);

-- 5. The old ledger and its denormalized rollup go. Every 0 in review_llm_call is ambiguous -- the
--    coercion means "was unpriced at the time", not "was free" -- and the distinguishing information
--    was never written, so no migration can recover it. These are development smoke-test rows.
DROP TABLE review_llm_call;
ALTER TABLE review_status
    DROP COLUMN model,
    DROP COLUMN tokens_in,
    DROP COLUMN tokens_out,
    DROP COLUMN cost_millicents;
```

- [ ] **Step 4: Run the schema test**

Run: `./gradlew :spire-orchestrator:test --tests 'dev.codespire.orchestrator.llm.LlmChargeSchemaIT'`
Expected: PASS. `gen_random_uuid()` needs `pgcrypto` on Postgres < 13; if it errors, replace it in the test helper with a literal `'00000000-0000-0000-0000-0000000000NN'::uuid` per case.

- [ ] **Step 5: Prove the negative assertions can fail**

Temporarily delete the `CHECK (token_type <> 'TOTAL' OR pricing_mode <> 'METERED')` line, re-run, and confirm `aTotalLineCannotBeMetered` fails. Restore it. Repeat for the `UNMETERED` check. Four of these assertions pass trivially if a constraint is renamed away, which is exactly the vacuity hole `ContractSchemaSnapshotTest` had.

- [ ] **Step 6: Commit**

```bash
git add spire-orchestrator/src/main/resources/db/migration spire-orchestrator/src/test
git commit -m "Add the LLM charge ledger and pricing mode (V30)

One row per token type per call, carrying the rate that priced it, so every
historical figure is reproducible as tokens x rate and a later price edit
cannot reach it. A UNIQUE (call_ref, token_type) closes a real double-count
window: the old insert's only guard was a staleness check, so a redelivered
result between ReviewGenerated and ReviewCompleted charged one call twice.

pricing_mode makes zero a category rather than a value. An asserted zero for
self-hosted inference is now distinguishable from an absent price, which is
what a spend cap needs to be more than decorative.

CHECK constraints make the illegal combinations unrepresentable at the
storage layer, not just in the service that writes them.

review_llm_call and review_status's cost columns are dropped: every zero in
them is ambiguous, the information to disambiguate was never written, and
they hold development smoke-test rows."
```

---

## Task 2: Neutral token vocabulary on the contract

Reshape `ModelUsage`, then remove every read of the columns Task 1 dropped. Token accounting is still
`INPUT`/`OUTPUT` only — the real vendor mapping is Task 3 — so the behaviour change here is confined to
where cost comes from: nowhere yet, rather than a fabricated zero.

**Files:**
- Create: `spire-contract/src/main/java/dev/codespire/contract/review/TokenType.java`
- Create: `spire-contract/src/main/java/dev/codespire/contract/review/TokenCount.java`
- Modify: `spire-contract/src/main/java/dev/codespire/contract/review/ModelUsage.java`
- Modify: `spire-contract/src/test/resources/contract-schema.txt`
- Modify: `spire-llm/src/main/java/dev/codespire/llm/LangChain4jLlmProvider.java:147-152`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/ResultSaga.java:163-169, 235, 422-444`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewProjection.java:346-395, 961-966, 1078-1091, 1149-1152, 1290, 1317-1351, 1474-1483`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewDetail.java:45-46, 66, 73-75`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewSummary.java`
- Test: `spire-contract/src/test/java/dev/codespire/contract/review/ModelUsageTest.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/llm/LlmChargeSchemaIT.java` — **the
  `TokenType.values()` swap described in Step 8. Do not skip it; it is why this file is listed.**

**Interfaces:**
- Produces: `TokenType` enum with constants `INPUT, CACHED_INPUT, CACHE_WRITE, OUTPUT, REASONING, TOTAL`; `record TokenCount(TokenType type, int tokens)`; `record ModelUsage(String model, List<TokenCount> counts, int reportedTotal, boolean reconciled)` with `int tokensOf(TokenType)` and static `ModelUsage of(String model, int input, int output)`.

- [ ] **Step 1: Write the failing test**

`spire-contract/src/test/java/dev/codespire/contract/review/ModelUsageTest.java`:

```java
package dev.codespire.contract.review;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ModelUsage carries a PARTITION of a call's tokens and no money at all. Both properties are load
 * bearing: the partition is what makes summing charge lines correct, and the absence of a cost field
 * is what stops the worker — which holds no price catalog — from reporting one.
 */
class ModelUsageTest {

    @Test
    void tokensOfReturnsTheCountForATypeAndZeroForOneTheVendorDidNotReport() {
        ModelUsage usage = new ModelUsage("TEST-MODEL",
                List.of(new TokenCount(TokenType.INPUT, 120),
                        new TokenCount(TokenType.OUTPUT, 30)),
                150, true);

        assertEquals(120, usage.tokensOf(TokenType.INPUT));
        assertEquals(30, usage.tokensOf(TokenType.OUTPUT));
        assertEquals(0, usage.tokensOf(TokenType.CACHED_INPUT));
    }

    @Test
    void theConvenienceFactoryBuildsATwoTypePartitionThatReconciles() {
        ModelUsage usage = ModelUsage.of("TEST-MODEL", 120, 30);

        assertEquals(150, usage.reportedTotal());
        assertTrue(usage.reconciled());
        assertEquals(2, usage.counts().size());
    }

    /** Defensive copy: a caller mutating its list afterwards must not change a recorded usage. */
    @Test
    void countsAreCopiedNotAliased() {
        List<TokenCount> mutable = new java.util.ArrayList<>();
        mutable.add(new TokenCount(TokenType.INPUT, 5));
        ModelUsage usage = new ModelUsage("TEST-MODEL", mutable, 5, true);

        mutable.clear();

        assertEquals(1, usage.counts().size());
    }

    /** A null counts list is an empty partition, never a NullPointerException downstream. */
    @Test
    void nullCountsBecomeEmpty() {
        assertEquals(0, new ModelUsage("TEST-MODEL", null, 0, true).counts().size());
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew :spire-contract:test --tests 'dev.codespire.contract.review.ModelUsageTest'`
Expected: FAIL — compilation error, `TokenType` and `TokenCount` do not exist and `ModelUsage` has the old shape.

- [ ] **Step 3: Create the two new contract types**

`TokenType.java`:

```java
package dev.codespire.contract.review;

/**
 * The neutral token-billing dimensions, as a PARTITION: every token a call consumed belongs to
 * exactly one of these. Vendors disagree on whether their detail counts are included in or additional
 * to the headline numbers, so each adapter subtracts as needed to produce disjoint counts — see
 * {@code TokenUsageMapper}.
 *
 * <p>{@link #TOTAL} is the degraded case, not a dimension: it carries a call's whole token count when
 * the per-type breakdown could not be reconciled against the vendor's own total. A TOTAL line can
 * never be priced at a metered rate, because there is no split to apply rates to.
 */
public enum TokenType {
    /** Fresh prompt tokens — the vendor's input count minus any cached portion. */
    INPUT,
    /** Prompt tokens served from the vendor's cache, billed at a reduced rate. */
    CACHED_INPUT,
    /** Prompt tokens written INTO the vendor's cache, billed at a premium. */
    CACHE_WRITE,
    /** Generated tokens — the vendor's output count minus any separately reported reasoning. */
    OUTPUT,
    /** Reasoning/thinking tokens, where the vendor reports them apart from output. */
    REASONING,
    /** Degraded: an unreconcilable call's whole token count. Never metered. */
    TOTAL
}
```

`TokenCount.java`:

```java
package dev.codespire.contract.review;

/** One token-billing dimension's count for a single LLM call. */
public record TokenCount(TokenType type, int tokens) {
}
```

- [ ] **Step 4: Reshape `ModelUsage`**

Replace the whole file:

```java
package dev.codespire.contract.review;

import java.util.List;

/**
 * What an LLM adapter reports about one call: which model, and how many tokens of each billing
 * dimension.
 *
 * <p><b>No money.</b> Pricing needs the operator-entered catalog, which only the orchestrator owns
 * (ADR-018), so an adapter cannot compute a cost and — after this type lost its cost field — cannot
 * express one either. The field it replaced was always zero and its own comment said pricing happened
 * elsewhere, which is the kind of documented lie that eventually gets believed.
 *
 * @param counts        a partition — each token counted once, under exactly one {@link TokenType}
 * @param reportedTotal the vendor's OWN total for the call, kept as the independent check on the
 *                      partition rather than as a derived convenience
 * @param reconciled    whether {@code counts} sums to {@code reportedTotal}. False means the
 *                      breakdown could not be trusted and {@code counts} holds a single
 *                      {@link TokenType#TOTAL} line instead.
 */
public record ModelUsage(String model, List<TokenCount> counts, int reportedTotal, boolean reconciled) {

    public ModelUsage {
        counts = counts == null ? List.of() : List.copyOf(counts);
    }

    /** Tokens recorded for one dimension; 0 when the vendor did not report it. */
    public int tokensOf(TokenType type) {
        int total = 0;
        for (TokenCount count : counts) {
            if (count.type() == type) {
                total += count.tokens();
            }
        }
        return total;
    }

    /** A plain input/output call — the shape every vendor reports and most tests need. */
    public static ModelUsage of(String model, int input, int output) {
        return new ModelUsage(model,
                List.of(new TokenCount(TokenType.INPUT, input), new TokenCount(TokenType.OUTPUT, output)),
                input + output, true);
    }
}
```

- [ ] **Step 5: Run the new test to confirm it passes**

Run: `./gradlew :spire-contract:test --tests 'dev.codespire.contract.review.ModelUsageTest'`
Expected: PASS.

- [ ] **Step 6: Update every call site so the build compiles**

Task 1 already dropped `review_llm_call` and `review_status`'s four usage columns, so this step does
not invent a temporary value for any of them — it removes the code that read them. **Nothing in this
task may write a zero cost.** A zero standing in for an absent price is the exact defect this branch
exists to remove, and the plan's no-synthetic-data constraint binds the intermediate commits too.

`LangChain4jLlmProvider.java:147-152` — keep the two-bucket behaviour for now; Task 3 replaces it with
the real vendor mapping:

```java
        ChatResponse response = model.chat(request);
        TokenUsage usage = response.tokenUsage();
        return new Completion(
                response.aiMessage().text(),
                ModelUsage.of(params.model(),
                        usage != null && usage.inputTokenCount() != null ? usage.inputTokenCount() : 0,
                        usage != null && usage.outputTokenCount() != null ? usage.outputTokenCount() : 0));
```

`ResultSaga.java` — delete `priced(ReviewResult)` (lines 422-435) and `priceUsage(ModelUsage)` (437-444)
entirely, and simplify the `ReviewGenerated` branch:

```java
            case ReviewGenerated e -> ifCurrentRun(e.reviewId(), e.commit(), "ReviewGenerated", () -> {
                projection.appendEvent(e.reviewId(), "result", "ReviewGenerated",
                        e.result().findings().size() + " findings");
                projection.recordOutcome(e.reviewId(), e.result(), ReviewProjection.STAGE_COMMENTS);
```

Also remove the `recordLlmCall` calls at 166, 168 and 235, and the `llmModels` injection if nothing
else uses it. Task 8 reinstates recording, against the new ledger.

`ReviewProjection.java` — seven changes, all of them deletions of dropped-column access. **Find every
reader before you start**: `grep -n 'r\.model\|r\.tokensIn\|r\.tokensOut\|r\.costMillicents'` over the
file, and confirm your edits cover every hit. Six of the seven below were found that way rather than by
reading the plan, so do not assume this list is exhaustive either.

1. **`recordOutcome` (346-372):** drop `model`, `tokens_in`, `tokens_out`, `cost_millicents` from the
   `UPDATE` and delete the whole `usage == null` branch. It now writes only `findings_count`,
   `findings_json`, `stage`, `updated_at`, and no longer needs the `usage` local at all.
2. **Delete `recordLlmCall` (379-395)** — Task 7 replaces it with `recordCharges`.
3. **Delete `withReviewCall` (1338-1347)** — it synthesized a "review" call row out of the dropped
   `review_status` columns. With the ledger as the only source there is nothing to merge, so
   `toDetail` (1317-1328) passes the charge lines straight through.
4. **Delete `usageView(ReviewRow)` (1474-1483), `ReviewDetail.UsageView` (`:66`) and the `usage`
   component of `ReviewDetail` (`:45`).** It reads all four dropped columns to render a single
   model/prompt/completion/cost summary. The per-type charge breakdown Task 10 builds is a strict
   superset of it, so re-deriving this legacy display from the ledger only to delete it two tasks later
   is pure churn. **Consequence to accept, not work around:** the review-detail page shows no usage
   figures between this task and Task 10. That window already exists regardless — this task changes
   `ReviewDetail`'s `llmCalls` component to `chargeLines`, which the UI does not read until Task 10.
5. **Trim `ReviewRow` (1149-1152)** of its `model`, `tokensIn`, `tokensOut`, `costMillicents` fields and
   stop selecting/reading them wherever the row is mapped. `Integer`-typed token fields and a nullable
   `Long` cost go together — remove all four.
6. **`llmTypeFor(Connection, String model)` (:1290)** still takes a model NAME and maps it to a vendor
   type, so it survives unchanged — but confirm its caller now feeds it the ledger-derived model rather
   than `r.model`, which no longer exists.
7. **Replace `llmCalls(String)` (1078-1091) with `chargeLines(String)`** reading the new ledger:

```java
    /** Every charge line recorded for a review, oldest first — the cost card's raw material. */
    public List<ReviewDetail.ChargeLineView> chargeLines(String reviewId) {
        List<ReviewDetail.ChargeLineView> out = new ArrayList<>();
        String sql = """
                SELECT kind, model, token_type, tokens, rate_millicents_per_million, cost_millicents,
                       pricing_mode, priced_at
                  FROM llm_charge WHERE review_id = ? ORDER BY priced_at, token_type
                """;
        // ... map each row; rate and cost are NULLABLE, so read them via getObject(..., Long.class)
        //     and let NULL stay NULL. Reading them with getLong would turn "unpriced" back into 0,
        //     which is the bug this branch removes.
        return out;
    }
```

**`ReviewDetail.java`** — replace the `LlmCall` record (73-75) with:

```java
    /**
     * One token dimension of one LLM call, priced. {@code rateMillicentsPerMillion} and
     * {@code costMillicents} are null exactly when {@code pricingMode} is "UNKNOWN" — never 0, which
     * would be indistinguishable from an UNMETERED model's asserted zero.
     *
     * <p>{@code kind}, {@code tokenType} and {@code pricingMode} are Strings here, not the enums they
     * mirror, deliberately: this is an outbound view read straight from the ledger, whose CHECK
     * constraints already restrict those columns. The enums exist to protect the WRITE path, where a
     * typo'd literal costs a lost charge; a display type has nothing to lose by carrying the stored
     * value verbatim, and typing it would force this record to be defined after the enums rather than
     * alongside the query that fills it.
     */
    public record ChargeLineView(String kind, String model, String tokenType, int tokens,
                                 Long rateMillicentsPerMillion, Long costMillicents,
                                 String pricingMode, String pricedAt) {
    }
```

and change the `llmCalls` component (line 46) to `List<ChargeLineView> chargeLines`. The `usage`
component and its `UsageView` record are deleted outright per change 4 above — `ReviewDetail` loses one
component as well as changing another.

**`listSummaries` (961-966)** selects `rs.*` and names `rs.cost_millicents` and `rs.model`, so it
breaks at runtime once the columns are gone. Re-derive all three from the ledger — the query is
touched once here rather than again in Task 7:

```sql
SELECT rs.*,
       (SELECT model FROM llm_charge c WHERE c.review_id = rs.review_id
         ORDER BY c.priced_at DESC LIMIT 1)                                      AS model,
       (SELECT m.type FROM llm_model m
         WHERE m.name = (SELECT model FROM llm_charge c WHERE c.review_id = rs.review_id
                          ORDER BY c.priced_at DESC LIMIT 1) LIMIT 1)            AS llm_type,
       COALESCE((SELECT SUM(c.cost_millicents) FROM llm_charge c
                  WHERE c.review_id = rs.review_id), 0)                          AS total_cost_millicents,
       COALESCE((SELECT COUNT(DISTINCT c.call_ref) FROM llm_charge c
                  WHERE c.review_id = rs.review_id AND c.pricing_mode = 'UNKNOWN'), 0) AS unpriced_calls
  FROM review_status rs ORDER BY rs.updated_at DESC
```

Add `int unpricedCalls` to `ReviewSummary`. The ledger is empty until Task 8 starts writing it, so
totals read as 0 — honestly zero, because there are no charges, not because a price was missing. That
distinction is exactly what `unpricedCalls` exists to carry.

Test sites — replace `new ModelUsage("m", 1, 1, 0)` with `ModelUsage.of("m", 1, 1)` in each of:
`spire-gateway/.../WireFormatRoundTripTest.java:52`, `spire-review-worker/.../WorkerPipelineTest.java:125`,
`spire-contract/.../ReconciliationTypesTest.java:71`, `spire-review-worker/.../ReviewWorkerTest.java:207,228,235,274,557`,
`spire-review-worker/.../FollowUpWorkerTest.java:52,86,201`, `spire-review-worker/.../FollowUpWorkerPromptTest.java:52`,
`spire-llm/.../FindingsParserTest.java:14`, `spire-review-worker/.../CircuitBreakingLlmProviderTest.java:152`,
`spire-orchestrator/.../ResultSagaRetryTest.java:293,340`, `spire-orchestrator/.../ReviewProjectionTest.java:79,385`,
`spire-orchestrator/.../ReviewProjectionPriorRunIT.java:109,133`.

`ReviewProjectionTest.java:79` and `:385` assert a cost that `recordOutcome` no longer stores — **delete
those assertions** rather than asserting `0`, along with any assertion on `review_llm_call`. Cost is
asserted against the ledger in Task 7; an assertion that a dropped column reads zero tests nothing and
enshrines the conflation.

- [ ] **Step 7: Regenerate the ADR-013 contract snapshot**

Run: `./gradlew :spire-contract:test --tests 'dev.codespire.contract.ContractSchemaSnapshotTest'`
Expected: FAIL with a diff naming `src/test/resources/contract-schema.txt`.

This is a **deliberate** wire change, safe because `DomainEvent` carries no usage field, so the event store is untouched and no upcaster is needed. Copy the actual shape the failure prints into `spire-contract/src/test/resources/contract-schema.txt`, then re-run.

Expected: PASS.

- [ ] **Step 8: Activate Task 1's token-type drift guard**

Task 1 added `LlmChargeSchemaIT.theTokenTypeCheckAcceptsEveryKnownTokenType`, which asserts the ledger's
`token_type` CHECK accepts every token type the code can produce. Because `TokenType` did not exist yet,
it is currently driven by a **hardcoded `String[]` of the six names**, written from the same CHECK it is
meant to police. In that form it proves only that an array agrees with a constraint that the same author
copied it from — a tautology, not a guard.

You are creating `TokenType`. Swap the literal array for the enum so the guard becomes real:

```java
        for (TokenType type : TokenType.values()) {
            assertDoesNotThrow(() -> exec(insert("UNKNOWN", type.name(), "NULL", "NULL")),
                    "llm_charge.token_type CHECK rejects TokenType." + type
                            + " — add it to the CHECK in V30__llm_charge_ledger.sql");
        }
```

Import `dev.codespire.contract.review.TokenType`; `spire-orchestrator` already declares
`implementation(project(":spire-contract"))`, so no build change is needed. Delete the comment that
asked for this swap.

**Why this step is spelled out rather than left to a code comment:** without it, nothing in the plan
directs anyone back to that file, the array stays hardcoded indefinitely, and the CHECK is free to drift
from the enum — which is the exact failure the test was added to prevent. A guard that cannot detect its
own subject is worse than none, because it reports success.

Verify it is load-bearing: temporarily add a constant to `TokenType` without touching the migration, run
the test, confirm it now FAILS naming that constant, then remove the constant.

Run: `./gradlew :spire-orchestrator:test --tests 'dev.codespire.orchestrator.llm.LlmChargeSchemaIT'`
Expected: PASS, 10/10.

- [ ] **Step 9: Run the Docker-free tier**

Run: `./gradlew testFast`
Expected: PASS, all 13 modules.

- [ ] **Step 10: Commit**

```bash
git add spire-contract spire-llm spire-orchestrator spire-review-worker spire-gateway
git commit -m "Carry token usage as a typed partition, not two counts

ModelUsage becomes model + a list of per-type counts + the vendor's own
total + whether the two reconcile, and loses its cost field entirely. The
field was always zero on the wire and its comment said pricing happened
elsewhere; an adapter that holds no price catalog should not be able to
report a cost at all.

Behaviour is unchanged here — still INPUT and OUTPUT only. The vendor
mapping and the pricing that use the new shape follow.

The contract snapshot is regenerated deliberately: DomainEvent carries no
usage, so the event store is untouched and no upcaster is needed."
```

---

## Task 3: Map each vendor's usage onto the partition

**Files:**
- Create: `spire-llm/src/main/java/dev/codespire/llm/TokenUsageMapper.java`
- Modify: `spire-llm/src/main/java/dev/codespire/llm/LangChain4jLlmProvider.java:145-153`
- Test: `spire-llm/src/test/java/dev/codespire/llm/TokenUsageMapperTest.java`

**Interfaces:**
- Consumes: `ModelUsage`, `TokenCount`, `TokenType` from Task 2.
- Produces: `TokenUsageMapper.map(String model, TokenUsage usage) -> ModelUsage` (static, 2 params).

**Read before starting:** the vendor accessors, confirmed present in LangChain4j 1.18.1 by inspecting the jars:

| Neutral | OpenAI (`OpenAiTokenUsage`) | Anthropic (`AnthropicTokenUsage`) | Gemini (`GoogleAiGeminiTokenUsage`) |
|---|---|---|---|
| `INPUT` | `inputTokenCount()` − cached | `inputTokenCount()` (already excludes cache) | `inputTokenCount()` − cached |
| `CACHED_INPUT` | `inputTokensDetails().cachedTokens()` | `cacheReadInputTokens()` | `cachedContentTokenCount()` |
| `CACHE_WRITE` | not reported | `cacheCreationInputTokens()` | not reported |
| `OUTPUT` | `outputTokenCount()` − reasoning | `outputTokenCount()` | `outputTokenCount()` |
| `REASONING` | `outputTokensDetails().reasoningTokens()` | not reported | `thoughtsTokenCount()` |

**If the reconciliation test fails for a vendor, the subtraction above is wrong for it — flip that vendor's inclusive/exclusive assumption. Do NOT relax the assertion.** Getting this wrong is precisely what the invariant exists to catch, and a relaxed assertion converts a caught bug into a silent mispricing.

### What each vendor's total actually covers — read this before writing the mapper

The cross-check compares the buckets against `totalTokenCount()`, which only works where that value is
a genuine grand total. **On Anthropic it is not.** Verified by disassembling
`langchain4j-anthropic-1.18.1.jar`, not inferred:

- `AnthropicTokenUsage(Builder)` calls `super(builder.inputTokenCount, builder.outputTokenCount)`; the
  two cache fields become its own fields and never reach the base class.
- The base `TokenUsage(Integer, Integer)` constructor derives the third value as `sum(input, output)`.
- `AnthropicTokenUsage.Builder` has **no `totalTokenCount(...)` setter** — one cannot be supplied.

So on Anthropic `totalTokenCount()` is always exactly `input + output`, **excluding cache reads and
writes**. Checking "all buckets sum to the vendor total" would therefore fail on every cached Anthropic
call and degrade it to a single unpriceable `TOTAL` line — making cached calls, the ones caching exists
to make cheap, the only ones we cannot price.

So cross-check only the buckets the vendor's total actually covers:

| Vendor | Total covers | Buckets outside the check |
|---|---|---|
| OpenAI | every bucket (cached ⊂ input, reasoning ⊂ output, total = prompt + completion) | none |
| Gemini | every bucket (cached ⊂ prompt, total includes thoughts) | none |
| **Anthropic** | `INPUT + OUTPUT` only | `CACHED_INPUT`, `CACHE_WRITE` |
| Plain `TokenUsage` | `INPUT + OUTPUT` | none exist |

`ModelUsage.reportedTotal` is the sum of **all** buckets — the call's true token count — while
`reconciled` reports whether the *checkable* subset agreed. For Anthropic no independent grand total
exists, and saying so is better than inventing one.

**Builder APIs, all verified present by `javap` so your test compiles first time:**
`OpenAiTokenUsage.builder()` takes `.inputTokenCount(Integer)`, `.inputTokensDetails(...)`,
`.outputTokenCount(Integer)`, `.outputTokensDetails(...)`, `.totalTokenCount(Integer)`;
`OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(Integer)` and
`OpenAiTokenUsage.OutputTokensDetails.builder().reasoningTokens(Integer)` both exist.
`GoogleAiGeminiTokenUsage.builder()` takes `.inputTokenCount`, `.outputTokenCount`, `.totalTokenCount`,
`.cachedContentTokenCount`, `.thoughtsTokenCount`.
`AnthropicTokenUsage.builder()` takes `.inputTokenCount`, `.outputTokenCount`,
`.cacheCreationInputTokens`, `.cacheReadInputTokens` — **and nothing else.**

- [ ] **Step 1: Write the failing test**

`spire-llm/src/test/java/dev/codespire/llm/TokenUsageMapperTest.java`:

```java
package dev.codespire.llm;

import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.TokenType;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.googleai.GoogleAiGeminiTokenUsage;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mapper's one invariant: every token lands in exactly one bucket, and the buckets sum to the
 * total the VENDOR computed. That cross-check is what makes the mapping trustworthy without trusting
 * anyone's memory of each vendor's caching semantics — the vendors disagree on whether detail counts
 * are included in or additional to the headline numbers, and a wrong guess yields a plausible number.
 *
 * <p>All counts here are obviously-synthetic round numbers driving a pure function; none becomes
 * user-visible state.
 */
class TokenUsageMapperTest {

    private static void assertPartitions(ModelUsage usage) {
        int summed = 0;
        for (TokenType type : TokenType.values()) {
            summed += usage.tokensOf(type);
        }
        assertTrue(usage.reconciled(),
                "the buckets the vendor's total covers must agree with that total");
        assertEquals(usage.reportedTotal(), summed,
                "reportedTotal must be the sum of EVERY bucket — the call's true token count, which on "
                        + "Anthropic exceeds the vendor's own input+output total");
    }

    @Test
    void openAiSplitsCachedOutOfInputAndReasoningOutOfOutput() {
        OpenAiTokenUsage vendor = OpenAiTokenUsage.builder()
                .inputTokenCount(1000)
                .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(400).build())
                .outputTokenCount(300)
                .outputTokensDetails(OpenAiTokenUsage.OutputTokensDetails.builder().reasoningTokens(100).build())
                .totalTokenCount(1300)
                .build();

        ModelUsage usage = TokenUsageMapper.map("TEST-MODEL", vendor);

        assertEquals(600, usage.tokensOf(TokenType.INPUT));
        assertEquals(400, usage.tokensOf(TokenType.CACHED_INPUT));
        assertEquals(200, usage.tokensOf(TokenType.OUTPUT));
        assertEquals(100, usage.tokensOf(TokenType.REASONING));
        assertPartitions(usage);
    }

    /**
     * Anthropic reports cache reads and writes as line items ADDITIONAL to its input count, and its
     * builder cannot be given a total at all — LangChain4j derives one as input + output, excluding
     * both cache buckets. So the partition sums to more than the vendor's "total", and the cross-check
     * must cover only INPUT + OUTPUT. Checking all four against that total would fail on every cached
     * call and make cached calls the only unpriceable ones.
     */
    @Test
    void anthropicTreatsCacheCountsAsAdditiveLineItemsOutsideItsTotal() {
        AnthropicTokenUsage vendor = AnthropicTokenUsage.builder()
                .inputTokenCount(600)
                .cacheReadInputTokens(400)
                .cacheCreationInputTokens(50)
                .outputTokenCount(200)
                .build();

        ModelUsage usage = TokenUsageMapper.map("TEST-MODEL", vendor);

        assertEquals(600, usage.tokensOf(TokenType.INPUT));
        assertEquals(400, usage.tokensOf(TokenType.CACHED_INPUT));
        assertEquals(50, usage.tokensOf(TokenType.CACHE_WRITE));
        assertEquals(200, usage.tokensOf(TokenType.OUTPUT));
        assertPartitions(usage);
        // reportedTotal is the TRUE token count (all four buckets), not the vendor's partial figure.
        assertEquals(1250, usage.reportedTotal());
    }

    /**
     * Pins the Anthropic semantics the mapper depends on, so a LangChain4j upgrade that starts folding
     * cache tokens into the derived total is caught here rather than by every cached call silently
     * degrading to an unpriceable TOTAL line.
     */
    @Test
    void anthropicsDerivedTotalStillExcludesCacheTokens() {
        AnthropicTokenUsage vendor = AnthropicTokenUsage.builder()
                .inputTokenCount(600)
                .cacheReadInputTokens(400)
                .cacheCreationInputTokens(50)
                .outputTokenCount(200)
                .build();

        assertEquals(800, vendor.totalTokenCount(),
                "LangChain4j derives Anthropic's total as input + output only. If this now includes the "
                        + "cache buckets, TokenUsageMapper's Anthropic cross-check must cover them too.");
    }

    @Test
    void geminiSplitsCachedContentOutOfInputAndReportsThoughtsSeparately() {
        GoogleAiGeminiTokenUsage vendor = GoogleAiGeminiTokenUsage.builder()
                .inputTokenCount(1000)
                .cachedContentTokenCount(250)
                .outputTokenCount(300)
                .thoughtsTokenCount(120)
                .totalTokenCount(1420)
                .build();

        ModelUsage usage = TokenUsageMapper.map("TEST-MODEL", vendor);

        assertEquals(750, usage.tokensOf(TokenType.INPUT));
        assertEquals(250, usage.tokensOf(TokenType.CACHED_INPUT));
        assertEquals(300, usage.tokensOf(TokenType.OUTPUT));
        assertEquals(120, usage.tokensOf(TokenType.REASONING));
        assertPartitions(usage);
    }

    /** A vendor we have no mapping for still yields a usable two-bucket partition. */
    @Test
    void aPlainTokenUsageMapsToInputAndOutput() {
        ModelUsage usage = TokenUsageMapper.map("TEST-MODEL", new TokenUsage(700, 300, 1000));

        assertEquals(700, usage.tokensOf(TokenType.INPUT));
        assertEquals(300, usage.tokensOf(TokenType.OUTPUT));
        assertPartitions(usage);
    }

    /**
     * The degraded path. When the buckets cannot be made to sum to the vendor's total — a new billing
     * dimension we do not map yet — record the vendor's own total and say so, rather than publishing a
     * breakdown that quietly loses tokens.
     */
    @Test
    void anIrreconcilableBreakdownCollapsesToASingleUnreconciledTotal() {
        ModelUsage usage = TokenUsageMapper.map("TEST-MODEL", new TokenUsage(700, 300, 1500));

        assertFalse(usage.reconciled());
        assertEquals(1500, usage.reportedTotal());
        assertEquals(1500, usage.tokensOf(TokenType.TOTAL));
        assertEquals(1, usage.counts().size());
    }

    /** No usage at all is still a countable call, not a crash and not an invented number. */
    @Test
    void nullUsageYieldsAZeroTotalLine() {
        ModelUsage usage = TokenUsageMapper.map("TEST-MODEL", null);

        assertEquals(0, usage.reportedTotal());
        assertEquals(1, usage.counts().size());
        assertEquals(0, usage.tokensOf(TokenType.TOTAL));
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew :spire-llm:test --tests 'dev.codespire.llm.TokenUsageMapperTest'`
Expected: FAIL — `TokenUsageMapper` does not exist.

- [ ] **Step 3: Write the mapper**

`spire-llm/src/main/java/dev/codespire/llm/TokenUsageMapper.java`:

```java
package dev.codespire.llm;

import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.TokenCount;
import dev.codespire.contract.review.TokenType;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.googleai.GoogleAiGeminiTokenUsage;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Maps a vendor's token accounting onto the neutral {@link TokenType} partition.
 *
 * <p>The detail counts live on vendor subclasses, not on the base {@link TokenUsage}, and the vendors
 * disagree about whether those details are INCLUDED in the headline input/output numbers or
 * ADDITIONAL to them. OpenAI's input count includes its cached portion; Anthropic's excludes cache
 * reads entirely. Summing naively would double-count one and undercount the other, and both produce a
 * number that looks right.
 *
 * <p>So every mapping is checked against {@code totalTokenCount()} — arithmetic the vendor computed
 * independently. A mismatch is not smoothed over: the breakdown is discarded in favour of a single
 * {@link TokenType#TOTAL} line marked unreconciled, which is visible to an operator and cannot be
 * mistaken for a priced call.
 */
public final class TokenUsageMapper {

    private TokenUsageMapper() {
    }

    public static ModelUsage map(String model, TokenUsage usage) {
        if (usage == null) {
            return unreconciled(model, 0);
        }
        List<TokenCount> counts = partition(usage);
        int fullTotal = sumOf(counts, EnumSet.allOf(TokenType.class));
        Integer vendorTotal = usage.totalTokenCount();
        // No vendor total means nothing contradicts the partition — trust it and record our own sum.
        if (vendorTotal == null) {
            return new ModelUsage(model, counts, fullTotal, true);
        }
        if (sumOf(counts, coveredByTotal(usage)) != vendorTotal) {
            // Our arithmetic is the suspect party here, so record the vendor's own figure rather than
            // a sum derived from the extraction that just failed its own check.
            return unreconciled(model, vendorTotal);
        }
        return new ModelUsage(model, counts, fullTotal, true);
    }

    /**
     * Which buckets the vendor's own total accounts for.
     *
     * <p>Anthropic's is derived by LangChain4j from input and output alone — its builder cannot even be
     * given a total — so its two cache buckets sit OUTSIDE the cross-check. Including them would fail
     * every cached Anthropic call and leave exactly the cheap calls unpriceable. Every other vendor
     * reports a genuine grand total that covers all of its buckets.
     */
    private static Set<TokenType> coveredByTotal(TokenUsage usage) {
        if (usage instanceof AnthropicTokenUsage) {
            return EnumSet.of(TokenType.INPUT, TokenType.OUTPUT);
        }
        return EnumSet.allOf(TokenType.class);
    }

    private static int sumOf(List<TokenCount> counts, Set<TokenType> covered) {
        int total = 0;
        for (TokenCount count : counts) {
            if (covered.contains(count.type())) {
                total += count.tokens();
            }
        }
        return total;
    }

    private static ModelUsage unreconciled(String model, int total) {
        return new ModelUsage(model, List.of(new TokenCount(TokenType.TOTAL, total)), total, false);
    }

    private static List<TokenCount> partition(TokenUsage usage) {
        int input = zeroIfNull(usage.inputTokenCount());
        int output = zeroIfNull(usage.outputTokenCount());
        return switch (usage) {
            case OpenAiTokenUsage u -> openAi(u, input, output);
            case AnthropicTokenUsage u -> anthropic(u, input, output);
            case GoogleAiGeminiTokenUsage u -> gemini(u, input, output);
            default -> nonEmpty(new TokenCount(TokenType.INPUT, input), new TokenCount(TokenType.OUTPUT, output));
        };
    }

    /** Cached is a SUBSET of the input count, and reasoning a subset of output — both subtracted out. */
    private static List<TokenCount> openAi(OpenAiTokenUsage u, int input, int output) {
        int cached = u.inputTokensDetails() == null ? 0 : zeroIfNull(u.inputTokensDetails().cachedTokens());
        int reasoning = u.outputTokensDetails() == null ? 0
                : zeroIfNull(u.outputTokensDetails().reasoningTokens());
        return nonEmpty(new TokenCount(TokenType.INPUT, input - cached),
                new TokenCount(TokenType.CACHED_INPUT, cached),
                new TokenCount(TokenType.OUTPUT, output - reasoning),
                new TokenCount(TokenType.REASONING, reasoning));
    }

    /** Cache reads and writes are ADDITIONAL to the input count — nothing to subtract. */
    private static List<TokenCount> anthropic(AnthropicTokenUsage u, int input, int output) {
        return nonEmpty(new TokenCount(TokenType.INPUT, input),
                new TokenCount(TokenType.CACHED_INPUT, zeroIfNull(u.cacheReadInputTokens())),
                new TokenCount(TokenType.CACHE_WRITE, zeroIfNull(u.cacheCreationInputTokens())),
                new TokenCount(TokenType.OUTPUT, output));
    }

    /** Cached content is a SUBSET of the input count; thoughts are reported apart from output. */
    private static List<TokenCount> gemini(GoogleAiGeminiTokenUsage u, int input, int output) {
        int cached = zeroIfNull(u.cachedContentTokenCount());
        return nonEmpty(new TokenCount(TokenType.INPUT, input - cached),
                new TokenCount(TokenType.CACHED_INPUT, cached),
                new TokenCount(TokenType.OUTPUT, output),
                new TokenCount(TokenType.REASONING, zeroIfNull(u.thoughtsTokenCount())));
    }

    /** Only dimensions that actually occurred, so a call without caching carries no zero rows. */
    private static List<TokenCount> nonEmpty(TokenCount... candidates) {
        List<TokenCount> kept = new ArrayList<>(candidates.length);
        for (TokenCount candidate : candidates) {
            if (candidate.tokens() > 0) {
                kept.add(candidate);
            }
        }
        return List.copyOf(kept);
    }

    private static int zeroIfNull(Integer value) {
        return value == null ? 0 : value;
    }
}
```

- [ ] **Step 4: Run the test**

Run: `./gradlew :spire-llm:test --tests 'dev.codespire.llm.TokenUsageMapperTest'`
Expected: PASS. If a vendor case fails on the reconciliation assertion, flip that vendor's subtraction as instructed above and re-run — the assertion stays.

If a vendor builder method named in the test does not exist, inspect the jar rather than guessing:

```bash
find ~/.gradle/caches/modules-2/files-2.1/dev.langchain4j -name "langchain4j-openai-1.18.1.jar" \
  -o -name "langchain4j-open-ai-1.18.1.jar" | head -1 | \
  xargs -I{} javap -cp {} 'dev.langchain4j.model.openai.OpenAiTokenUsage$Builder'
```

- [ ] **Step 5: Wire the provider to the mapper**

`LangChain4jLlmProvider.java:145-153`:

```java
        ChatResponse response = model.chat(request);
        return new Completion(
                response.aiMessage().text(),
                TokenUsageMapper.map(params.model(), response.tokenUsage()));
```

Remove the now-unused `TokenUsage` import if nothing else in the file uses it.

- [ ] **Step 6: Run the module and the fast tier**

Run: `./gradlew :spire-llm:test && ./gradlew testFast`
Expected: PASS.

- [ ] **Step 7: Mutation-verify the invariant**

Temporarily change `openAi` to not subtract `cached` (`new TokenCount(TokenType.INPUT, input)`).
Run: `./gradlew :spire-llm:test --tests 'dev.codespire.llm.TokenUsageMapperTest'`
Expected: exactly the OpenAI case fails, on the partition assertion. Restore the subtraction and confirm green.

- [ ] **Step 8: Commit**

```bash
git add spire-llm
git commit -m "Map vendor token usage onto a checked partition

Each vendor reports its billing detail on its own TokenUsage subclass, and
they disagree on whether those details are included in or additional to the
headline input/output counts. OpenAI's input includes its cached portion,
Anthropic's excludes cache reads. Summing naively double-counts one and
undercounts the other, and both yield a plausible number.

Every mapping is therefore reconciled against the vendor's own
totalTokenCount. A mismatch discards the breakdown for a single TOTAL line
marked unreconciled, rather than publishing a split that loses tokens — so
an unmapped new dimension announces itself instead of mispricing quietly."
```

---

## Task 4: Pricing types and the registry's rate storage

**Files:**
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/llm/PricingMode.java`
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/llm/ChargeKind.java`
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/llm/ChargeLine.java`
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/llm/ChargeCall.java`
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/llm/CallRefs.java`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/llm/LlmModelInput.java`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/llm/LlmModelView.java`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/llm/LlmModelRegistry.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/llm/LlmModelRegistryPricingTest.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/llm/LlmModelResourceTest.java` — **7
  existing tests, all of which this task breaks and must repair. One of them asserts the defect. See below.**

### The existing catalog test enshrines the bug — do not just make it compile

`LlmModelResourceTest` (104 lines, 7 tests) builds every fixture from the two price fields this task
replaces, so all 7 break. Three of them are already failing before you start, because Task 1 dropped the
two `llm_model` price columns; that is expected and yours to fix, not someone else's breakage.

Repairing them by swapping the fixture to `pricingMode` + `rates` is **not sufficient**, because two of
them assert the old semantics directly:

```java
    @Test
    void uncataloguedModelCostsZero() {
        assertEquals(0L, registry.costMillicents("not-registered", 1_000, 1_000));
    }
```

**That test asserts the exact defect this branch exists to remove.** An uncatalogued model costing zero
is how an unpriceable call became indistinguishable from a free one. Invert it — do not delete it, since
the behaviour is worth pinning in its corrected form:

```java
    /**
     * The regression this branch was built for. An uncatalogued model used to be priced at 0, which froze
     * forever as "this call was free". It must now be UNKNOWN, with a null cost that no sum can absorb.
     */
    @Test
    void anUncataloguedModelIsUnknownNotFree() {
        List<ChargeLine> lines = registry.priceCall("TEST-NOT-REGISTERED", ModelUsage.of("TEST-NOT-REGISTERED", 1_000, 1_000));

        assertFalse(lines.isEmpty());
        assertTrue(lines.stream().allMatch(l -> l.mode() == PricingMode.UNKNOWN));
        assertTrue(lines.stream().allMatch(l -> l.costMillicents() == null));
    }
```

`pricesAReviewFromTokenUsage` (`:72-77`) calls the removed `costMillicents(String, int, int)` and must be
rewritten against `priceCall`, pricing each bucket at its own rate. `rejectsANegativePrice` (`:64`)
becomes obsolete — negative is no longer the interesting case; **zero under `METERED`** is, and that
assertion belongs in Task 5 with the rest of the REST validation. Delete it here and say so in your
report, so its disappearance is a decision on the record rather than an omission.

**Ownership boundary with Task 5:** this task repairs the 7 existing tests so the module is green again.
Task 5 adds the *new* REST validation cases (zero-under-METERED, rates-under-UNMETERED, UNKNOWN rejected
as a chosen mode, 409 on deleting a referenced model). Do not pre-empt those.

**Interfaces:**
- Consumes: `TokenType`, `ModelUsage` (Task 2).
- Produces:
  - `enum PricingMode { METERED, UNMETERED, UNKNOWN }`
  - `enum ChargeKind { REVIEW, RECONCILE, FOLLOWUP }`
  - `record ChargeLine(TokenType tokenType, int tokens, Long rateMillicentsPerMillion, Long costMillicents, PricingMode mode)`
  - `record ChargeCall(String reviewId, String callRef, ChargeKind kind, String model, List<ChargeLine> lines)`
  - `CallRefs.of(String reviewId, String slot, ChargeKind kind) -> String`
  - `LlmModelRegistry.priceCall(String model, ModelUsage usage) -> List<ChargeLine>`
  - `LlmModelRegistry.isPriceable(String model) -> boolean`
  - `LlmModelInput(String type, String name, String label, String pricingMode, Map<String, Long> rates, String outputTokenParam, Boolean supportsTemperature, String reasoningEffort, Map<String, Object> extraParams, Boolean enabled)`
  - `LlmModelView(..., String pricingMode, Map<String, Long> rates, ...)` — the two price fields replaced

**Note on class size:** `LlmModelRegistry` is 256 lines today and this adds rate CRUD plus pricing. If it passes 300, extract the rate table into `LlmModelRateRepository` (`ratesFor`, `replaceRates`) and have the registry delegate. Do not let it sprawl.

- [ ] **Step 1: Write the failing test**

`spire-orchestrator/src/test/java/dev/codespire/orchestrator/llm/LlmModelRegistryPricingTest.java`:

```java
package dev.codespire.orchestrator.llm;

import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.TokenCount;
import dev.codespire.contract.review.TokenType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pricing turns a token partition into charge lines. The cases that matter are the ones where a price
 * is NOT simply available: an uncatalogued model, a dimension with no rate, and an asserted zero. Each
 * must be distinguishable in the result, because collapsing any of them to a plain 0 is the defect
 * this whole change exists to remove.
 *
 * <p>Rates below are obviously-synthetic round numbers, not any vendor's real published price.
 */
@QuarkusTest
class LlmModelRegistryPricingTest {

    @Inject
    LlmModelRegistry registry;

    private LlmModelView metered(String name, Map<String, Long> rates) {
        return registry.create(new LlmModelInput("openai", name, "TEST " + name, "METERED", rates,
                null, null, null, Map.of(), true));
    }

    @Test
    void aMeteredCallIsPricedPerTypeAtTheStoredRate() {
        metered("TEST-METERED-1", Map.of("INPUT", 200_000L, "OUTPUT", 400_000L));

        List<ChargeLine> lines = registry.priceCall("TEST-METERED-1",
                new ModelUsage("TEST-METERED-1",
                        List.of(new TokenCount(TokenType.INPUT, 1_000_000),
                                new TokenCount(TokenType.OUTPUT, 500_000)),
                        1_500_000, true));

        assertEquals(2, lines.size());
        ChargeLine input = lines.stream().filter(l -> l.tokenType() == TokenType.INPUT).findFirst().orElseThrow();
        assertEquals(PricingMode.METERED, input.mode());
        assertEquals(200_000L, input.rateMillicentsPerMillion());
        assertEquals(200_000L, input.costMillicents()); // 1M tokens at 200000/1M
        ChargeLine output = lines.stream().filter(l -> l.tokenType() == TokenType.OUTPUT).findFirst().orElseThrow();
        assertEquals(200_000L, output.costMillicents()); // 500k tokens at 400000/1M
    }

    /**
     * The partial case. A dimension the operator never priced must not silently cost zero, and must
     * not take the rest of the call down with it.
     */
    @Test
    void aDimensionWithNoRateIsUnknownWhileTheRestOfTheCallPrices() {
        metered("TEST-METERED-2", Map.of("INPUT", 200_000L, "OUTPUT", 400_000L));

        List<ChargeLine> lines = registry.priceCall("TEST-METERED-2",
                new ModelUsage("TEST-METERED-2",
                        List.of(new TokenCount(TokenType.INPUT, 1_000_000),
                                new TokenCount(TokenType.CACHE_WRITE, 1_000_000)),
                        2_000_000, true));

        ChargeLine cacheWrite = lines.stream()
                .filter(l -> l.tokenType() == TokenType.CACHE_WRITE).findFirst().orElseThrow();
        assertEquals(PricingMode.UNKNOWN, cacheWrite.mode());
        assertNull(cacheWrite.costMillicents());
        assertNull(cacheWrite.rateMillicentsPerMillion());

        ChargeLine input = lines.stream()
                .filter(l -> l.tokenType() == TokenType.INPUT).findFirst().orElseThrow();
        assertEquals(PricingMode.METERED, input.mode());
        assertEquals(200_000L, input.costMillicents());
    }

    /**
     * The Anthropic shape, and the reason pricing must iterate {@code counts()} rather than any single
     * total. Anthropic reports cache reads and writes as buckets OUTSIDE its own token total, so pricing
     * anything against a total would bill those tokens at the wrong rate or not at all — and cached
     * tokens are the ones a cache exists to make cheap, so under-billing them is the expensive mistake.
     * Each bucket must be priced at its own rate.
     */
    @Test
    void everyBucketIsPricedAtItsOwnRateIncludingTheCacheBuckets() {
        metered("TEST-METERED-CACHE", Map.of(
                "INPUT", 300_000L, "OUTPUT", 600_000L, "CACHED_INPUT", 30_000L, "CACHE_WRITE", 375_000L));

        List<ChargeLine> lines = registry.priceCall("TEST-METERED-CACHE",
                new ModelUsage("TEST-METERED-CACHE",
                        List.of(new TokenCount(TokenType.INPUT, 1_000_000),
                                new TokenCount(TokenType.CACHED_INPUT, 1_000_000),
                                new TokenCount(TokenType.CACHE_WRITE, 1_000_000),
                                new TokenCount(TokenType.OUTPUT, 1_000_000)),
                        4_000_000, true));

        assertEquals(4, lines.size());
        assertEquals(30_000L, lines.stream().filter(l -> l.tokenType() == TokenType.CACHED_INPUT)
                .findFirst().orElseThrow().costMillicents(),
                "a cached-input token must cost its OWN rate, not the fresh-input rate");
        assertEquals(375_000L, lines.stream().filter(l -> l.tokenType() == TokenType.CACHE_WRITE)
                .findFirst().orElseThrow().costMillicents());
        // The call's cost is the sum of its lines — no total-based shortcut can produce this figure.
        assertEquals(1_305_000L, lines.stream().mapToLong(ChargeLine::costMillicents).sum());
    }

    /** Self-hosted inference: an ASSERTED zero, which must read differently from an absent price. */
    @Test
    void anUnmeteredModelChargesAnExplicitZero() {
        registry.create(new LlmModelInput("openai", "TEST-UNMETERED", "TEST self-hosted", "UNMETERED",
                Map.of(), null, null, null, Map.of(), true));

        List<ChargeLine> lines = registry.priceCall("TEST-UNMETERED",
                ModelUsage.of("TEST-UNMETERED", 1_000_000, 500_000));

        assertEquals(2, lines.size());
        assertTrue(lines.stream().allMatch(l -> l.mode() == PricingMode.UNMETERED));
        assertTrue(lines.stream().allMatch(l -> l.costMillicents() == 0L));
        assertTrue(lines.stream().allMatch(l -> l.rateMillicentsPerMillion() == 0L));
    }

    /**
     * The regression that motivated the change: an uncatalogued model used to be priced at 0, which
     * froze forever as "free". It must be UNKNOWN.
     */
    @Test
    void anUncataloguedModelIsUnknownAndNeverZero() {
        List<ChargeLine> lines = registry.priceCall("TEST-NOT-IN-CATALOG",
                ModelUsage.of("TEST-NOT-IN-CATALOG", 1_000, 500));

        assertFalse(lines.isEmpty());
        assertTrue(lines.stream().allMatch(l -> l.mode() == PricingMode.UNKNOWN));
        assertTrue(lines.stream().allMatch(l -> l.costMillicents() == null));
    }

    /** An unreconciled call has no split, so it cannot be metered even for a priced model. */
    @Test
    void anUnreconciledCallYieldsASingleUnknownTotalLine() {
        metered("TEST-METERED-3", Map.of("INPUT", 200_000L, "OUTPUT", 400_000L));

        List<ChargeLine> lines = registry.priceCall("TEST-METERED-3",
                new ModelUsage("TEST-METERED-3", List.of(new TokenCount(TokenType.TOTAL, 900)), 900, false));

        assertEquals(1, lines.size());
        assertEquals(TokenType.TOTAL, lines.get(0).tokenType());
        assertEquals(PricingMode.UNKNOWN, lines.get(0).mode());
    }

    @Test
    void aMeteredModelWithoutInputOrOutputRatesIsRejectedOnSave() {
        assertThrows(IllegalArgumentException.class, () -> registry.create(new LlmModelInput(
                "openai", "TEST-NO-RATES", "TEST no rates", "METERED", Map.of("INPUT", 200_000L),
                null, null, null, Map.of(), true)));
    }

    @Test
    void aMeteredModelWithAZeroRateIsRejectedOnSave() {
        assertThrows(IllegalArgumentException.class, () -> registry.create(new LlmModelInput(
                "openai", "TEST-ZERO-RATE", "TEST zero", "METERED",
                Map.of("INPUT", 0L, "OUTPUT", 400_000L), null, null, null, Map.of(), true)));
    }

    @Test
    void isPriceableIsTrueForAMeteredModelWithBothMandatoryRatesAndForAnUnmeteredOne() {
        metered("TEST-PRICEABLE", Map.of("INPUT", 200_000L, "OUTPUT", 400_000L));
        registry.create(new LlmModelInput("openai", "TEST-PRICEABLE-FREE", "TEST free", "UNMETERED",
                Map.of(), null, null, null, Map.of(), true));

        assertTrue(registry.isPriceable("TEST-PRICEABLE"));
        assertTrue(registry.isPriceable("TEST-PRICEABLE-FREE"));
        assertFalse(registry.isPriceable("TEST-STILL-NOT-IN-CATALOG"));
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew :spire-orchestrator:test --tests 'dev.codespire.orchestrator.llm.LlmModelRegistryPricingTest'`
Expected: FAIL — compilation error; `PricingMode`, `ChargeLine` and the new `LlmModelInput` shape do not exist.

- [ ] **Step 3: Create the pricing value types**

**First, close a drift risk the Task 1 review flagged.** `llm_charge.kind` has a
`CHECK (kind IN ('review', 'reconcile', 'followup'))`, but nothing type-checks what the writer passes —
a typo'd literal in Task 8 would fail the INSERT at runtime and dead-letter the result. `token_type` is
already an enum whose names the CHECK lists verbatim; `kind` should work identically, so both columns get
the same treatment and the same guard.

Two coupled edits:

1. **Amend `V30__llm_charge_ledger.sql`** so the kind CHECK lists enum names verbatim, matching how
   `token_type` already works:

   ```sql
   CHECK (kind IN ('REVIEW', 'RECONCILE', 'FOLLOWUP')),
   ```

   Editing the migration in place is correct — it has not run anywhere persistent. If your local dev
   Postgres reports a Flyway checksum mismatch, say so in the report rather than working around it.

2. **Add the mirrored drift guard** to `LlmChargeSchemaIT`, alongside the `TokenType` one Task 2
   activated:

   ```java
   /**
    * The ledger's kind CHECK must accept every ChargeKind the writer can produce. Without this, adding
    * a call kind without amending the migration turns that kind's first charge into a lost INSERT.
    */
   @Test
   void theKindCheckAcceptsEveryChargeKind() {
       for (ChargeKind kind : ChargeKind.values()) {
           assertDoesNotThrow(() -> exec(insertKind(kind.name())),
                   "llm_charge.kind CHECK rejects ChargeKind." + kind
                           + " — add it to the CHECK in V30__llm_charge_ledger.sql");
       }
   }
   ```

   Give each iteration a distinct `call_ref` (`'CANARY-KIND-' + kind.name()`); the existing tests' refs
   are all distinct and must stay that way. Verify it is load-bearing: add a constant to `ChargeKind`
   without touching the migration, confirm the test fails naming it, remove the constant.

The UI displays this value, so it lowercases for display in Task 10 — the wire and storage form is the
enum name.

`ChargeKind.java`:

```java
package dev.codespire.orchestrator.llm;

/**
 * Which paid call a charge belongs to. An enum rather than a string because the ledger's {@code kind}
 * CHECK lists these names verbatim: a typo'd literal would otherwise pass compilation and fail the
 * INSERT at runtime, dead-lettering a result whose money has already been spent.
 */
public enum ChargeKind {
    /** The review generation call. */
    REVIEW,
    /** The ADR-019 reconcile call that verdicts a prior run's findings. */
    RECONCILE,
    /** A conversation follow-up answer. */
    FOLLOWUP
}
```

`PricingMode.java`:

```java
package dev.codespire.orchestrator.llm;

/**
 * How a model's tokens are costed. Pricing is orchestrator-owned (ADR-018), so this deliberately does
 * not live in the shared contract.
 */
public enum PricingMode {
    /** Operator-entered rates apply. Every rate must be greater than zero. */
    METERED,
    /**
     * Self-hosted or otherwise unbilled inference: cost is an ASSERTED zero. Distinct from
     * {@link #UNKNOWN} on purpose — conflating the two is what let an unpriced model read as free.
     */
    UNMETERED,
    /** Pricing could not be determined. Cost is NULL, never zero. */
    UNKNOWN
}
```

`ChargeLine.java`:

```java
package dev.codespire.orchestrator.llm;

import dev.codespire.contract.review.TokenType;

/**
 * One token dimension of one LLM call, priced.
 *
 * <p>The rate is carried, not just the cost, so the figure is reproducible as
 * {@code tokens x rate / 1_000_000} and a later catalog edit cannot reach it.
 *
 * @param rateMillicentsPerMillion null exactly when {@code mode} is {@link PricingMode#UNKNOWN}
 * @param costMillicents           null exactly when {@code mode} is {@link PricingMode#UNKNOWN} —
 *                                 never 0, which would be indistinguishable from an asserted zero
 */
public record ChargeLine(TokenType tokenType, int tokens, Long rateMillicentsPerMillion,
                         Long costMillicents, PricingMode mode) {

    /** A priced line. Rounds once, at the end, per the money rule. */
    public static ChargeLine metered(TokenType type, int tokens, long rate) {
        return new ChargeLine(type, tokens, rate, (long) tokens * rate / 1_000_000L, PricingMode.METERED);
    }

    public static ChargeLine unmetered(TokenType type, int tokens) {
        return new ChargeLine(type, tokens, 0L, 0L, PricingMode.UNMETERED);
    }

    public static ChargeLine unknown(TokenType type, int tokens) {
        return new ChargeLine(type, tokens, null, null, PricingMode.UNKNOWN);
    }
}
```

`ChargeCall.java`:

```java
package dev.codespire.orchestrator.llm;

import java.util.List;

/**
 * One LLM call's charge lines plus the identity they are recorded under.
 *
 * @param callRef the deterministic key that makes recording idempotent under redelivery — see
 *                {@link CallRefs}
 * @param kind    which paid call this is; stored as the enum NAME, which the ledger's kind CHECK
 *                lists verbatim
 */
public record ChargeCall(String reviewId, String callRef, ChargeKind kind, String model,
                         List<ChargeLine> lines) {

    public ChargeCall {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
```

`CallRefs.java`:

```java
package dev.codespire.orchestrator.llm;

/**
 * The deterministic identity of one paid LLM call.
 *
 * <p>Mirrors the claim the worker already takes before spending
 * ({@code CommentIdempotencyStore.claim(reviewId, slot, key)}), rather than plumbing a new field
 * through the wire: the orchestrator can rebuild the same key from facts every delivery of the event
 * carries, so a redelivered result resolves to the same {@code call_ref} and the ledger's
 * {@code UNIQUE (call_ref, token_type)} makes the second recording a no-op.
 *
 * <p>The slot is the COMMIT for a review or reconcile call, and the THREAD REF for a follow-up —
 * matching what the worker puts in that position.
 */
public final class CallRefs {

    private CallRefs() {
    }

    public static String of(String reviewId, String slot, ChargeKind kind) {
        // A blank slot would yield a well-formed-LOOKING ref ("review::x/y#1||REVIEW") that silently
        // breaks the UNIQUE (call_ref, token_type) identity this method exists to provide — two different
        // calls would collide and the second would be discarded as a redelivery of the first. Fail loudly
        // instead: every caller has a real commit or thread ref, so a blank one is a bug upstream.
        if (reviewId == null || reviewId.isBlank() || slot == null || slot.isBlank()) {
            throw new IllegalArgumentException(
                    "A charge needs a reviewId and a slot (the commit, or the thread ref for a follow-up); "
                            + "got reviewId='" + reviewId + "', slot='" + slot + "'");
        }
        return reviewId + '|' + slot + '|' + kind.name();
    }
}
```

- [ ] **Step 4: Reshape the model DTOs**

`LlmModelInput.java`:

```java
package dev.codespire.orchestrator.llm;

import java.util.Map;

/**
 * Create/update payload for a catalog model.
 *
 * <p>{@code pricingMode} is "METERED" or "UNMETERED". Under METERED, {@code rates} maps a
 * {@code TokenType} name to millicents per 1,000,000 tokens and must contain a rate greater than zero
 * for at least INPUT and OUTPUT — the two dimensions every vendor reports on every call. The optional
 * dimensions (CACHED_INPUT, CACHE_WRITE, REASONING) may be omitted, because a model that does not bill
 * for them cannot be asked to price them.
 *
 * <p>Under UNMETERED, {@code rates} must be empty: the cost is an asserted zero.
 */
public record LlmModelInput(
        String type,
        String name,
        String label,
        String pricingMode,
        Map<String, Long> rates,
        String outputTokenParam,
        Boolean supportsTemperature,
        String reasoningEffort,
        Map<String, Object> extraParams,
        Boolean enabled) {
}
```

`LlmModelView.java` — same change: replace `inputPriceMillicentsPerMillion` / `outputPriceMillicentsPerMillion` with `String pricingMode` and `Map<String, Long> rates`, keeping every other component and its order.

- [ ] **Step 5: Implement rates, validation and pricing in the registry**

In `LlmModelRegistry`:

1. **Delete the coercions** at the old lines 78, 79, 106, 107 and drop both price columns from the `INSERT`/`UPDATE`; add `pricing_mode`.
2. **Validate before writing**, in a private helper called by both `create` and `update`:

```java
    /** Mandatory because every vendor reports these two on every call; the rest are model-specific. */
    private static final List<TokenType> REQUIRED_RATES = List.of(TokenType.INPUT, TokenType.OUTPUT);

    private static PricingMode validatedMode(LlmModelInput in) {
        PricingMode mode = parseMode(in.pricingMode());
        Map<String, Long> rates = in.rates() == null ? Map.of() : in.rates();
        if (mode == PricingMode.UNMETERED) {
            if (!rates.isEmpty()) {
                throw new IllegalArgumentException(
                        "An UNMETERED model asserts a zero cost, so it must carry no rates");
            }
            return mode;
        }
        for (TokenType required : REQUIRED_RATES) {
            Long rate = rates.get(required.name());
            if (rate == null || rate <= 0) {
                throw new IllegalArgumentException("A METERED model needs a rate above zero for "
                        + required.name() + ". If this model is self-hosted and costs nothing to call,"
                        + " set its pricing mode to UNMETERED instead of entering a zero — a zero rate"
                        + " and an unentered rate must stay distinguishable.");
            }
        }
        rates.forEach((type, rate) -> {
            if (rate == null || rate <= 0) {
                throw new IllegalArgumentException("Rate for " + type + " must be above zero");
            }
        });
        return mode;
    }

    /** UNKNOWN is a runtime outcome, never an operator's choice, so it is not accepted here. */
    private static PricingMode parseMode(String raw) {
        PricingMode mode = raw == null ? null : switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "METERED" -> PricingMode.METERED;
            case "UNMETERED" -> PricingMode.UNMETERED;
            default -> null;
        };
        if (mode == null) {
            throw new IllegalArgumentException("pricingMode must be METERED or UNMETERED");
        }
        return mode;
    }
```

3. **Replace rates transactionally** on create/update — `DELETE FROM llm_model_rate WHERE model_id = ?` then insert each entry.
4. **Read them back** in `toView` via `ratesFor(connection, id)`.
5. **Replace `costMillicents(String, int, int)` with `priceCall`:**

```java
    /**
     * Price one call's token partition into charge lines.
     *
     * <p>Never returns a zero cost for a price it could not find. The method this replaced answered
     * {@code 0L} for an uncatalogued model, a blank model name AND a SQLException, so a momentary
     * database fault wrote a permanent "this call was free".
     */
    public List<ChargeLine> priceCall(String model, ModelUsage usage) {
        List<TokenCount> counts = usage == null ? List.of() : usage.counts();
        if (counts.isEmpty()) {
            return List.of(ChargeLine.unknown(TokenType.TOTAL, 0));
        }
        Pricing pricing = pricingFor(model);
        // The catalog is consulted BEFORE the reconciled check, because an UNMETERED model's cost is an
        // asserted zero whatever the split turns out to be — there is nothing a missing breakdown could
        // change about it. Checking reconciliation first would record a self-hosted deployment's calls as
        // unpriced, which is both untrue and enough to make the attention panel flag genuinely free work.
        if (pricing.mode() == PricingMode.UNMETERED) {
            return usage.reconciled()
                    ? counts.stream().map(count -> line(pricing, count)).toList()
                    : List.of(ChargeLine.unmetered(TokenType.TOTAL, usage.reportedTotal()));
        }
        // For a metered or unknown model an unreconciled call has no split, so no per-type rate applies.
        if (!usage.reconciled()) {
            return List.of(ChargeLine.unknown(TokenType.TOTAL, usage.reportedTotal()));
        }
        return counts.stream().map(count -> line(pricing, count)).toList();
    }

    private static ChargeLine line(Pricing pricing, TokenCount count) {
        if (pricing.mode() == PricingMode.UNMETERED) {
            return ChargeLine.unmetered(count.type(), count.tokens());
        }
        Long rate = pricing.rates().get(count.type());
        if (pricing.mode() == PricingMode.UNKNOWN || rate == null) {
            return ChargeLine.unknown(count.type(), count.tokens());
        }
        return ChargeLine.metered(count.type(), count.tokens(), rate);
    }

    /** What the catalog says about a model's pricing; UNKNOWN with no rates when it cannot be read. */
    private record Pricing(PricingMode mode, Map<TokenType, Long> rates) {
        static final Pricing UNKNOWN = new Pricing(PricingMode.UNKNOWN, Map.of());
    }

    private Pricing pricingFor(String model) {
        if (model == null || model.isBlank()) {
            return Pricing.UNKNOWN;
        }
        try (Connection c = dataSource.getConnection()) {
            // ... SELECT id, pricing_mode FROM llm_model WHERE name = ?; then ratesFor(c, id).
            // Not found -> Pricing.UNKNOWN.
        } catch (SQLException e) {
            // Deliberately NOT a zero. A transient fault must not become permanent silent corruption.
            LOG.errorf(e, "Pricing lookup failed for model %s — recording the call as unpriced", model);
            return Pricing.UNKNOWN;
        }
    }

    /** Whether a review may be started against this model: priceable, or explicitly unbilled. */
    public boolean isPriceable(String model) {
        Pricing pricing = pricingFor(model);
        if (pricing.mode() == PricingMode.UNMETERED) {
            return true;
        }
        return pricing.mode() == PricingMode.METERED
                && REQUIRED_RATES.stream().allMatch(pricing.rates()::containsKey);
    }
```

6. **Guard `delete`** against a referencing provider:

```java
    @Transactional
    public boolean delete(UUID id) {
        try (Connection c = dataSource.getConnection()) {
            String name = nameOf(c, id);
            if (name == null) {
                return false;
            }
            // Without this, the save-time guard that a provider's model must be catalogued is
            // defeated after the fact: deleting the entry leaves the provider pointing at nothing
            // and every call it makes unpriceable.
            int users = countProvidersUsing(c, name);
            if (users > 0) {
                throw new IllegalStateException("Model '" + name + "' is in use by " + users
                        + " LLM provider(s). Point them at another model first.");
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM llm_model WHERE id = ?")) {
                ps.setObject(1, id);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete LLM model " + id, e);
        }
    }
```

`countProvidersUsing` is:

```sql
SELECT count(*) FROM llm_provider WHERE model = ?
```

**Verified, not assumed:** `V8__llm_provider.sql:14` declares `model VARCHAR(255) NOT NULL`, and
`LlmProviderRegistry` reads and writes it as a plain string (`:88`, `:116`, `:220`). No later migration
alters it. Match on the model NAME, which is what a provider stores — never on the catalog row's UUID.

**Class-size warning, measured:** `LlmModelRegistry.java` is currently **256 lines** and this task adds
rate CRUD, pricing-mode validation, `priceCall`, `isPriceable` and the delete guard. It will cross the
project's 300-line limit. Plan for that from the start rather than discovering it at the end: extract the
rate table into `LlmModelRateRepository` (`ratesFor(Connection, UUID)`, `replaceRates(UUID, Map<TokenType,
Long>)`) and have the registry delegate. Splitting once, deliberately, beats a reviewer asking you to
split a 380-line class afterwards.

- [ ] **Step 6: Run the test**

Run: `./gradlew :spire-orchestrator:test --tests 'dev.codespire.orchestrator.llm.LlmModelRegistryPricingTest'`
Expected: PASS.

- [ ] **Step 7: Mutation-verify the two guards that matter most**

- Change `line(...)` so a missing rate returns `ChargeLine.metered(count.type(), count.tokens(), 0L)`. Expect `aDimensionWithNoRateIsUnknownWhileTheRestOfTheCallPrices` to fail. Restore.
- Make `pricingFor`'s `catch` return `new Pricing(PricingMode.UNMETERED, Map.of())`. Expect `anUncataloguedModelIsUnknownAndNeverZero` to still pass (it does not hit the catch) — which shows that path is **not** covered. Add a test that injects a failure, or accept the gap and note it. Prefer adding coverage: point the registry at a closed `DataSource` in a focused unit test and assert `UNKNOWN`.

- [ ] **Step 8: Commit**

```bash
git add spire-orchestrator/src/main/java/dev/codespire/orchestrator/llm spire-orchestrator/src/test
git commit -m "Price calls into charge lines instead of one coerced total

priceCall returns a line per token dimension carrying the rate that priced
it. The method it replaces answered 0 for an uncatalogued model, a blank
model name and a SQLException alike, so a momentary database fault wrote a
permanent \"this call was free\".

A METERED model now needs a rate above zero for INPUT and OUTPUT, and its
error says to use UNMETERED for genuinely free inference rather than
entering a zero. Optional dimensions stay optional: a model that does not
bill for cache writes cannot be asked to price them, so an unrated
dimension makes its own line UNKNOWN and leaves the rest of the call
priced.

Deleting a catalogued model a provider still references is refused —
otherwise the save-time guard is defeated the moment the entry goes away."
```

---

## Task 5: REST validation for the model catalog

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/llm/LlmModelResource.java:70-95`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/llm/LlmModelResourceTest.java` (exists — extend)

**Interfaces:**
- Consumes: `LlmModelInput` with `pricingMode`/`rates` (Task 4), `PricingMode`.
- Produces: 400 with an actionable message for every invalid pricing combination; 409 when deleting a referenced model.

- [ ] **Step 1: Write the failing tests**

Add to `LlmModelResourceTest`:

```java
    /**
     * The gap that let hole #1 through. requireNonNegative rejected null and ACCEPTED zero, while the
     * UI turned a blank field into zero, so "no price entered" arrived as a valid free model. Zero is
     * now only legal as a deliberate UNMETERED assertion.
     */
    @Test
    void aZeroRateOnAMeteredModelIsRejectedWithAnActionableMessage() {
        given().contentType(ContentType.JSON)
                .body("""
                      {"type":"openai","name":"TEST-ZERO","label":"TEST zero","pricingMode":"METERED",
                       "rates":{"INPUT":0,"OUTPUT":400000}}
                      """)
                .when().post("/api/llm-models")
                .then().statusCode(400)
                .body(containsString("UNMETERED"));
    }

    @Test
    void anUnmeteredModelCarryingRatesIsRejected() {
        given().contentType(ContentType.JSON)
                .body("""
                      {"type":"openai","name":"TEST-CONFUSED","label":"TEST","pricingMode":"UNMETERED",
                       "rates":{"INPUT":200000}}
                      """)
                .when().post("/api/llm-models")
                .then().statusCode(400);
    }

    @Test
    void anUnknownPricingModeIsRejectedSoUnknownCannotBeChosen() {
        given().contentType(ContentType.JSON)
                .body("""
                      {"type":"openai","name":"TEST-UNKNOWN","label":"TEST","pricingMode":"UNKNOWN",
                       "rates":{}}
                      """)
                .when().post("/api/llm-models")
                .then().statusCode(400);
    }

    @Test
    void aMeteredModelMissingTheOutputRateIsRejected() {
        given().contentType(ContentType.JSON)
                .body("""
                      {"type":"openai","name":"TEST-PARTIAL","label":"TEST","pricingMode":"METERED",
                       "rates":{"INPUT":200000}}
                      """)
                .when().post("/api/llm-models")
                .then().statusCode(400).body(containsString("OUTPUT"));
    }

    @Test
    void aMeteredModelWithBothMandatoryRatesIsCreated() {
        given().contentType(ContentType.JSON)
                .body("""
                      {"type":"openai","name":"TEST-GOOD","label":"TEST good","pricingMode":"METERED",
                       "rates":{"INPUT":200000,"OUTPUT":400000}}
                      """)
                .when().post("/api/llm-models")
                .then().statusCode(201).body("rates.INPUT", equalTo(200000));
    }
```

- [ ] **Step 2: Run to confirm they fail**

Run: `./gradlew :spire-orchestrator:test --tests 'dev.codespire.orchestrator.llm.LlmModelResourceTest'`
Expected: FAIL — the new fields are unknown and no rate validation exists.

- [ ] **Step 3: Replace `validate`**

```java
    private void validate(LlmModelInput in) {
        if (in == null) {
            throw new BadRequestException("LLM model body is required");
        }
        requireField(in.type(), "type");
        requireField(in.name(), "name");
        requireField(in.label(), "label");
        requireField(in.pricingMode(), "pricingMode");
        if (!TYPES.contains(in.type())) {
            throw new BadRequestException("Unsupported model type '" + in.type()
                    + "' (expected one of: " + String.join(", ", TYPES.stream().sorted().toList()) + ")");
        }
        // Pricing validity lives in the registry, which owns the METERED/UNMETERED rules and the
        // mandatory-dimension list. Surfaced as 400 rather than 500 because it is the caller's input.
        try {
            registry.validatePricing(in);
        } catch (IllegalArgumentException invalid) {
            throw new BadRequestException(invalid.getMessage());
        }
    }
```

Expose `validatePricing(LlmModelInput)` on the registry (the `validatedMode` helper from Task 4, made package-visible or public and returning `void`), so the rule has one home and the resource does not restate it. Delete `requireNonNegative` — nothing calls it now.

Map the delete guard to 409:

```java
    @DELETE
    @RolesAllowed("spire-admin")
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        try {
            if (!registry.delete(uuid(id))) {
                throw new NotFoundException("No LLM model " + id);
            }
        } catch (IllegalStateException inUse) {
            throw new ClientErrorException(inUse.getMessage(), Response.Status.CONFLICT);
        }
        return Response.noContent().build();
    }
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :spire-orchestrator:test --tests 'dev.codespire.orchestrator.llm.LlmModelResourceTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add spire-orchestrator/src/main/java/dev/codespire/orchestrator/llm/LlmModelResource.java spire-orchestrator/src/test
git commit -m "Reject a zero rate on a metered model at the API

requireNonNegative rejected null and accepted zero, while the UI turned a
blank price field into zero — so \"no price entered\" arrived as a valid
free model and froze that way. Zero is now legal only as a deliberate
UNMETERED assertion, and the error says so rather than just refusing.

Validation delegates to the registry so the METERED/UNMETERED rules have
one home instead of being restated at the edge, and deleting a model a
provider still uses answers 409 rather than orphaning it."
```

---

## Task 6: A provider may only name a catalogued model

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/llm/LlmProviderResource.java:153`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/llm/LlmProviderModelGuardTest.java`

**Interfaces:**
- Consumes: `LlmModelRegistry.isPriceable(String)` (Task 4).

- [ ] **Step 1: Write the failing test**

```java
package dev.codespire.orchestrator.llm;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * The Settings dropdown is a courtesy; this is the control. A provider naming a model that is not in
 * the catalog cannot be priced, so every call it makes would land in the ledger as UNKNOWN — the exact
 * state the accounting rework exists to make impossible to configure.
 */
@QuarkusTest
class LlmProviderModelGuardTest {

    @Test
    void aProviderNamingAnUncataloguedModelIsRejected() {
        given().contentType(ContentType.JSON)
                .body("""
                      {"name":"TEST provider","type":"openai","baseUrl":"https://api.example.invalid",
                       "apiKey":"TEST-KEY","model":"TEST-NOT-IN-CATALOG"}
                      """)
                .when().post("/api/llm-providers")
                .then().statusCode(400).body(containsString("catalog"));
    }

    @Test
    void aProviderNamingACataloguedPriceableModelIsAccepted() {
        given().contentType(ContentType.JSON)
                .body("""
                      {"type":"openai","name":"TEST-GUARD-MODEL","label":"TEST guard",
                       "pricingMode":"METERED","rates":{"INPUT":200000,"OUTPUT":400000}}
                      """)
                .when().post("/api/llm-models").then().statusCode(201);

        given().contentType(ContentType.JSON)
                .body("""
                      {"name":"TEST provider ok","type":"openai","baseUrl":"https://api.example.invalid",
                       "apiKey":"TEST-KEY","model":"TEST-GUARD-MODEL"}
                      """)
                .when().post("/api/llm-providers")
                .then().statusCode(201);
    }
}
```

- [ ] **Step 2: Run to confirm it fails**

Run: `./gradlew :spire-orchestrator:test --tests 'dev.codespire.orchestrator.llm.LlmProviderModelGuardTest'`
Expected: FAIL — the uncatalogued model is accepted with 201.

- [ ] **Step 3: Add the guard**

In `LlmProviderResource`, next to the existing `requireField(in.model(), "model")` at line 153:

```java
        requireField(in.model(), "model");
        // A model outside the catalog has no rates, so every call this provider makes would be
        // recorded unpriced. Refuse the configuration rather than discover it per review.
        if (!models.isPriceable(in.model())) {
            throw new BadRequestException("Model '" + in.model() + "' is not in the catalog with usable"
                    + " pricing. Add it under Settings -> LLM -> Models first, with a rate for input and"
                    + " output tokens — or mark it UNMETERED if it is self-hosted and costs nothing.");
        }
```

Inject `LlmModelRegistry models;` if the resource does not already hold it.

- [ ] **Step 4: Run the test and the module**

Run: `./gradlew :spire-orchestrator:test --tests 'dev.codespire.orchestrator.llm.*'`
Expected: PASS.

- [ ] **Step 5: Mutation-verify**

Invert the guard to `if (false)`. Expect exactly `aProviderNamingAnUncataloguedModelIsRejected` to fail. Restore.

- [ ] **Step 6: Commit**

```bash
git add spire-orchestrator/src/main/java/dev/codespire/orchestrator/llm/LlmProviderResource.java spire-orchestrator/src/test
git commit -m "Refuse an LLM provider whose model is not priceable

The model field was validated only for being non-blank, so any string was
accepted and the Settings dropdown was the sole thing keeping providers
pointed at catalogued models — a courtesy, not a control. A provider naming
an uncatalogued model cannot be priced, so every call it made would land in
the ledger unpriced.

The message names the fix, including the UNMETERED route for self-hosted
inference."
```

---

## Task 7: Record charge lines in the read model

**Helpers already in `ReviewProjection` that this task builds on — verified present, do not re-create:**
`update(String sql, Binder binder)` at `:1549` (the `Binder` functional interface takes the
`PreparedStatement`, so a `ps -> { ... }` lambda is correct) and `broadcast(String reviewId)` at `:1077`.
`setNullableLong` does **not** exist and is added by this task, as shown below.

**This task adds the WRITER only.** Task 2 already removed every read of the dropped columns and
re-derived `model`, `llm_type`, `total_cost_millicents` and `unpriced_calls` in `listSummaries`, and
already replaced `llmCalls` with `chargeLines` and `ReviewDetail.LlmCall` with `ChargeLineView`. Do not
redo any of that — verify it is in place, then add `recordCharges` and `costOf`.

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewProjection.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/readmodel/LlmChargeProjectionIT.java`

**Interfaces:**
- Consumes: `ChargeCall`, `ChargeLine`, `PricingMode` (Task 4); `ReviewDetail.ChargeLineView` and the
  ledger-derived `listSummaries` query (Task 2).
- Produces:
  - `ReviewProjection.recordCharges(ChargeCall call)` — idempotent
  - `ReviewProjection.costOf(String reviewId) -> CostSummary`
  - `record CostSummary(long knownCostMillicents, int unpricedCalls, String lastModel)`

- [ ] **Step 1: Write the failing test**

```java
package dev.codespire.orchestrator.readmodel;

import dev.codespire.contract.review.TokenType;
import dev.codespire.orchestrator.llm.ChargeCall;
import dev.codespire.orchestrator.llm.ChargeLine;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Two properties of the ledger writer, both of which fail silently if broken: recording is idempotent
 * under redelivery, and an unpriced line is EXCLUDED from the known total rather than added as zero.
 * A zero-summing total looks complete and is not.
 */
@QuarkusTest
class LlmChargeProjectionIT {

    private static final String REVIEW = "review::TEST-WS/TEST-REPO#1";

    @Inject
    ReviewProjection projection;

    private ChargeCall call(String ref, List<ChargeLine> lines) {
        return new ChargeCall(REVIEW, ref, ChargeKind.REVIEW, "TEST-MODEL", lines);
    }

    @Test
    void recordingTheSameCallTwiceChargesItOnce() {
        ChargeCall once = call("CANARY-REF-1",
                List.of(ChargeLine.metered(TokenType.INPUT, 1_000_000, 200_000L)));

        projection.recordCharges(once);
        projection.recordCharges(once);

        assertEquals(200_000L, projection.costOf(REVIEW).knownCostMillicents());
        assertEquals(1, projection.chargeLines(REVIEW).size());
    }

    @Test
    void anUnpricedLineIsCountedAsUnpricedNotAsZeroCost() {
        projection.recordCharges(call("CANARY-REF-2", List.of(
                ChargeLine.metered(TokenType.INPUT, 1_000_000, 200_000L),
                ChargeLine.unknown(TokenType.CACHE_WRITE, 500_000))));

        ReviewProjection.CostSummary cost = projection.costOf(REVIEW);
        assertEquals(200_000L, cost.knownCostMillicents());
        assertEquals(1, cost.unpricedCalls());
    }

    @Test
    void anUnmeteredCallContributesAnExplicitZeroAndIsNotFlaggedUnpriced() {
        projection.recordCharges(call("CANARY-REF-3",
                List.of(ChargeLine.unmetered(TokenType.INPUT, 1_000_000))));

        ReviewProjection.CostSummary cost = projection.costOf(REVIEW);
        assertEquals(0L, cost.knownCostMillicents());
        assertEquals(0, cost.unpricedCalls());
    }
}
```

- [ ] **Step 2: Run to confirm it fails**

Run: `./gradlew :spire-orchestrator:test --tests 'dev.codespire.orchestrator.readmodel.LlmChargeProjectionIT'`
Expected: FAIL — `recordCharges` does not exist.

- [ ] **Step 3: Implement the writer and the reads**

```java
    /**
     * Append one LLM call's charge lines. Idempotent: {@code ON CONFLICT DO NOTHING} against the
     * ledger's {@code UNIQUE (call_ref, token_type)}, so a redelivered result event cannot charge the
     * same call twice.
     */
    public void recordCharges(ChargeCall call) {
        for (ChargeLine line : call.lines()) {
            update("""
                    INSERT INTO llm_charge (id, review_id, call_ref, kind, model, pricing_mode,
                            token_type, tokens, rate_millicents_per_million, cost_millicents)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (call_ref, token_type) DO NOTHING
                    """, ps -> {
                ps.setObject(1, java.util.UUID.randomUUID());
                ps.setString(2, call.reviewId());
                ps.setString(3, call.callRef());
                ps.setString(4, call.kind().name());
                ps.setString(5, call.model());
                ps.setString(6, line.mode().name());
                ps.setString(7, line.tokenType().name());
                ps.setInt(8, line.tokens());
                setNullableLong(ps, 9, line.rateMillicentsPerMillion());
                setNullableLong(ps, 10, line.costMillicents());
            });
        }
        broadcast(call.reviewId());
    }

    /**
     * Bind a nullable money column. NULL must stay NULL: a rate or cost written as 0 because the value
     * was absent is the exact conflation this ledger exists to remove, and {@code setLong} on an
     * unboxed null would either throw or silently write zero depending on the call site.
     */
    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    /**
     * A review's cost.
     *
     * @param knownCostMillicents the sum of lines that COULD be priced. Deliberately not the whole
     *                            picture on its own — see {@code unpricedCalls}.
     * @param unpricedCalls       how many distinct calls have at least one unpriced line, so the UI can
     *                            say the total is partial instead of presenting it as complete
     * @param lastModel           the model on the most recent charge line, for the badge that used to
     *                            read review_status.model
     */
    public record CostSummary(long knownCostMillicents, int unpricedCalls, String lastModel) {
    }
```

`costOf` is one query:

```sql
SELECT COALESCE(SUM(cost_millicents), 0)                                        AS known_cost,
       COUNT(DISTINCT CASE WHEN pricing_mode = 'UNKNOWN' THEN call_ref END)     AS unpriced_calls,
       (SELECT model FROM llm_charge WHERE review_id = ? ORDER BY priced_at DESC LIMIT 1) AS last_model
  FROM llm_charge WHERE review_id = ?
```

- [ ] **Step 4: Confirm Task 2's read side is intact**

Task 2 already did this work; verify rather than repeat it. Read the file and confirm all four hold —
if any is missing, it is a Task 2 regression and belongs in this task's report as a concern:

- `recordOutcome` writes only `findings_count`, `findings_json`, `stage`, `updated_at`.
- `listSummaries` derives `model`, `llm_type`, `total_cost_millicents` and `unpriced_calls` from
  `llm_charge`, and `ReviewSummary` carries `int unpricedCalls`.
- `chargeLines(String)` exists and reads `rate_millicents_per_million` / `cost_millicents` as nullable
  `Long`, not via `getLong` (which would turn "unpriced" back into `0`).
- `withReviewCall` is gone and `ReviewRow` no longer carries `model` / `tokensIn` / `tokensOut` /
  `costMillicents`.

- [ ] **Step 5: Run the module tests**

Run: `./gradlew :spire-orchestrator:test`
Expected: PASS. Update `ReviewProjectionTest` / `ReviewProjectionPriorRunIT` assertions that referenced the dropped columns.

- [ ] **Step 6: Mutation-verify idempotency**

Remove `ON CONFLICT (call_ref, token_type) DO NOTHING`. Expect `recordingTheSameCallTwiceChargesItOnce` to fail — on the SQL constraint, which is the point: the schema is the backstop and the clause is the graceful handling. Restore.

- [ ] **Step 7: Commit**

```bash
git add spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel spire-orchestrator/src/test
git commit -m "Record charge lines and derive cost from the ledger

recordCharges appends per-type lines with ON CONFLICT DO NOTHING, so a
redelivered result cannot charge one call twice.

costOf returns the priced sum AND how many calls carry an unpriced line,
because a sum that silently omits what it could not price looks complete and
is not. The reviews list carries the same count for the same reason.

review_status's cost columns are gone, so the model badge and the list total
now derive from the ledger — one source instead of a rollup that only ever
held the last run's review call."
```

---

## Task 8: Price in the saga, and refuse to spend what cannot be priced

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/ResultSaga.java:136-250`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/llm/WorkerLlmCredentials.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/pipeline/ResultSagaPricingTest.java`

**Interfaces:**
- Consumes: `priceCall`, `isPriceable`, `CallRefs.of`, `recordCharges`.
- Produces: `WorkerLlmCredentials.defaultModelName() -> Optional<String>`.

**Why the guard is here.** Pricing is post-hoc: `ResultSaga` prices when the result event returns, by which point the money is spent. Failing there would waste the spend *and* lose the review. The pre-spend point is where `GenerateReview` is emitted, which already has exactly this shape for a missing LLM credential at `ResultSaga.java:143-150` — copy that idiom.

**You may need to re-add an injection Task 2 removed.** Task 2 deleted `ResultSaga`'s pricing calls and
was told to drop its `LlmModelRegistry llmModels` field if nothing else used it. This task needs it again,
for both `isPriceable` and `priceCall`. If the field is gone, put it back — that is expected churn from
sequencing the removal before the replacement, not a mistake by either task.

- [ ] **Step 1: Write the failing test**

```java
package dev.codespire.orchestrator.pipeline;

// imports as in ResultSagaRetryTest, which already fakes the projection and the emitter

/**
 * Two behaviours, at the two points where a pricing decision is still possible.
 *
 * <p>Before the spend: an unpriceable model must not produce a GenerateReview at all. After it:
 * whatever the call cost must be recorded as charge lines keyed so redelivery is a no-op.
 */
class ResultSagaPricingTest {

    @Test
    void contextAssembledDoesNotGenerateAReviewWhenTheModelCannotBePriced() {
        // default provider names TEST-UNPRICEABLE; registry.isPriceable -> false
        saga.on(contextAssembled("review::TEST-WS/TEST-REPO#1", "TESTSHA00000"));

        assertTrue(emitted.isEmpty(), "no paid command may be emitted for an unpriceable model");
        assertTrue(projection.note().contains("pricing"),
                "the dashboard must say WHY nothing ran, not leave a silent stall");
    }

    @Test
    void contextAssembledGeneratesAReviewWhenTheModelIsPriceable() {
        // registry.isPriceable -> true
        saga.on(contextAssembled("review::TEST-WS/TEST-REPO#1", "TESTSHA00000"));

        assertEquals(1, emitted.size());
        assertInstanceOf(ActionCommand.GenerateReview.class, emitted.get(0));
    }

    @Test
    void reviewGeneratedRecordsChargeLinesUnderADeterministicCallRef() {
        saga.on(reviewGenerated("review::TEST-WS/TEST-REPO#1", "TESTSHA00000",
                ModelUsage.of("TEST-MODEL", 1_000_000, 500_000)));

        assertEquals(1, projection.recordedCalls().size());
        assertEquals("review::TEST-WS/TEST-REPO#1|TESTSHA00000|REVIEW",
                projection.recordedCalls().get(0).callRef());
    }

    /** The reconcile call is its own charge, under its own ref, so it cannot collide with the review. */
    @Test
    void aReconcileCallIsChargedSeparatelyFromTheReviewCall() {
        saga.on(reviewGeneratedWithReconcile("review::TEST-WS/TEST-REPO#1", "TESTSHA00000"));

        assertEquals(2, projection.recordedCalls().size());
        assertTrue(projection.recordedCalls().stream()
                .anyMatch(c -> c.callRef().endsWith("|RECONCILE")));
    }

    /** A follow-up is keyed to its thread, matching the slot the worker claims under. */
    @Test
    void aFollowUpIsChargedUnderItsThreadRef() {
        saga.on(followUpGenerated("review::TEST-WS/TEST-REPO#1", "TEST-THREAD-1"));

        assertEquals("review::TEST-WS/TEST-REPO#1|TEST-THREAD-1|FOLLOWUP",
                projection.recordedCalls().get(0).callRef());
    }
}
```

Follow `ResultSagaRetryTest`'s existing fake-projection pattern; extend that fake with `recordCharges` capture and a `note()` accessor rather than inventing a new harness.

- [ ] **Step 2: Run to confirm it fails**

Run: `./gradlew :spire-orchestrator:test --tests 'dev.codespire.orchestrator.pipeline.ResultSagaPricingTest'`
Expected: FAIL — the guard and the recording do not exist.

- [ ] **Step 3: Add `defaultModelName()`**

In `WorkerLlmCredentials`:

```java
    /** The default provider's model name, for the pre-spend priceability check. */
    public Optional<String> defaultModelName() {
        return registry.resolveDefault().map(LlmProviderConfig::model);
    }
```

- [ ] **Step 4: Add the pre-spend guard**

In `ResultSaga`'s `ContextAssembled` branch, after the existing `llmCred.isEmpty()` check:

```java
                // Pre-spend guard. Pricing happens when the result comes back, so this is the last
                // point at which an unpriceable call can be prevented rather than merely reported.
                String model = workerLlmCredentials.defaultModelName().orElse("");
                if (!llmModels.isPriceable(model)) {
                    timeline.record("result", "skipped:GenerateReview", e.reviewId(),
                            "model '" + model + "' has no usable pricing");
                    projection.setNote(e.reviewId(), "Model '" + model + "' has no usable pricing — set"
                            + " input and output rates in Settings → LLM → Models, or mark it UNMETERED"
                            + " if it is self-hosted.");
                    LOG.warnf("Skipping GenerateReview for %s — model '%s' is not priceable",
                            e.reviewId(), model);
                    return;
                }
```

- [ ] **Step 5: Record the charges**

In the `ReviewGenerated` branch, replacing what Task 1 stripped out:

```java
                projection.recordOutcome(e.reviewId(), e.result(), ReviewProjection.STAGE_COMMENTS);
                charge(new ChargeRequest(e.reviewId(), e.commit(), ChargeKind.REVIEW), e.result().usage());
                if (e.reconcileUsage() != null) {
                    charge(new ChargeRequest(e.reviewId(), e.commit(), ChargeKind.RECONCILE),
                            e.reconcileUsage());
                }
```

and in the `FollowUpGenerated` branch, replacing the old `recordLlmCall`:

```java
                if (e.usage() != null) {
                    charge(new ChargeRequest(e.reviewId(), e.threadRef().value(), ChargeKind.FOLLOWUP),
                            e.usage());
                }
```

and one helper. The identity of a call is three facts, so they travel as a record rather than as
parameters — `(reviewId, slot, kind, usage)` would be four, over this project's limit:

```java
    /**
     * Which paid call this is. {@code slot} is the commit for a review or reconcile call and the thread
     * ref for a follow-up — the same position the worker claims its idempotency under, so the ref
     * derived from it is stable across redelivery.
     */
    private record ChargeRequest(String reviewId, String slot, ChargeKind kind) {
    }

    /** Price a call and append its lines. */
    private void charge(ChargeRequest request, ModelUsage usage) {
        List<ChargeLine> lines = llmModels.priceCall(usage.model(), usage);
        projection.recordCharges(new ChargeCall(request.reviewId(),
                CallRefs.of(request.reviewId(), request.slot(), request.kind()),
                request.kind(), usage.model(), lines));
    }
```

- [ ] **Step 6: Run the tests**

Run: `./gradlew :spire-orchestrator:test`
Expected: PASS.

- [ ] **Step 7: Mutation-verify the pre-spend guard**

Invert it to `if (false)`. Expect exactly `contextAssembledDoesNotGenerateAReviewWhenTheModelCannotBePriced` to fail. Restore.

- [ ] **Step 8: Commit**

```bash
git add spire-orchestrator/src/main/java spire-orchestrator/src/test
git commit -m "Guard the spend before it happens, then charge the ledger

Pricing is post-hoc: the saga prices when the result returns, by which point
the money is gone, so failing there would waste the spend and lose the
review too. The pre-spend point is where GenerateReview is emitted, and it
already had this exact shape for a missing credential — an unpriceable model
now skips the same way, with the reason on the dashboard.

Charge lines are recorded under a ref derived from the same facts the worker
claims its idempotency under, so a redelivered result resolves to the same
key and the ledger's uniqueness makes the second write a no-op. Reconcile
and follow-up calls get their own refs so they cannot collide."
```

---

## Task 9: Surface unpriced and unreconciled calls on the attention panel

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/attention/AttentionQueries.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/attention/AttentionQueriesCostTest.java`

**Interfaces:**
- Produces: `AttentionView` rows with codes `LLM_COST_UNPRICED` and `LLM_USAGE_UNRECONCILED`, both `WARNING`, both non-dismissable.

Both are conditions true right now that clear when the cause is fixed — pricing entered, or a mapping added — so neither is dismissable, matching the panel's rule. Codes carry no vendor name (ADR-020).

- [ ] **Step 1: Write the failing test**

```java
package dev.codespire.orchestrator.attention;

import dev.codespire.contract.attention.AttentionView;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An unpriced call is invisible in a money total by construction — it contributes nothing. Without a
 * row saying so, "$0.00" and "we could not price 40 calls" look identical on the dashboard.
 */
@QuarkusTest
class AttentionQueriesCostTest {

    @Inject
    AttentionQueries queries;

    @Test
    void anUnpricedChargeRaisesAWarningPointingAtTheModelSettings() {
        insertUnpricedCharge("review::TEST-WS/TEST-REPO#1", "TEST-MODEL");

        List<AttentionView> rows = queries.collect();

        assertTrue(rows.stream().anyMatch(r -> "LLM_COST_UNPRICED".equals(r.code())
                && r.severity() == AttentionView.Severity.WARNING
                && "/settings/llm".equals(r.action())
                && r.dismiss() == null));
    }

    @Test
    void anUnreconciledCallRaisesItsOwnRow() {
        insertUnreconciledCharge("review::TEST-WS/TEST-REPO#2", "TEST-MODEL");

        assertTrue(queries.collect().stream()
                .anyMatch(r -> "LLM_USAGE_UNRECONCILED".equals(r.code())));
    }

    @Test
    void aFullyPricedLedgerRaisesNeitherRow() {
        insertMeteredCharge("review::TEST-WS/TEST-REPO#3", "TEST-MODEL");

        List<AttentionView> rows = queries.collect();

        assertTrue(rows.stream().noneMatch(r -> "LLM_COST_UNPRICED".equals(r.code())));
        assertTrue(rows.stream().noneMatch(r -> "LLM_USAGE_UNRECONCILED".equals(r.code())));
    }
}
```

Write the three `insert*Charge` helpers as direct SQL against `llm_charge`, mirroring `LlmChargeSchemaIT`'s helper. Clear `llm_charge` in a `@BeforeEach` so the rows do not leak between cases.

- [ ] **Step 2: Run to confirm it fails**

Run: `./gradlew :spire-orchestrator:test --tests 'dev.codespire.orchestrator.attention.AttentionQueriesCostTest'`
Expected: FAIL — neither row is produced.

- [ ] **Step 3: Add the rows**

**Verified shape of the file you are extending:** the public entry point is `collect()` at `:50` — there
is no `rows()`. It opens one connection and calls `llmProviderRows(c, rows)`, `scmProviderRows(c, rows)`,
`reviewRows(c, rows)`, `credentialRows(c, rows)` in sequence (`:53-56`), then `deadLetterRows(rows)`
outside the connection (`:60`), then sorts. **Add `costRows(c, rows)` to that sequence** — a private
method that appends but is never called is the failure this note exists to prevent, and it would leave
every test red with a correct-looking implementation.

Follow the file's existing pattern — a private method appending to the list, called from `collect()`, with
whole-literal SQL (the file deliberately avoids concatenating identifiers, because a table name cannot be
a bind parameter and the old form could only assert its safety in a comment):

```java
    private void costRows(Connection c, List<AttentionView> rows) throws SQLException {
        int unpriced = count(c,
                "SELECT count(DISTINCT call_ref) FROM llm_charge WHERE pricing_mode = 'UNKNOWN'");
        if (unpriced > 0) {
            rows.add(new AttentionView("LLM_COST_UNPRICED", Severity.WARNING, null,
                    unpriced + " LLM call(s) could not be priced, so the reported cost is lower than"
                            + " the real spend.", "/settings/llm"));
        }
        int unreconciled = count(c,
                "SELECT count(DISTINCT call_ref) FROM llm_charge WHERE token_type = 'TOTAL'");
        if (unreconciled > 0) {
            rows.add(new AttentionView("LLM_USAGE_UNRECONCILED", Severity.WARNING, null,
                    unreconciled + " LLM call(s) reported a token breakdown that did not match their"
                            + " own total, so only the total was recorded.", "/settings/llm"));
        }
    }
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :spire-orchestrator:test --tests 'dev.codespire.orchestrator.attention.*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add spire-orchestrator/src/main/java/dev/codespire/orchestrator/attention spire-orchestrator/src/test
git commit -m "Raise attention rows for unpriced and unreconciled calls

An unpriced call contributes nothing to a money total, so without a row
saying so a genuine \$0.00 and \"we could not price 40 calls\" look
identical. Both rows are conditions that clear when the cause is fixed —
rates entered, or a vendor mapping added — so neither is dismissable."
```

---

## Task 10: UI — pricing mode, per-type rates, per-type cost

**Files:**
- Modify: `spire-ui/src/api.ts:106-111, 693-719`
- Modify: `spire-ui/src/components/SettingsLlmProviders.tsx:60-64, 137, 240, 261-262, 469, 560-680`
- Create: `spire-ui/src/components/ReviewCostCard.tsx`
- Test: `spire-ui/src/components/SettingsLlmProviders.test.ts` (extend), `spire-ui/src/components/ReviewCostCard.test.tsx` (create)

**Interfaces:**
- Consumes: the server shapes from Tasks 4 and 7.
- Produces: `LlmModelView`/`LlmModelInput` with `pricingMode: PricingMode` and `rates: Partial<Record<TokenType, number>>`; `ChargeLineView`; `ReviewCostCard` props `{ lines: ChargeLineView[]; unpricedCalls: number }`.

**The load-bearing UI change** is at `SettingsLlmProviders.tsx:602-603`, where `Number(inputPrice) || 0` turns a blank field into a zero price. That is one third of hole #1 and it must become a validation error, not a default.

- [ ] **Step 1: Write the failing tests**

Add to `SettingsLlmProviders.test.ts`:

```ts
  /**
   * One third of the accounting bug lived here: a blank price field became `Number('') || 0`, which
   * the server accepted as a valid free model. A blank field is now an error, and zero is only
   * reachable by choosing UNMETERED.
   */
  it('refuses to submit a metered model with a blank rate instead of sending zero', async () => {
    const createLlmModel = vi.spyOn(api, 'createLlmModel');
    render(<SettingsLlmProviders />);

    await userEvent.type(screen.getByLabelText(/model name/i), 'TEST-MODEL');
    await userEvent.type(screen.getByLabelText(/display label/i), 'TEST label');
    // input rate left blank
    await userEvent.click(screen.getByRole('button', { name: /add model/i }));

    expect(createLlmModel).not.toHaveBeenCalled();
    expect(screen.getByText(/rate is required/i)).toBeInTheDocument();
  });

  it('sends no rates at all when the model is marked unmetered', async () => {
    const createLlmModel = vi.spyOn(api, 'createLlmModel').mockResolvedValue(/* ... */);
    render(<SettingsLlmProviders />);

    await userEvent.type(screen.getByLabelText(/model name/i), 'TEST-SELF-HOSTED');
    await userEvent.type(screen.getByLabelText(/display label/i), 'TEST self-hosted');
    await userEvent.click(screen.getByLabelText(/self-hosted/i));
    await userEvent.click(screen.getByRole('button', { name: /add model/i }));

    expect(createLlmModel).toHaveBeenCalledWith(
      expect.objectContaining({ pricingMode: 'UNMETERED', rates: {} }),
    );
  });

  it('sends an optional rate only when it was filled in', async () => {
    const createLlmModel = vi.spyOn(api, 'createLlmModel').mockResolvedValue(/* ... */);
    render(<SettingsLlmProviders />);

    await userEvent.type(screen.getByLabelText(/model name/i), 'TEST-MODEL');
    await userEvent.type(screen.getByLabelText(/display label/i), 'TEST label');
    await userEvent.type(screen.getByLabelText(/^input rate/i), '2.50');
    await userEvent.type(screen.getByLabelText(/^output rate/i), '10');
    await userEvent.click(screen.getByRole('button', { name: /add model/i }));

    const sent = createLlmModel.mock.calls[0][0];
    expect(Object.keys(sent.rates).sort()).toEqual(['INPUT', 'OUTPUT']);
  });
```

`ReviewCostCard.test.tsx`:

```tsx
/**
 * The card's job is to make a partial total legible. A total that silently omits what could not be
 * priced reads as complete, which is the same defect as the zero it replaced — one layer up.
 */
describe('ReviewCostCard', () => {
  it('groups lines by call and shows a rate per token type', () => {
    render(<ReviewCostCard lines={[
      { kind: 'review', model: 'TEST-MODEL', tokenType: 'INPUT', tokens: 1000,
        rateMillicentsPerMillion: 250000, costMillicents: 250, pricingMode: 'METERED',
        pricedAt: '2026-08-06T00:00:00Z' },
      { kind: 'review', model: 'TEST-MODEL', tokenType: 'CACHED_INPUT', tokens: 4000,
        rateMillicentsPerMillion: 25000, costMillicents: 100, pricingMode: 'METERED',
        pricedAt: '2026-08-06T00:00:00Z' },
    ]} unpricedCalls={0} />);

    expect(screen.getByText(/cached input/i)).toBeInTheDocument();
    expect(screen.queryByText(/could not be priced/i)).not.toBeInTheDocument();
  });

  it('says the total is partial when a call could not be priced', () => {
    render(<ReviewCostCard lines={[
      { kind: 'review', model: 'TEST-MODEL', tokenType: 'INPUT', tokens: 1000,
        rateMillicentsPerMillion: null, costMillicents: null, pricingMode: 'UNKNOWN',
        pricedAt: '2026-08-06T00:00:00Z' },
    ]} unpricedCalls={1} />);

    expect(screen.getByText(/could not be priced/i)).toBeInTheDocument();
  });

  it('labels an unmetered call as self-hosted rather than as free', () => {
    render(<ReviewCostCard lines={[
      { kind: 'review', model: 'TEST-MODEL', tokenType: 'INPUT', tokens: 1000,
        rateMillicentsPerMillion: 0, costMillicents: 0, pricingMode: 'UNMETERED',
        pricedAt: '2026-08-06T00:00:00Z' },
    ]} unpricedCalls={0} />);

    expect(screen.getByText(/self-hosted/i)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run to confirm they fail**

Run: `cd spire-ui && npx vitest run src/components/SettingsLlmProviders.test.ts src/components/ReviewCostCard.test.tsx`
Expected: FAIL — `ReviewCostCard` does not exist; the settings form has no pricing-mode control.

- [ ] **Step 3: Update `api.ts`**

```ts
/** The neutral token-billing dimensions. Mirrors the server's TokenType. */
export type TokenType = 'INPUT' | 'CACHED_INPUT' | 'CACHE_WRITE' | 'OUTPUT' | 'REASONING' | 'TOTAL';

/** How a model's tokens are costed. UNKNOWN is a runtime outcome, never an operator's choice. */
export type PricingMode = 'METERED' | 'UNMETERED' | 'UNKNOWN';

/** Which paid call a charge belongs to. Mirrors the server's ChargeKind; stored and sent as the name. */
export type ChargeKind = 'REVIEW' | 'RECONCILE' | 'FOLLOWUP';

/** One token dimension of one LLM call, priced. Rate and cost are null exactly when UNKNOWN. */
export interface ChargeLineView {
  kind: ChargeKind;
  model: string;
  tokenType: TokenType;
  tokens: number;
  rateMillicentsPerMillion: number | null;
  costMillicents: number | null;
  pricingMode: PricingMode;
  pricedAt: string;
}
```

In `LlmModelView` and `LlmModelInput`, replace the two price numbers with:

```ts
  pricingMode: Exclude<PricingMode, 'UNKNOWN'>;
  rates: Partial<Record<Exclude<TokenType, 'TOTAL'>, number>>; // millicents per 1M tokens
```

Add `unpricedCalls: number` to `ReviewSummary`, and replace `LlmCall` with `ChargeLineView` in `ReviewDetail` (`chargeLines`). Update the `costMillicents` comment on `ReviewSummary` — `0` no longer means "unpriced", `unpricedCalls > 0` does.

- [ ] **Step 4: Rework the model form**

At `SettingsLlmProviders.tsx:560-680`:

- Replace `inputPrice`/`outputPrice` state with a `pricingMode` state plus a `rates` record keyed by token type.
- **Remove `|| 0`.** Build the payload from filled fields only:

```ts
  function ratesPayload(): Partial<Record<Exclude<TokenType, 'TOTAL'>, number>> {
    if (pricingMode === 'UNMETERED') return {};
    const out: Partial<Record<Exclude<TokenType, 'TOTAL'>, number>> = {};
    for (const [type, raw] of Object.entries(rates)) {
      // A blank field is an ABSENT rate, never a zero one — that conflation is the bug this
      // whole change removes, and defaulting here would reintroduce it one layer up.
      if (raw.trim() === '') continue;
      out[type as Exclude<TokenType, 'TOTAL'>] = dollarsToMillicentsPerMillion(Number(raw));
    }
    return out;
  }
```

- Validate before submitting: under `METERED`, `INPUT` and `OUTPUT` must be present and `> 0`, else set an error containing "rate is required" and do not call the API.
- Sort at line 60-64 by summed `rates` values rather than the removed fields; render rate cells at 261-262 from `rates`; update the empty-state copy at 240 and 469.
- Show `UNMETERED` models as "self-hosted" in the table, never as "$0.00".
- Use lucide-react icons only.

If the component passes 250 lines, extract the model form into `SettingsLlmModelForm.tsx` — it is already 585 lines and this adds to it.

- [ ] **Step 5: Replace the model-usage card**

**Where the card actually lives — verified, because the file list above was written before this was
checked.** It is not a new component grafted onto the detail page; it is an existing exported function:

| What | Location |
|---|---|
| `usageCard(r: ReviewDetail)` — the card, both branches | `render.tsx:648-700` |
| `llmCallRow(call, i)` — one per-call row | `render.tsx:~628-646` |
| `LLM_CALL_KIND_LABEL: Record<string, string>` | `render.tsx:623` |
| `EMPTY_USAGE: Usage` | `render.tsx:621` |
| Its only caller | `components/ReviewDetail.tsx:250` (imported at `:6`) |

Four consequences, none of them optional:

1. **`LLM_CALL_KIND_LABEL`'s keys must become the uppercase enum names** — `REVIEW`, `RECONCILE`,
   `FOLLOWUP`. The server now sends `ChargeKind` names verbatim. Left lowercase, every row silently
   falls through to `?? call.kind` and renders a raw `FOLLOWUP` instead of a label. It would look like a
   styling bug, not a contract mismatch.
2. **`EMPTY_USAGE` and the `r.usage` fallback branch (`:672` onward) are deleted**, not adapted — Task 2
   removed `ReviewDetail.usage` from the server. `Usage` becomes an unused import.
3. **`llmCallRow` reads `call.tokensIn` / `call.tokensOut` / `call.costMillicents`**, none of which exist
   on a charge line. It is replaced, not edited: a charge line is one token dimension, so several lines
   make up one call and the rows must group by `call_ref` before rendering.
4. **`costMillicents` is now `number | null`.** `reduce((sum, c) => sum + c.costMillicents, 0)` at `:650`
   would produce `NaN` or silently treat null as 0 — sum only non-null costs, and render the total as
   partial when any line is unpriced. This is the UI face of the branch's central rule; getting it wrong
   here undoes the work every server-side task did to keep unknown distinct from zero.

`render.tsx` is **876 lines**, so extract the new card into `components/ReviewCostCard.tsx` rather than
growing it further, and have `ReviewDetail.tsx:250` render the component directly. Delete `usageCard`,
`llmCallRow`, `LLM_CALL_KIND_LABEL` and `EMPTY_USAGE` from `render.tsx` once nothing imports them.

**Three test files reference the old shape** and must be updated: `App.routes.test.tsx`,
`components/ReviewDetail.layout.test.tsx`, `components/ReviewDetail.roles.test.tsx`.

- [ ] **Step 5b: Write `ReviewCostCard.tsx`**

Group `lines` by a `kind`+`pricedAt` key, render tokens/rate/cost per type, sum only non-null costs, and render "N call(s) could not be priced — this total is partial" when `unpricedCalls > 0`. Label `UNMETERED` lines "self-hosted (unmetered)". Keep it under 250 lines and under 8 props. Wire it into the review detail page in place of the old per-call cost list.

**This card also replaces the deleted `UsageView`.** Task 2 removed the server's legacy single-row model/prompt/completion/cost summary (`ReviewDetail.usage` and its `UsageView` record) because this card is a strict superset of it, so the review-detail page has shown no usage figures since Task 2. Delete the UI's `usage` rendering and its `api.ts` interface in this same pass — leaving either behind means `tsc` fails on a field the server no longer sends, or the page renders a permanently empty panel.

- [ ] **Step 6: Run the UI suite**

Run: `cd spire-ui && npx vitest run && npx tsc --noEmit`
Expected: PASS, `tsc` silent. Do **not** run this concurrently with a Docker build against the same tree — that has previously produced bogus failures.

- [ ] **Step 7: Commit**

```bash
git add spire-ui
git commit -m "Make a blank rate an error, and a partial total say so

A blank price field became Number('') || 0 and was sent as a valid zero,
which is one of the three layers that turned \"no price entered\" into
\"free\". A blank field is now a validation error and zero is reachable only
by marking the model self-hosted.

Optional dimensions are sent only when filled, so a model that does not bill
for cache writes carries no rate for them rather than a zero.

The cost card breaks a call down per token type and states when a total is
partial. An unmetered call reads as self-hosted, never as \$0.00 — the
distinction the whole change exists to preserve."
```

---

## Task 11: ADR-023 and the docs

**Files:**
- Modify: `docs/DECISIONS.md` (prepend ADR-023 above ADR-022 at line 7)
- Modify: `docs/SECURITY.md:167-176`
- Modify: `docs/ROADMAP.md`
- Modify: `CLAUDE.md` (Status section)
- Modify: `docs/SMOKE-TEST.md` (a new mode for live verification)

- [ ] **Step 1: Write ADR-023**

Title: `## ADR-023 — LLM cost is a charge-line ledger with snapshotted rates, and zero is a category`.

Cover, in the house style (decision, why, what was rejected): the partition invariant and why it cross-checks against the vendor's total — including that Anthropic's total excludes cache tokens, so the check is per-vendor; `pricing_mode` and why a stricter number check could not work; snapshotting the rate versus a temporal price catalog; guards at config time / pre-spend / post-hoc and why pricing being post-hoc forces that split; and the `UNIQUE (call_ref, token_type)` double-count fix.

**Record one thing the ADR must not overstate.** `ModelUsage` is a Kafka wire type and this branch
reshaped it, but the ADR-013 snapshot gate stayed green — it renders each component as `name: TypeName`
and never recurses, so a nested wire type's shape is invisible to it. State plainly that the break is
safe because **no persisted event carries usage** (verified: `DomainEvent` has no such field) and Kafka
retention is short, **not** because a compat gate approved it. The blind spot is filed as
`techdebt/spire-contract/3-2-contract-snapshot-does-not-recurse-into-nested-wire-types.md`; reference it
rather than restating it. An ADR that credits a gate which did not run is the kind of claim a future
reader will rely on.

- [ ] **Step 2: Update `SECURITY.md`**

The "Cost / abuse controls" section still says v1 has "per-review token budgeting only". Amend it to record that spend is now measured per token type at snapshotted rates and that unpriceable models are refused before they spend — while stating plainly that **fleet-level caps remain deferred**, and that a money cap is inert for `UNMETERED` deployments by design, so the caps will need a token or call-count axis.

- [ ] **Step 3: Update `ROADMAP.md` and `CLAUDE.md`**

Add a delivered entry dated 2026-08-06 describing the ledger, the guards and the wire change. In `ROADMAP.md`'s "Explicitly deferred" section, note that the fleet caps now have a trustworthy ledger to read and carry the `UNMETERED` consequence forward. Update the test counts from the final run.

- [ ] **Step 4: Add a smoke-test mode**

A new mode in `docs/SMOKE-TEST.md` covering: register a `METERED` model and confirm a real review produces per-type charge lines; mark a model `UNMETERED` and confirm the cost card says self-hosted rather than `$0.00`; attempt to save a metered model with a blank rate and confirm the refusal; attempt to delete a model a provider uses and confirm the 409; and point a provider at an uncatalogued model to confirm the 400.

- [ ] **Step 5: Full verification**

```bash
./gradlew testFast
./gradlew testServices
cd spire-ui && npx vitest run && npx tsc --noEmit
```

Expected: all green. Record the final counts in `CLAUDE.md` and `ROADMAP.md`.

- [ ] **Step 6: Commit and open the PR**

```bash
git add docs CLAUDE.md
git commit -m "Record ADR-023 and the cost accounting status

Documents why the ledger snapshots rates rather than deriving them from a
temporal catalog, why zero had to become a category rather than a stricter
number check, and why the guards split across config time, pre-spend and
post-hoc — pricing happens after the money is spent, so only the first two
can refuse anything.

SECURITY.md keeps the fleet-cap gap open and adds the consequence that a
money cap is inert on an unmetered deployment by design, so the caps will
need a token or call-count axis."
git push -u origin feat/llm-cost-accounting
gh pr create --base master --title "Record LLM cost as a priced charge-line ledger" --body "..."
```

The PR body should lead with the defect (an unpriceable call recorded as costing zero, which would have made a spend cap inert), then the design, then the migration's one-time operator action: **any model previously saved with a zero rate must be given rates or marked UNMETERED before it will run a review.**

---

## Self-Review

**Spec coverage.** Every section maps to a task: `pricing_mode` + rate table → 1, 4; ledger + `UNIQUE` → 1, 7; migration and legacy drop → 1; wire reshape + snapshot → 2; partition invariant → 3; the three guard layers → 4, 5, 6, 8; attention → 9; UI → 10; ADR + docs → 11; testing → distributed, with mutation verification in 3, 4, 6, 7, 8.

**Two gaps found and closed while writing the plan:**

- The spec's `costMillicents` signature (`String, int, int`) cannot express a partition, so Task 4 replaces it with `priceCall(String, ModelUsage)` rather than modifying it. Named explicitly so the implementer does not try to keep the old shape.
- Dropping `review_status.model` breaks the reviews-list model badge and the `llm_type` subquery, which the spec did not mention. Task 2 re-derives both from the ledger's most recent line.

**Three more found in the pre-flight scan, and fixed by reordering.** The plan originally reshaped the contract before running the migration, which forced an intermediate state for columns about to be dropped. That single root cause produced all three:

- It mandated `ps.setLong(6, 0L)` and a test asserting cost `0` — a fabricated zero, violating this plan's own no-synthetic-data constraint, in the branch whose entire purpose is removing that conflation.
- It deleted `ReviewProjection.llmCalls(String)` while `:995` still called it, so the task did not compile.
- It never mentioned `withReviewCall` (`:1338-1347`), `toDetail` (`:1317-1328`) or `ReviewRow`, all of which read the four dropped columns.

The migration is now Task 1, so Task 2 **removes** those reads instead of inventing values for them. Task 2 also absorbed the `listSummaries` re-derivation, because dropping the columns breaks that query at runtime the moment the migration lands — Task 7 now verifies that work rather than repeating it.

**Type consistency.** `ModelUsage.of(String, int, int)` is used identically in Tasks 2, 3, 4, 8. `ChargeLine.metered/unmetered/unknown` are the only constructors used after Task 4. `CallRefs.of(reviewId, slot, kind)` produces the exact strings Task 8's tests assert. `ReviewDetail.ChargeLineView` is defined once, in Task 2, and consumed by Tasks 7 and 10. `PricingMode.UNKNOWN` is never accepted from an operator (rejected in Tasks 4 and 5, excluded from the TS type in Task 10).

**Parameter limits.** `charge(...)` would have taken four parameters; Task 8 Step 5 uses a `ChargeRequest` record instead. The plan deliberately does **not** show the four-parameter version first — an earlier draft did, with prose correcting it afterwards, which invites an implementer reading quickly to write the version the project forbids. `priceCall`, `CallRefs.of`, `recordCharges` and `isPriceable` are all within three.

**Enum-backed wire values.** `TokenType`, `PricingMode` and `ChargeKind` are all stored as their enum names, and the ledger's `token_type`, `pricing_mode` and `kind` CHECKs list those names verbatim. Two of the three carry a drift guard that loops `values()` and asserts the CHECK accepts each — `token_type` (activated in Task 2) and `kind` (added in Task 4). `pricing_mode` has none because its three values are produced by pricing logic that the Task 4 tests already cover exhaustively; the two that needed guards are the ones whose vocabulary a future change is likely to extend.
