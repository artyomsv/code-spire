-- "The vendor does not bill this token type", as an explicit operator assertion (M3.5 part D).
--
-- Until now a rate row meant one thing: a positive price. A type with no row was UNENTERED, and a call
-- reporting it was priced as UNKNOWN -- correctly, because nobody had said what it costs. That is what
-- stopped work item 36 after it had already spent: codex reported CACHED_INPUT and REASONING, neither
-- had a rate, the run's cost became unknown, and the next phase refused.
--
-- The fix is NOT to treat an unentered rate as zero. That is the invented price this project refuses:
-- a fabricated zero silently subtracts real spend from every total computed over the ledger. The fix is
-- to let an operator SAY "this vendor does not bill this type", and to store that assertion as a fact
-- distinct from an unentered rate -- exactly the distinction pricing_mode UNMETERED already draws
-- against UNKNOWN for a whole model (PricingMode.java).
--
-- So: billing = RATED carries a positive rate, billing = NOT_BILLED carries NO rate, and a type with no
-- row at all still means nobody has said. Existing rows are RATED by definition -- every one of them was
-- written under a CHECK demanding a rate above zero.
ALTER TABLE llm_model_rate ADD COLUMN billing VARCHAR(16) NOT NULL DEFAULT 'RATED'
    CHECK (billing IN ('RATED', 'NOT_BILLED'));

-- The old column was NOT NULL. A NOT_BILLED row has no rate to store, and storing 0 there would make
-- the assertion indistinguishable from a zero price the moment anything reads the column alone.
ALTER TABLE llm_model_rate ALTER COLUMN rate_millicents_per_million DROP NOT NULL;

-- The pairing, so neither half can drift: a rated row has a positive rate, an asserted row has none.
-- The original inline CHECK (rate > 0) stays and is harmless -- a NULL does not violate it -- but it
-- would allow a NOT_BILLED row that still carried a price, which is why this one is stricter.
ALTER TABLE llm_model_rate ADD CONSTRAINT llm_model_rate_billing_shape CHECK (
    (billing = 'RATED' AND rate_millicents_per_million IS NOT NULL AND rate_millicents_per_million > 0)
    OR (billing = 'NOT_BILLED' AND rate_millicents_per_million IS NULL)
);
