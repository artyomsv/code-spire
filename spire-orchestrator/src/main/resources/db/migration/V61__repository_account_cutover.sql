-- Keep the populated legacy workspace column as rollback evidence until slice 10.
-- Accounts no longer own repository namespaces, including newly created accounts.
ALTER TABLE scm_provider DROP CONSTRAINT scm_provider_type_workspace_role_key;
ALTER TABLE scm_provider DROP CONSTRAINT scm_provider_workspace_by_role;

CREATE TABLE repository_unregistered_event (
    registration_id UUID NOT NULL,
    scm_type TEXT NOT NULL,
    forge_origin TEXT CHECK (forge_origin IS NULL OR forge_origin <> ''),
    workspace TEXT NOT NULL CHECK (workspace <> ''),
    slug TEXT NOT NULL CHECK (slug <> ''),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE NULLS NOT DISTINCT (registration_id,scm_type,forge_origin,workspace,slug)
);
