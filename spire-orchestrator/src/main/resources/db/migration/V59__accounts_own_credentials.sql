-- Accounts own credentials. Sources keep their per-source URL and allowlists.
ALTER TABLE scm_provider ALTER COLUMN workspace DROP NOT NULL;
ALTER TABLE scm_provider DROP CONSTRAINT scm_provider_role;
ALTER TABLE scm_provider ADD CONSTRAINT scm_provider_role
    CHECK (role IN ('REVIEWER', 'FACTORY', 'CONTEXT'));
ALTER TABLE scm_provider ADD CONSTRAINT scm_provider_workspace_by_role
    CHECK ((role = 'CONTEXT') = (workspace IS NULL));
ALTER TABLE scm_provider ADD COLUMN reported_scopes TEXT;
ALTER TABLE scm_provider ADD COLUMN scopes_checked_at TIMESTAMPTZ;

ALTER TABLE context_provider ADD COLUMN account_id UUID REFERENCES scm_provider(id);
CREATE INDEX context_provider_account_id ON context_provider(account_id);
ALTER TABLE context_provider ALTER COLUMN auth_kind DROP NOT NULL;
ALTER TABLE context_provider ALTER COLUMN auth_secret DROP NOT NULL;
DROP INDEX context_provider_single_default;
ALTER TABLE context_provider DROP COLUMN is_default;

-- Keep auth_* this release; drop them only in the release after the startup
-- reconciler has run everywhere. SQL cannot move ciphertext between Tink AADs.
-- A failed row retains its original encrypted credential for retry/recovery.
-- Successful rows clear their legacy credentials: this does not support application downgrade.
ALTER TABLE context_provider ADD CONSTRAINT context_provider_one_credential
    CHECK ((account_id IS NULL) <> (auth_secret IS NULL));
