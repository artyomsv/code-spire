-- Paying for a build with a Codex subscription (M3.5 part F).

-- How a repository's builds pay. Every existing setup pays with an API key, because until now that was
-- the only way a build could.
ALTER TABLE repository_build_defaults ADD COLUMN pay_with VARCHAR(16) NOT NULL DEFAULT 'API_KEY';
ALTER TABLE repository_build_defaults ADD CONSTRAINT repository_build_defaults_known_pay_with
    CHECK (pay_with IN ('API_KEY', 'SUBSCRIPTION'));
