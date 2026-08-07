-- A charge line's cost can never be negative.
--
-- V30 bounded the ledger's money in every direction but this one: tokens >= 0, a METERED line must carry
-- a rate, an UNMETERED one must be exactly zero -- and nothing said a cost could not be BELOW zero. It
-- could: `tokens x rate` is computed in a long of millicents, so a rate above roughly 9.2e12 wraps and
-- writes a negative cost, which SUBTRACTS from the review's total and from any deployment-wide sum. That
-- is worse than an unpriced call, because an unpriced call at least raises an attention row; a negative
-- one silently understates spend, and a spend cap reading these totals would fire late or never.
--
-- Two guards above this one now make the state unreachable: the validator refuses a rate large enough to
-- overflow (LlmModelPricingValidator.MAX_RATE_MILLICENTS_PER_MILLION), and the arithmetic itself refuses
-- to wrap (Math.multiplyExact, recorded as an UNKNOWN line). This is the layer no code path can bypass,
-- the same argument as V30's own CHECKs: a writer that computes a wrong sign fails at INSERT rather than
-- shipping a number that reads as real.
--
-- Deliberately VALIDATING, not NOT VALID. A row that violates this is arithmetically impossible money,
-- and if one exists the upgrade should stop and say so rather than carry it into every future total. It
-- can only have been written by a rate above the new bound, i.e. before that bound existed.
ALTER TABLE llm_charge
    ADD CONSTRAINT llm_charge_cost_non_negative
    CHECK (cost_millicents IS NULL OR cost_millicents >= 0);
