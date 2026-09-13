-- Expand only. Workspace and its old constraints stay intact until the resolver cutover.
-- Account ids and provider:<id> encryption AADs are never rewritten by this bridge.
CREATE TABLE repository (
    id UUID PRIMARY KEY,
    scm_type TEXT NOT NULL,
    forge_origin TEXT NOT NULL,
    workspace TEXT NOT NULL CHECK (workspace <> ''),
    slug TEXT NOT NULL CHECK (slug <> ''),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    revision BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (scm_type, forge_origin, workspace, slug)
);
CREATE TABLE repository_account (
    repository_id UUID NOT NULL REFERENCES repository(id),
    account_id UUID NOT NULL REFERENCES scm_provider(id),
    role TEXT NOT NULL CHECK (role IN ('REVIEWER', 'FACTORY')),
    PRIMARY KEY (repository_id, role)
);
CREATE INDEX repository_account_account ON repository_account(account_id);

-- Immutable migration evidence, deliberately without an account FK: evidence survives re-assignment.
CREATE TABLE repository_legacy_account AS
    SELECT id AS account_id, type, base_url, workspace, role FROM scm_provider
    WHERE role IN ('REVIEWER', 'FACTORY');
ALTER TABLE repository_legacy_account ADD PRIMARY KEY (account_id);

CREATE TABLE repository_registration_bridge (
    registration_id UUID PRIMARY KEY,
    revision BIGINT NOT NULL,
    provider_type TEXT NOT NULL,
    forge_origin TEXT,
    scope TEXT NOT NULL,
    target TEXT NOT NULL,
    enabled BOOLEAN NOT NULL,
    deleted BOOLEAN NOT NULL,
    repository_id UUID REFERENCES repository(id),
    problem TEXT
);
ALTER TABLE review_status ADD COLUMN repository_id UUID REFERENCES repository(id);
ALTER TABLE factory_run ADD COLUMN repository_id UUID REFERENCES repository(id);
