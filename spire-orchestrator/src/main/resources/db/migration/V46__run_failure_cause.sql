-- FR-F9: a failed run's cause comes from a closed set, recorded as data.
--
-- factory_run.failure_cause shipped as an unconstrained VARCHAR(32). Three producers wrote it with
-- no agreement between them -- the launcher's own literals, the harness's FailureCause enum, and
-- whatever string the publisher put in its outcome JSON -- so a typo in any of them became a
-- category no query matched and no operator ever saw grouped. "Read the logs" is not a failure
-- cause, and neither is a value only one writer knows how to spell.
--
-- The set is RunFailureCause in spire-contract, which is also where the aliases live that translate
-- each producer's older vocabulary into it. This constraint is the second enforcement, at rest,
-- deliberately: the application normalises on the way in, and the database refuses anything that
-- escaped that. The same doubled guard the priceable-model rule uses, for the same reason -- a rule
-- enforced only at its one current caller is a rule the next caller does not inherit.
--
-- NOT an enum type. A Postgres enum needs a migration to add a value, which turns "classify this
-- new failure honestly" into a schema change, and the pressure then is to reuse a wrong value that
-- already exists. A CHECK is edited the same way but reads as data rather than as a type.

ALTER TABLE factory_run DROP CONSTRAINT IF EXISTS factory_run_failure_cause_closed;

-- Existing rows first: anything a pre-V46 writer stored that is not in the set becomes
-- UNCLASSIFIED rather than blocking the migration. Losing the original string is acceptable
-- precisely because it was unqueryable -- that is the defect being closed -- and failure_detail
-- still carries the operator-readable text beside it.
UPDATE factory_run
   SET failure_cause = 'UNCLASSIFIED'
 WHERE failure_cause IS NOT NULL
   AND failure_cause NOT IN (
       'BAD_COMMAND',
       'IMAGE_UNAVAILABLE',
       'PUBLISHER_MISCONFIGURED',
       'RUNTIME_UNAVAILABLE',
       'CREDENTIAL_REJECTED',
       'ALL_CREDENTIALS_EXHAUSTED',
       'AGENT_FAILED',
       'AGENT_TIMEOUT',
       'NOTHING_PRODUCED',
       'BLOCKED_EGRESS',
       'SANDBOX_LOST',
       'CLONE_FAILED',
       'GATE_REFUSED',
       'PUSH_REJECTED',
       'NON_FAST_FORWARD',
       'BUNDLE_UNREADABLE',
       'DROPPED_COMMIT',
       'SALVAGE_FAILED',
       'DISPATCH_FAILED',
       'DISPATCH_UNCERTAIN',
       'CANCELLED',
       'WORKER_FAILED',
       'UNCLASSIFIED');

ALTER TABLE factory_run
    ADD CONSTRAINT factory_run_failure_cause_closed
    CHECK (failure_cause IS NULL OR failure_cause IN (
        'BAD_COMMAND',
        'IMAGE_UNAVAILABLE',
        'PUBLISHER_MISCONFIGURED',
        'RUNTIME_UNAVAILABLE',
        'CREDENTIAL_REJECTED',
        'ALL_CREDENTIALS_EXHAUSTED',
        'AGENT_FAILED',
        'AGENT_TIMEOUT',
        'NOTHING_PRODUCED',
        'BLOCKED_EGRESS',
        'SANDBOX_LOST',
        'CLONE_FAILED',
        'GATE_REFUSED',
        'PUSH_REJECTED',
        'NON_FAST_FORWARD',
        'BUNDLE_UNREADABLE',
        'DROPPED_COMMIT',
        'SALVAGE_FAILED',
        'DISPATCH_FAILED',
        'DISPATCH_UNCERTAIN',
        'CANCELLED',
        'WORKER_FAILED',
        'UNCLASSIFIED'));
