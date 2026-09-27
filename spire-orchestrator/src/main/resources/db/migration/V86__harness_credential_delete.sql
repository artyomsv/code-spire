-- Deleting a harness key or subscription seat (operator feedback, 2026-09-27).
--
-- A member no run used is deleted outright. A member a run used cannot be: factory_run points at it,
-- and that attribution is the record of who paid. So such a member is ERASED instead: its stored key or
-- sign-in file is removed, it can never be switched on again, and it disappears from the pool screen.
-- The row stays only so a finished run can still name what paid for it.
ALTER TABLE harness_credential ALTER COLUMN api_key DROP NOT NULL;
ALTER TABLE harness_credential ADD COLUMN erased_at TIMESTAMPTZ;

-- A live member has a secret; an erased one has none, is off, and names no account.
ALTER TABLE harness_credential ADD CONSTRAINT harness_credential_secret_unless_erased
    CHECK (erased_at IS NOT NULL OR api_key IS NOT NULL);
ALTER TABLE harness_credential ADD CONSTRAINT harness_credential_erased_holds_nothing
    CHECK (erased_at IS NULL OR (api_key IS NULL AND NOT enabled AND account_ref IS NULL));

-- An erased member's name is free again, so the operator can reuse it. It stays on the erased row, which
-- is what a finished run shows.
ALTER TABLE harness_credential DROP CONSTRAINT harness_credential_label_key;
CREATE UNIQUE INDEX harness_credential_label_live ON harness_credential (label) WHERE erased_at IS NULL;
