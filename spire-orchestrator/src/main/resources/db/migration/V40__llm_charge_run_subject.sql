-- The ledger's spine was review-shaped: review_id NOT NULL, and a kind CHECK naming only the three
-- review call kinds. A run has a runId and none of those kinds, and putting a run id into a column
-- named review_id is the shape where a name lies (ARCHITECTURE §7) — the same defect already
-- tracked for review_id itself, which carries no provider.

ALTER TABLE llm_charge RENAME COLUMN review_id TO subject_id;

ALTER TABLE llm_charge ADD COLUMN subject_kind VARCHAR(8) NOT NULL DEFAULT 'REVIEW';
ALTER TABLE llm_charge ADD CONSTRAINT llm_charge_subject_kind
    CHECK (subject_kind IN ('REVIEW', 'RUN'));

-- Which capability pack caused the spend. Added NOW because it cannot be backfilled: a row that did
-- not record its capability cannot have one inferred later (ADR-034). The same reasoning as ADR-023
-- snapshotting a rate onto the row rather than re-deriving it from a mutable catalog.
ALTER TABLE llm_charge ADD COLUMN capability VARCHAR(16) NOT NULL DEFAULT 'REVIEW';
ALTER TABLE llm_charge ADD CONSTRAINT llm_charge_capability
    CHECK (capability IN ('REVIEW', 'BUILD', 'AUTONOMY', 'KNOWLEDGE', 'INSIGHT'));

-- Which pool member paid, so an UNMETERED run is still attributable to a credential.
ALTER TABLE llm_charge ADD COLUMN credential_ref TEXT;

-- The kind CHECK has to grow to admit the factory's call kinds.
--
-- It is dropped BY DEFINITION rather than by name, on purpose. V30 declared it inline and unnamed,
-- so Postgres generated a name from the table alone — llm_charge_check, llm_charge_check1, and so
-- on, in creation order. A migration naming llm_charge_kind_check, which is what it looks like it
-- should be called, would find nothing: DROP CONSTRAINT IF EXISTS succeeds having dropped nothing,
-- the new constraint is added alongside the old one, and every factory INSERT then fails against a
-- constraint the migration believed it had removed.
DO $$
DECLARE
    constraint_name TEXT;
BEGIN
    SELECT conname INTO constraint_name
    FROM pg_constraint
    WHERE conrelid = 'llm_charge'::regclass
      AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%RECONCILE%';

    IF constraint_name IS NULL THEN
        RAISE EXCEPTION 'no CHECK constraint on llm_charge mentions RECONCILE; V30 has changed shape';
    END IF;

    EXECUTE format('ALTER TABLE llm_charge DROP CONSTRAINT %I', constraint_name);
END $$;

ALTER TABLE llm_charge ADD CONSTRAINT llm_charge_kind
    CHECK (kind IN ('REVIEW', 'RECONCILE', 'FOLLOWUP', 'SPEC', 'PLAN', 'BUILD', 'FIX'));

-- A run's charges are read by subject exactly as a review's are.
CREATE INDEX IF NOT EXISTS llm_charge_subject_idx ON llm_charge (subject_kind, subject_id);
