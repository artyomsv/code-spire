-- A run that put its work on the branch but whose agent nobody observed to the end.
--
-- The agent overran its wall clock, or the runtime could not read its exit -- and the publisher had
-- already pushed. Until now the read model had to choose one of two wrong answers. Reporting it as
-- a failure hid work that is really on the remote, so an operator went looking for a branch the list
-- said did not exist. Reporting it as 'succeeded' asserted a clean delivery for a run whose agent
-- was killed mid-thought, so a half-written change looked finished and reviewed like one.
--
-- This is the third time the same call has been made in this schema, and it has gone the same way
-- every time: 'push_gate_refused' and 'delivered_nothing' are both outcomes that were once folded
-- into a neighbouring status and could not be told apart afterwards. The lesson each recorded is
-- that a status is how an operator reads a row at a glance, so an outcome that needs a different
-- action needs its own word.
--
-- Deliberately NOT a failure cause. The run delivered; nothing infrastructural broke. It is also
-- not retryable -- retrying pushes a second agent at a branch the first may still be holding, which
-- is the reason AGENT_TIMEOUT and SALVAGE_FAILED are both non-retryable in V46's taxonomy.
--
-- No backfill, and unlike V47 that is not a choice. The fact this status records arrived on the wire
-- for the first time in this change, so no existing row carries it, and a historical 'succeeded' row
-- with a ref is genuinely indistinguishable from one of these. Inventing a rule to relabel some of
-- them would put a guess in the column an operator trusts.

ALTER TABLE factory_run DROP CONSTRAINT IF EXISTS factory_run_status_closed;

ALTER TABLE factory_run
    ADD CONSTRAINT factory_run_status_closed
    CHECK (status IN ('queued', 'running', 'succeeded', 'delivered_nothing', 'delivered_unfinished',
                      'failed', 'push_gate_refused', 'cancelled'));
