-- Two run failure causes of plan steps (M4 slice 3, A2): CHECKPOINT_MISSING, a required start whose held checkpoint is
-- gone (before this it was an alias of CLONE_FAILED, so no stored row holds it), and START_NOT_DESCENDED, a checkpoint
-- that does not extend the head its step started from. V97's closed set is replaced with the same values plus these two;
-- RunFailureCauseTest reads the newest such CHECK, so the enum and the column cannot drift.
ALTER TABLE factory_run DROP CONSTRAINT IF EXISTS factory_run_failure_cause_closed;

ALTER TABLE factory_run
    ADD CONSTRAINT factory_run_failure_cause_closed
    CHECK (failure_cause IS NULL OR failure_cause IN (
        'BAD_COMMAND', 'IMAGE_UNAVAILABLE', 'PUBLISHER_MISCONFIGURED', 'RUNTIME_UNAVAILABLE',
        'CREDENTIAL_REJECTED', 'ALL_CREDENTIALS_EXHAUSTED', 'AGENT_FAILED', 'MODEL_UNAVAILABLE',
        'AGENT_TIMEOUT', 'BLOCKED_EGRESS', 'SANDBOX_LOST', 'CLONE_FAILED', 'GATE_REFUSED',
        'PUSH_REJECTED', 'PUSH_TRANSPORT_FAILED', 'NON_FAST_FORWARD', 'BUNDLE_UNREADABLE',
        'DROPPED_COMMIT', 'SALVAGE_FAILED', 'DISPATCH_FAILED', 'DISPATCH_UNCERTAIN', 'CANCELLED',
        'WORKER_FAILED', 'RESULT_UNPUBLISHABLE', 'ARTIFACT_MISSING', 'CHECKPOINT_MISSING',
        'START_NOT_DESCENDED', 'UNCLASSIFIED'));
