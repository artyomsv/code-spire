-- FR-F9: a failed run's cause comes from a closed set, recorded as data.
--
-- factory_run.failure_cause shipped as an unconstrained VARCHAR(32). Three producers wrote it with
-- no agreement between them -- the launcher's own literals, the harness's FailureCause enum, and
-- whatever string the publisher put in its outcome JSON -- so a typo in any of them became a
-- category no query matched and no operator ever saw grouped. "Read the logs" is not a failure
-- cause, and neither is a value only one writer knows how to spell.
--
-- The set is RunFailureCause in spire-contract, which also owns the aliases translating each
-- producer's older vocabulary into it. This constraint is the second enforcement, at rest: the
-- application normalises on the way in, and the database refuses anything that escaped. The same
-- doubled guard the priceable-model rule uses, because a rule enforced only at its one current
-- caller is a rule the next caller does not inherit.
--
-- NOT an enum type. A Postgres enum needs a migration to add a value, which turns "classify this
-- new failure honestly" into a schema change, and the pressure is then to reuse a wrong value that
-- already exists. A CHECK is edited the same way but reads as data rather than as a type.
--
-- MAINTENANCE. The set appears twice below, once to translate history and once as the constraint.
-- Adding a value means a NEW migration replacing the CHECK; this file is immutable once applied.
-- RunFailureCauseTest.everyCauseTheEnumCanProduceIsAcceptedByTheColumn reads the newest such
-- migration and fails if the enum has a value the CHECK does not, so the two cannot drift.

-- 1. Translate the vocabularies history was actually written in.
--
-- Every failed row M0 produced carries an alias spelling rather than a wire-set value: the harness's
-- own words and the publisher's. SANDBOX_UNREACHABLE alone is what the launcher wrote for every
-- daemon failure. Sending them all to UNCLASSIFIED would discard the classification of every failure
-- this deployment has recorded, and would do it for values whose correct target is known exactly.
-- The application path already translates these; the same table is applied here so a row written
-- before this migration and a row written after it mean the same thing.
UPDATE factory_run
   SET failure_cause = CASE failure_cause
       WHEN 'PROVIDER_ERROR'       THEN 'MODEL_UNAVAILABLE'
       WHEN 'NO_MODEL_RESPONSE'    THEN 'MODEL_UNAVAILABLE'
       WHEN 'HARNESS_EXIT_NONZERO' THEN 'AGENT_FAILED'
       WHEN 'TIMED_OUT'            THEN 'AGENT_TIMEOUT'
       WHEN 'OUT_OF_MEMORY'        THEN 'SANDBOX_LOST'
       WHEN 'EVICTED'              THEN 'SANDBOX_LOST'
       WHEN 'SANDBOX_UNREACHABLE'  THEN 'SANDBOX_LOST'
       WHEN 'PUSH_GATE_REFUSED'    THEN 'GATE_REFUSED'
       WHEN 'PUSH_FAILED'          THEN 'PUSH_TRANSPORT_FAILED'
       WHEN 'PUBLISHER_FAILED'     THEN 'WORKER_FAILED'
       ELSE failure_cause
   END
 WHERE failure_cause IN ('PROVIDER_ERROR', 'NO_MODEL_RESPONSE', 'HARNESS_EXIT_NONZERO', 'TIMED_OUT',
                         'OUT_OF_MEMORY', 'EVICTED', 'SANDBOX_UNREACHABLE', 'PUSH_GATE_REFUSED',
                         'PUSH_FAILED', 'PUBLISHER_FAILED');

-- 2. Anything still outside the set becomes UNCLASSIFIED, keeping its own word in the detail.
--
-- Preserved rather than dropped, exactly as FactoryRunProjection does at runtime. The two paths
-- decide the same thing and must decide it the same way, or a row's meaning depends on which side
-- of this migration it was written on.
UPDATE factory_run
   SET failure_detail = 'unrecognised cause ''' || failure_cause || ''''
                        || COALESCE(': ' || failure_detail, ''),
       failure_cause = 'UNCLASSIFIED'
 WHERE failure_cause IS NOT NULL
   AND failure_cause NOT IN (
       'BAD_COMMAND', 'IMAGE_UNAVAILABLE', 'PUBLISHER_MISCONFIGURED', 'RUNTIME_UNAVAILABLE',
       'CREDENTIAL_REJECTED', 'ALL_CREDENTIALS_EXHAUSTED', 'AGENT_FAILED', 'MODEL_UNAVAILABLE',
       'AGENT_TIMEOUT', 'BLOCKED_EGRESS', 'SANDBOX_LOST', 'CLONE_FAILED', 'GATE_REFUSED',
       'PUSH_REJECTED', 'PUSH_TRANSPORT_FAILED', 'NON_FAST_FORWARD', 'BUNDLE_UNREADABLE',
       'DROPPED_COMMIT', 'SALVAGE_FAILED', 'DISPATCH_FAILED', 'DISPATCH_UNCERTAIN', 'CANCELLED',
       'WORKER_FAILED', 'RESULT_UNPUBLISHABLE', 'UNCLASSIFIED');

ALTER TABLE factory_run DROP CONSTRAINT IF EXISTS factory_run_failure_cause_closed;

ALTER TABLE factory_run
    ADD CONSTRAINT factory_run_failure_cause_closed
    CHECK (failure_cause IS NULL OR failure_cause IN (
        'BAD_COMMAND', 'IMAGE_UNAVAILABLE', 'PUBLISHER_MISCONFIGURED', 'RUNTIME_UNAVAILABLE',
        'CREDENTIAL_REJECTED', 'ALL_CREDENTIALS_EXHAUSTED', 'AGENT_FAILED', 'MODEL_UNAVAILABLE',
        'AGENT_TIMEOUT', 'BLOCKED_EGRESS', 'SANDBOX_LOST', 'CLONE_FAILED', 'GATE_REFUSED',
        'PUSH_REJECTED', 'PUSH_TRANSPORT_FAILED', 'NON_FAST_FORWARD', 'BUNDLE_UNREADABLE',
        'DROPPED_COMMIT', 'SALVAGE_FAILED', 'DISPATCH_FAILED', 'DISPATCH_UNCERTAIN', 'CANCELLED',
        'WORKER_FAILED', 'RESULT_UNPUBLISHABLE', 'UNCLASSIFIED'));
