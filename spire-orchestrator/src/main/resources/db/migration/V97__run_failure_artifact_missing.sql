-- A specification or plan run that ended without handing over its artifact (M4 slice 3) fails with its own cause,
-- ARTIFACT_MISSING: the agent ran and was paid for, and nothing came back to show for it. V46's closed set is
-- replaced with the same values plus that one; RunFailureCauseTest reads the newest such CHECK, so the enum and
-- the column cannot drift. No row holds the new value yet, so nothing is translated.
ALTER TABLE factory_run DROP CONSTRAINT IF EXISTS factory_run_failure_cause_closed;

ALTER TABLE factory_run
    ADD CONSTRAINT factory_run_failure_cause_closed
    CHECK (failure_cause IS NULL OR failure_cause IN (
        'BAD_COMMAND', 'IMAGE_UNAVAILABLE', 'PUBLISHER_MISCONFIGURED', 'RUNTIME_UNAVAILABLE',
        'CREDENTIAL_REJECTED', 'ALL_CREDENTIALS_EXHAUSTED', 'AGENT_FAILED', 'MODEL_UNAVAILABLE',
        'AGENT_TIMEOUT', 'BLOCKED_EGRESS', 'SANDBOX_LOST', 'CLONE_FAILED', 'GATE_REFUSED',
        'PUSH_REJECTED', 'PUSH_TRANSPORT_FAILED', 'NON_FAST_FORWARD', 'BUNDLE_UNREADABLE',
        'DROPPED_COMMIT', 'SALVAGE_FAILED', 'DISPATCH_FAILED', 'DISPATCH_UNCERTAIN', 'CANCELLED',
        'WORKER_FAILED', 'RESULT_UNPUBLISHABLE', 'ARTIFACT_MISSING', 'UNCLASSIFIED'));
