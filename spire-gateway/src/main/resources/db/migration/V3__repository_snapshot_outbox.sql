-- Metadata only: signatures/credentials never cross to the orchestrator registry channel.
ALTER TABLE webhook_repo ADD COLUMN forge_origin TEXT;
CREATE TABLE repository_snapshot_outbox (
    revision BIGSERIAL PRIMARY KEY,
    registration_id UUID NOT NULL,
    payload JSONB NOT NULL,
    sent_at TIMESTAMPTZ
);
CREATE FUNCTION queue_repository_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE row_data webhook_repo; next_revision BIGINT;
BEGIN
    IF TG_OP = 'DELETE' THEN row_data := OLD; ELSE row_data := NEW; END IF;
    next_revision := nextval('repository_snapshot_outbox_revision_seq');
    INSERT INTO repository_snapshot_outbox(revision,registration_id,payload)
    VALUES (next_revision,row_data.id,jsonb_build_object(
        'type','RepositoryRegistration',
        'registrationId',row_data.id,'revision',next_revision,'providerType',row_data.provider_type,
        'forgeOrigin',row_data.forge_origin,'scope',row_data.scope,'target',row_data.target,
        'enabled',row_data.enabled,'deleted',TG_OP = 'DELETE'));
    RETURN row_data;
END $$;
CREATE TRIGGER repository_snapshot_created AFTER INSERT ON webhook_repo
    FOR EACH ROW EXECUTE FUNCTION queue_repository_snapshot();
CREATE TRIGGER repository_snapshot_changed AFTER UPDATE OF provider_type,scope,target,enabled,forge_origin ON webhook_repo
    FOR EACH ROW EXECUTE FUNCTION queue_repository_snapshot();
CREATE TRIGGER repository_snapshot_deleted AFTER DELETE ON webhook_repo
    FOR EACH ROW EXECUTE FUNCTION queue_repository_snapshot();
-- Populate the outbox atomically for every existing registration without changing its key or secret.
UPDATE webhook_repo SET forge_origin=forge_origin;
CREATE INDEX repository_snapshot_pending ON repository_snapshot_outbox(revision) WHERE sent_at IS NULL;
