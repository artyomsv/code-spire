-- The work-item change feed (docs/superpowers/specs/2026-10-28-live-data-design.md §4.2). Each table a work-item
-- screen reads says on commit which item changed, on channel work_item_changed: the item's id, or '*' for a table
-- that affects many items. PostgreSQL delivers only committed notices and folds identical ones in one transaction.
--
-- An UPDATE notifies only when a column other than the table's bookkeeping columns changed, so a column added later
-- notifies by default. A table a work-item read starts to use needs a decision in WorkItemChangeCoverageTest.

-- TG_ARGV[0] names the column that holds the work-item id.
CREATE FUNCTION notify_work_item_changed() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    item TEXT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        item := to_jsonb(OLD) ->> TG_ARGV[0];
    ELSE
        item := to_jsonb(NEW) ->> TG_ARGV[0];
    END IF;
    IF item IS NOT NULL THEN
        PERFORM pg_notify('work_item_changed', item);
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION notify_work_items_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_notify('work_item_changed', '*');
    RETURN NULL;
END;
$$;

-- Item tables: the row names its work item.
CREATE TRIGGER work_item_changed_work_item AFTER INSERT OR DELETE ON work_item
    FOR EACH ROW EXECUTE FUNCTION notify_work_item_changed('id');
-- activity_polled_at: WorkActivityPoller, every 2 minutes per item. slot_reserved: WorkItemStore.
CREATE TRIGGER work_item_changed_work_item_update AFTER UPDATE ON work_item FOR EACH ROW
    WHEN (to_jsonb(OLD) - '{activity_polled_at,slot_reserved}'::text[] IS DISTINCT FROM to_jsonb(NEW) - '{activity_polled_at,slot_reserved}'::text[])
    EXECUTE FUNCTION notify_work_item_changed('id');

CREATE TRIGGER work_item_changed_work_item_gate AFTER INSERT OR DELETE ON work_item_gate
    FOR EACH ROW EXECUTE FUNCTION notify_work_item_changed('work_item_id');
CREATE TRIGGER work_item_changed_work_item_gate_update AFTER UPDATE ON work_item_gate FOR EACH ROW
    WHEN (to_jsonb(OLD) IS DISTINCT FROM to_jsonb(NEW))
    EXECUTE FUNCTION notify_work_item_changed('work_item_id');

CREATE TRIGGER work_item_changed_work_run_effect AFTER INSERT OR DELETE ON work_run_effect
    FOR EACH ROW EXECUTE FUNCTION notify_work_item_changed('work_item_id');
CREATE TRIGGER work_item_changed_work_run_effect_update AFTER UPDATE ON work_run_effect FOR EACH ROW
    WHEN (to_jsonb(OLD) IS DISTINCT FROM to_jsonb(NEW))
    EXECUTE FUNCTION notify_work_item_changed('work_item_id');

CREATE TRIGGER work_item_changed_work_land_effect AFTER INSERT OR DELETE ON work_land_effect
    FOR EACH ROW EXECUTE FUNCTION notify_work_item_changed('work_item_id');
-- next_check_at, updated_at: WorkLandEffects.backOff defers a re-check with the same reason. waitForChecks re-encrypts
-- detail on every pass, so a land that waits for checks still notifies its item every 2 minutes (accepted).
CREATE TRIGGER work_item_changed_work_land_effect_update AFTER UPDATE ON work_land_effect FOR EACH ROW
    WHEN (to_jsonb(OLD) - '{next_check_at,updated_at}'::text[] IS DISTINCT FROM to_jsonb(NEW) - '{next_check_at,updated_at}'::text[])
    EXECUTE FUNCTION notify_work_item_changed('work_item_id');

CREATE TRIGGER work_item_changed_work_item_preparation_attempt AFTER INSERT OR DELETE ON work_item_preparation_attempt
    FOR EACH ROW EXECUTE FUNCTION notify_work_item_changed('work_item_id');
CREATE TRIGGER work_item_changed_work_item_preparation_attempt_update AFTER UPDATE ON work_item_preparation_attempt FOR EACH ROW
    WHEN (to_jsonb(OLD) IS DISTINCT FROM to_jsonb(NEW))
    EXECUTE FUNCTION notify_work_item_changed('work_item_id');

CREATE TRIGGER work_item_changed_work_tracker_outbox AFTER INSERT OR DELETE ON work_tracker_outbox
    FOR EACH ROW EXECUTE FUNCTION notify_work_item_changed('work_item_id');
-- checked_at: WorkSourceEffects.touch defers a failed row.
CREATE TRIGGER work_item_changed_work_tracker_outbox_update AFTER UPDATE ON work_tracker_outbox FOR EACH ROW
    WHEN (to_jsonb(OLD) - 'checked_at' IS DISTINCT FROM to_jsonb(NEW) - 'checked_at')
    EXECUTE FUNCTION notify_work_item_changed('work_item_id');

