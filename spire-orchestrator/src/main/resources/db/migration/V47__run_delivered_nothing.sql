-- A run that finished and delivered nothing gets its own terminal status.
--
-- The worker emits RunFinished with a null pushed_ref and no blocked paths when the agent exited
-- cleanly and committed nothing. That is a legitimate outcome, not a fault -- M0's own walking
-- skeleton produces it for a script that commits nothing -- and the read model recorded it as
-- 'succeeded', the same status as a run whose branch is on the remote. An operator reading the list
-- could not tell the two apart without opening every row, and any later "runs succeeded" number
-- would have counted both.
--
-- A status rather than a failure cause, and that is the whole decision. Calling it 'failed' sends
-- someone hunting for a bug that does not exist; the agent did what it was asked and had nothing to
-- deliver. This repeats the call already made for 'push_gate_refused', which is likewise a run that
-- did correct work that was deliberately not delivered -- and it is why the failure taxonomy in V46
-- carries no value for it.
--
-- The ended_at pairing is unchanged and still holds: this is a terminal status, so ended_at is set,
-- which the existing CHECK requires of everything outside queued and running.

ALTER TABLE factory_run DROP CONSTRAINT IF EXISTS factory_run_status_check;

ALTER TABLE factory_run DROP CONSTRAINT IF EXISTS factory_run_status_closed;

ALTER TABLE factory_run
    ADD CONSTRAINT factory_run_status_closed
    CHECK (status IN ('queued', 'running', 'succeeded', 'delivered_nothing',
                      'failed', 'push_gate_refused', 'cancelled'));

-- A run recorded before this migration that succeeded with no ref and no blocked paths is the same
-- outcome under the old vocabulary, so it is relabelled rather than left indistinguishable. Narrow
-- on purpose: a null pushed_ref with blocked paths is a gate refusal and already has its status.
UPDATE factory_run
   SET status = 'delivered_nothing'
 WHERE status = 'succeeded'
   AND pushed_ref IS NULL
   AND blocked_paths IS NULL;
