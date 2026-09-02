-- A dispatch whose acknowledgement never arrived, which is NOT the same as one that failed (FR-F10).
--
-- The orchestrator writes the run's row, then publishes the command and waits for the broker to
-- acknowledge it. When that wait elapses, the previous code recorded the run as failed with cause
-- DISPATCH_FAILED, and DISPATCH_FAILED is re-armable: the operator's identical retry re-arms the row
-- and publishes again. That is correct exactly when the record never left, and wrong when it did.
--
-- An elapsed wait says nothing about the record. The producer may still be retrying; the append may
-- already have happened and only the acknowledgement been lost. So the old reading was a guess, made
-- in the optimistic direction, and being wrong about it costs a second agent run against the same
-- branch with the model paid twice. Being wrong the other way costs an operator one decision.
--
-- Hence a status rather than another cause: 'failed' is where a run goes when we KNOW what happened,
-- and every reader of that column -- the retry re-arm, the attention panel, an operator scanning the
-- list -- acts on it. This is the fourth time this schema has split an outcome out of a neighbouring
-- status rather than overloading it, for the reason V49 records: a status is how an operator reads a
-- row at a glance, so an outcome that needs a different action needs its own word.
--
-- The status stays LIVE, and that is load-bearing. If the record did land, the run's own RunStarted
-- is on its way, and the projection only applies a result to a live row -- so excluding this status
-- would silently drop the result of a run that really is executing, which is the very outcome the
-- uncertainty is about. Reality resolves most of these without anyone being asked.
--
-- No backfill. The condition could not be recorded before this change, so no existing row is in it,
-- and a historical 'failed' + DISPATCH_FAILED row is genuinely indistinguishable from one that
-- would now be uncertain -- relabelling any of them would put a guess in the column that decides
-- whether a paid run is started a second time.

ALTER TABLE factory_run DROP CONSTRAINT IF EXISTS factory_run_status_closed;

ALTER TABLE factory_run
    ADD CONSTRAINT factory_run_status_closed
    CHECK (status IN ('queued', 'running', 'succeeded', 'delivered_nothing', 'delivered_unfinished',
                      'failed', 'push_gate_refused', 'cancelled', 'dispatch_uncertain'));

-- ended_at must stay NULL while a run is unresolved, alongside 'queued' and 'running'. V43 pairs the
-- two so a row cannot claim to have finished at a time while still being open, and an uncertain
-- dispatch has not finished: it is waiting for its own result or for an operator. Without this the
-- new status cannot be written at all -- the old pairing makes a live row with no end time fail.
--
-- Found by its DEFINITION, not by its name. V43 declared it inline and unnamed, so its name is
-- whatever Postgres generated (an ordinal suffix that depends on how many other unnamed checks that
-- CREATE TABLE happened to declare first). V47 had to guess one of those once already. A DROP by a
-- guessed name is the worst kind of failure here: `IF EXISTS` makes a wrong guess succeed silently,
-- leaving the old constraint in place, and the very first uncertain dispatch then fails to write.
DO $$
DECLARE
    old_name text;
BEGIN
    SELECT conname INTO old_name
      FROM pg_constraint
     WHERE conrelid = 'factory_run'::regclass
       AND contype = 'c'
       AND pg_get_constraintdef(oid) LIKE '%ended_at%';

    IF old_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE factory_run DROP CONSTRAINT %I', old_name);
    END IF;
END $$;

ALTER TABLE factory_run
    ADD CONSTRAINT factory_run_ended_when_terminal
    CHECK ((status IN ('queued', 'running', 'dispatch_uncertain')) = (ended_at IS NULL));
