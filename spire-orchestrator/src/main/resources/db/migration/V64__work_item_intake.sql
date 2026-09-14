-- Bookkeeping and normalized policy evidence only. Tracker content is fetched on demand.
CREATE TABLE work_source (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL CHECK (btrim(name) <> ''),
    type TEXT NOT NULL CHECK (btrim(type) <> ''),
    origin TEXT NOT NULL CHECK (btrim(origin) <> ''),
    external_project_id TEXT NOT NULL CHECK (btrim(external_project_id) <> ''),
    external_scope TEXT NOT NULL CHECK (btrim(external_scope) <> ''),
    repository_id UUID NOT NULL REFERENCES repository(id),
    account_id UUID NOT NULL REFERENCES scm_provider(id),
    enabled BOOLEAN NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    scan_cursor TEXT,
    scan_requested BOOLEAN NOT NULL DEFAULT true,
    health TEXT NOT NULL DEFAULT 'not_checked',
    checked_at TIMESTAMPTZ,
    UNIQUE(repository_id,type,origin,external_project_id)
);
CREATE TABLE work_source_actor (
    source_id UUID NOT NULL REFERENCES work_source(id),
    actor_id TEXT NOT NULL CHECK (btrim(actor_id) <> ''),
    observed_handle TEXT NOT NULL,
    display_name TEXT NOT NULL,
    resolved_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(source_id,actor_id)
);
CREATE TABLE autonomy_profile (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL UNIQUE CHECK (btrim(name) <> ''),
    precedence INTEGER NOT NULL UNIQUE CHECK (precedence >= 0),
    deleted BOOLEAN NOT NULL DEFAULT false
);
CREATE TABLE autonomy_profile_version (
    profile_id UUID NOT NULL REFERENCES autonomy_profile(id),
    version BIGINT NOT NULL CHECK (version > 0),
    definition JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(profile_id,version)
);
CREATE FUNCTION immutable_work_profile() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Profile versions are immutable'; END;
$$;
CREATE TRIGGER autonomy_profile_version_immutable BEFORE UPDATE ON autonomy_profile_version
    FOR EACH ROW EXECUTE FUNCTION immutable_work_profile();
CREATE TABLE work_repository_policy (
    repository_id UUID PRIMARY KEY REFERENCES repository(id),
    revision BIGINT NOT NULL DEFAULT 1,
    ceiling_id UUID NOT NULL,
    ceiling_version BIGINT NOT NULL,
    FOREIGN KEY(ceiling_id,ceiling_version) REFERENCES autonomy_profile_version(profile_id,version)
);
CREATE TABLE work_label_mapping (
    repository_id UUID NOT NULL REFERENCES work_repository_policy(repository_id),
    label TEXT NOT NULL CHECK (btrim(label) <> ''),
    profile_id UUID NOT NULL,
    profile_version BIGINT NOT NULL,
    FOREIGN KEY(profile_id,profile_version) REFERENCES autonomy_profile_version(profile_id,version),
    PRIMARY KEY(repository_id,label)
);
CREATE TABLE work_item (
    id TEXT PRIMARY KEY,
    source_id UUID NOT NULL REFERENCES work_source(id),
    repository_id UUID NOT NULL REFERENCES repository(id),
    issue_id TEXT NOT NULL,
    issue_key TEXT NOT NULL,
    tracker_url TEXT NOT NULL,
    generation BIGINT NOT NULL CHECK (generation > 0),
    profile_id UUID,
    profile_version BIGINT,
    policy_revision BIGINT NOT NULL,
    phase TEXT NOT NULL,
    workflow_status TEXT NOT NULL,
    reason TEXT NOT NULL,
    revision BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY(profile_id,profile_version) REFERENCES autonomy_profile_version(profile_id,version)
);
CREATE TABLE work_item_delivery (
    work_item_id TEXT NOT NULL,
    delivery_id TEXT NOT NULL,
    PRIMARY KEY(work_item_id,delivery_id)
);
CREATE TABLE work_item_outbox (
    effect_id UUID PRIMARY KEY REFERENCES event_log(event_id),
    work_item_id TEXT NOT NULL,
    effect_type TEXT NOT NULL CHECK (effect_type IN ('WORK_EVENT')),
    payload BYTEA NOT NULL,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX work_item_outbox_pending ON work_item_outbox(created_at) WHERE published_at IS NULL;
CREATE TABLE work_item_gate (
    id UUID PRIMARY KEY,
    work_item_id TEXT NOT NULL REFERENCES work_item(id),
    generation BIGINT NOT NULL,
    phase TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('pending','approved','denied','expired','superseded'))
);
ALTER TABLE factory_run ADD COLUMN work_item_id TEXT REFERENCES work_item(id);
ALTER TABLE factory_run ADD COLUMN work_item_generation BIGINT;
ALTER TABLE factory_run ADD COLUMN work_item_phase TEXT;
