-- Add routing metadata without rewriting webhook keys, secrets or rejection evidence.
ALTER TABLE webhook_repo ADD COLUMN repository_id UUID;
ALTER TABLE webhook_repo ADD COLUMN event_kind TEXT NOT NULL DEFAULT 'REVIEWER'
    CHECK (event_kind IN ('REVIEWER','FACTORY','ISSUE'));
ALTER TABLE webhook_repo ADD COLUMN source_id UUID;
ALTER TABLE webhook_repo ADD COLUMN revision BIGINT;
ALTER TABLE webhook_repo DROP CONSTRAINT webhook_repo_provider_type_scope_target_key;
ALTER TABLE webhook_repo ADD CONSTRAINT webhook_repo_repository_kind_key UNIQUE (repository_id,event_kind);
CREATE UNIQUE INDEX webhook_repo_legacy_kind_key
    ON webhook_repo(provider_type,forge_origin,scope,target,event_kind) NULLS NOT DISTINCT
    WHERE repository_id IS NULL;

DROP TRIGGER repository_snapshot_changed ON webhook_repo;
CREATE FUNCTION assign_repository_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.revision := nextval('repository_snapshot_outbox_revision_seq');
    RETURN NEW;
END $$;
CREATE TRIGGER repository_revision_created BEFORE INSERT ON webhook_repo
    FOR EACH ROW EXECUTE FUNCTION assign_repository_revision();
CREATE TRIGGER repository_revision_changed
    BEFORE UPDATE OF provider_type,scope,target,enabled,forge_origin,repository_id,event_kind,source_id ON webhook_repo
    FOR EACH ROW EXECUTE FUNCTION assign_repository_revision();

CREATE OR REPLACE FUNCTION queue_repository_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE row_data webhook_repo; next_revision BIGINT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        row_data := OLD;
        next_revision := nextval('repository_snapshot_outbox_revision_seq');
    ELSE
        row_data := NEW;
        next_revision := NEW.revision;
    END IF;
    INSERT INTO repository_snapshot_outbox(revision,registration_id,payload)
    VALUES (next_revision,row_data.id,jsonb_build_object(
        'type','RepositoryRegistration',
        'registrationId',row_data.id,'revision',next_revision,'providerType',row_data.provider_type,
        'forgeOrigin',row_data.forge_origin,'scope',row_data.scope,'target',row_data.target,
        'enabled',row_data.enabled,'deleted',TG_OP = 'DELETE',
        'repositoryId',row_data.repository_id,'eventKind',row_data.event_kind,'sourceId',row_data.source_id));
    RETURN row_data;
END $$;
CREATE TRIGGER repository_snapshot_changed
    AFTER UPDATE OF provider_type,scope,target,enabled,forge_origin,repository_id,event_kind,source_id ON webhook_repo
    FOR EACH ROW EXECUTE FUNCTION queue_repository_snapshot();
UPDATE webhook_repo SET event_kind=event_kind;
ALTER TABLE webhook_repo ALTER COLUMN revision SET NOT NULL;
