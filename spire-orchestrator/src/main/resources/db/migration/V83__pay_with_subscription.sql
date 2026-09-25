-- Paying for a build with a Codex subscription (M3.5 part F).

-- A signed-in seat serves ONE agent at a time: two agents sharing a sign-in can each invalidate the
-- other's session. So a subscription member carries a lease: which run holds it, and until when. The
-- run id is the fence -- it is unique per attempt, so a late release from an old run matches nothing --
-- and the time bounds a lease whose release never arrives. NULL for every API key, which is shared.
ALTER TABLE harness_credential ADD COLUMN leased_by_run TEXT;
ALTER TABLE harness_credential ADD COLUMN leased_until TIMESTAMPTZ;

-- How a repository's builds pay. Every existing setup pays with an API key, because until now that was
-- the only way a build could.
ALTER TABLE repository_build_defaults ADD COLUMN pay_with VARCHAR(16) NOT NULL DEFAULT 'API_KEY';
ALTER TABLE repository_build_defaults ADD CONSTRAINT repository_build_defaults_known_pay_with
    CHECK (pay_with IN ('API_KEY', 'SUBSCRIPTION'));
