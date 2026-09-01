-- ADR-037: the factory pushes as a DEDICATED machine account, not the review bot.
--
-- Two identities, two authority sets. Allowlisting the factory's account as a PR author must not
-- give the review bot allowed-author rights on /review, /finding and /fix — which is exactly what
-- sharing one identity would do, and is the widening ADR-035 forbids.
--
-- A role rather than a second table: same registry, same Tink encryption, same settings UI, same
-- bot-identity resolution on save. One column and a wider unique constraint.
ALTER TABLE scm_provider ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'REVIEWER';
ALTER TABLE scm_provider ADD CONSTRAINT scm_provider_role
    CHECK (role IN ('REVIEWER', 'FACTORY'));

-- The old constraint is UNIQUE (type, workspace), declared inline in V3 and therefore named by
-- Postgres. It is dropped by DEFINITION rather than by a guessed name, for the reason V40 records:
-- DROP CONSTRAINT IF EXISTS on a name that does not exist succeeds having dropped nothing, and the
-- widening would then silently not happen — a second account for the same workspace still refused.
DO $$
DECLARE
    constraint_name TEXT;
BEGIN
    SELECT conname INTO constraint_name
    FROM pg_constraint
    WHERE conrelid = 'scm_provider'::regclass
      AND contype = 'u'
      AND pg_get_constraintdef(oid) = 'UNIQUE (type, workspace)';

    IF constraint_name IS NULL THEN
        RAISE EXCEPTION 'no UNIQUE (type, workspace) on scm_provider; V3 has changed shape';
    END IF;

    EXECUTE format('ALTER TABLE scm_provider DROP CONSTRAINT %I', constraint_name);
END $$;

-- Existing rows are REVIEWER by the default above, so nothing changes for a deployment that never
-- registers a factory account.
ALTER TABLE scm_provider ADD CONSTRAINT scm_provider_type_workspace_role_key
    UNIQUE (type, workspace, role);
