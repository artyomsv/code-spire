-- READ BEFORE APPLYING: this migration destroys the prior spend record, irreversibly.
--
-- Section 5 drops review_llm_call and review_status's four rollup columns. Flyway has no
-- down-migration and nothing in this file reconstructs them, so after the upgrade there is no record
-- of what any past review cost, how many tokens it used, or which model ran it -- nothing left to
-- reconcile against a vendor invoice.
--
-- Section 5's reasoning is about PRICE, and it holds: a legacy 0 cannot be carried forward honestly,
-- because the coercion that produced it meant "unpriced at the time", not "free". It says nothing
-- about model, tokens_in and tokens_out, which were never ambiguous and go with it anyway. So if this
-- deployment has reviewed real pull requests, archive first -- while still on V29, before this runs:
--
--   SET search_path TO orchestrator;          -- Flyway migrates with default-schema=orchestrator
--   CREATE TABLE review_llm_call_pre_v30 AS SELECT * FROM review_llm_call;
--   CREATE TABLE review_status_usage_pre_v30 AS
--       SELECT review_id, model, tokens_in, tokens_out, cost_millicents FROM review_status;
--
-- Both are plain tables Flyway does not manage, so they survive every later migration and can be
-- dropped by hand once reconciled. To take a copy off the box instead, from psql:
--
--   \copy (SELECT * FROM orchestrator.review_llm_call) TO 'review_llm_call.csv' WITH CSV HEADER
--
-- One thing the archive cannot give back: every cost_millicents in it still means "0 may be unknown
-- rather than free". That ambiguity is what this migration exists to end and it is not resolvable
-- after the fact -- the archive preserves the token counts and models, which are recoverable, and the
-- costs only as the unreliable figures they always were.

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
    kind          VARCHAR(16)  NOT NULL,   -- REVIEW | RECONCILE | FOLLOWUP
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
    -- Names the ChargeKind enum verbatim, matching how token_type already works below: a typo'd
    -- literal in the writer would otherwise pass compilation and dead-letter the result at INSERT time.
    CHECK (kind IN ('REVIEW', 'RECONCILE', 'FOLLOWUP')),
    -- Unlike llm_model_rate.token_type, TOTAL belongs here: an unreconciled call is charged as one
    -- undivided line (see the aTotalLineCannotBeMetered CHECK below) rather than left unrepresentable.
    CHECK (token_type IN ('INPUT', 'CACHED_INPUT', 'CACHE_WRITE', 'OUTPUT', 'REASONING', 'TOTAL')),
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
