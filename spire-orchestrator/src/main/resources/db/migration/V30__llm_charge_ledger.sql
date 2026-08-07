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