CREATE TRIGGER work_item_changed_work_step_summary AFTER INSERT OR DELETE ON work_step_summary
    FOR EACH ROW EXECUTE FUNCTION notify_work_item_changed('work_item_id');
CREATE TRIGGER work_item_changed_work_step_summary_update AFTER UPDATE ON work_step_summary FOR EACH ROW
    WHEN (to_jsonb(OLD) IS DISTINCT FROM to_jsonb(NEW))
    EXECUTE FUNCTION notify_work_item_changed('work_item_id');

CREATE TRIGGER work_item_changed_work_item_artifact AFTER INSERT OR DELETE ON work_item_artifact
    FOR EACH ROW EXECUTE FUNCTION notify_work_item_changed('work_item_id');
CREATE TRIGGER work_item_changed_work_item_artifact_update AFTER UPDATE ON work_item_artifact FOR EACH ROW
    WHEN (to_jsonb(OLD) IS DISTINCT FROM to_jsonb(NEW))
    EXECUTE FUNCTION notify_work_item_changed('work_item_id');

-- Shared tables: one row is shown on many items, and only an operator's settings change writes it.
CREATE TRIGGER work_item_changed_repository_land_settings AFTER INSERT OR DELETE ON repository_land_settings
    FOR EACH ROW EXECUTE FUNCTION notify_work_items_changed();
CREATE TRIGGER work_item_changed_repository_land_settings_update AFTER UPDATE ON repository_land_settings FOR EACH ROW
    WHEN (to_jsonb(OLD) IS DISTINCT FROM to_jsonb(NEW))
    EXECUTE FUNCTION notify_work_items_changed();

CREATE TRIGGER work_item_changed_repository AFTER INSERT OR DELETE ON repository
    FOR EACH ROW EXECUTE FUNCTION notify_work_items_changed();
CREATE TRIGGER work_item_changed_repository_update AFTER UPDATE ON repository FOR EACH ROW
    WHEN (to_jsonb(OLD) IS DISTINCT FROM to_jsonb(NEW))
    EXECUTE FUNCTION notify_work_items_changed();

CREATE TRIGGER work_item_changed_work_source AFTER INSERT OR DELETE ON work_source
    FOR EACH ROW EXECUTE FUNCTION notify_work_items_changed();
-- health, checked_at and the scan cursor: WorkSourceScanner every 30 seconds, WorkSourceRegistry health.
CREATE TRIGGER work_item_changed_work_source_update AFTER UPDATE ON work_source FOR EACH ROW
    WHEN (to_jsonb(OLD) - '{health,checked_at,scan_cursor,scan_requested,scan_batch,scan_next_cursor}'::text[]
          IS DISTINCT FROM to_jsonb(NEW) - '{health,checked_at,scan_cursor,scan_requested,scan_batch,scan_next_cursor}'::text[])
    EXECUTE FUNCTION notify_work_items_changed();

CREATE TRIGGER work_item_changed_work_source_actor AFTER INSERT OR DELETE ON work_source_actor
    FOR EACH ROW EXECUTE FUNCTION notify_work_items_changed();
CREATE TRIGGER work_item_changed_work_source_actor_update AFTER UPDATE ON work_source_actor FOR EACH ROW
    WHEN (to_jsonb(OLD) IS DISTINCT FROM to_jsonb(NEW))
    EXECUTE FUNCTION notify_work_items_changed();

CREATE TRIGGER work_item_changed_scm_provider AFTER INSERT OR DELETE ON scm_provider
    FOR EACH ROW EXECUTE FUNCTION notify_work_items_changed();
-- The health check and the scope check: ProviderRegistry.
CREATE TRIGGER work_item_changed_scm_provider_update AFTER UPDATE ON scm_provider FOR EACH ROW
    WHEN (to_jsonb(OLD) - '{last_check_at,last_check_ok,last_check_error,reported_scopes,scopes_checked_at}'::text[]
          IS DISTINCT FROM to_jsonb(NEW) - '{last_check_at,last_check_ok,last_check_error,reported_scopes,scopes_checked_at}'::text[])
    EXECUTE FUNCTION notify_work_items_changed();

-- GET /api/approvals asks RepositoryAccounts whether a land gate can be answered by a PR review.
CREATE TRIGGER work_item_changed_repository_account AFTER INSERT OR DELETE ON repository_account
    FOR EACH ROW EXECUTE FUNCTION notify_work_items_changed();
CREATE TRIGGER work_item_changed_repository_account_update AFTER UPDATE ON repository_account FOR EACH ROW
    WHEN (to_jsonb(OLD) IS DISTINCT FROM to_jsonb(NEW))
    EXECUTE FUNCTION notify_work_items_changed();
