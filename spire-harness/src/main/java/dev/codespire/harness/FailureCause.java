package dev.codespire.harness;

/**
 * Why a run ended, from a closed set. Recorded as data, because "read the logs" is not a failure
 * cause (FR-F9). PUSH_GATE_REFUSED and SALVAGE_FAILED are added by the worker, not by an adapter.
 */
public enum FailureCause {
    PROVIDER_ERROR, NO_MODEL_RESPONSE, TIMED_OUT, OUT_OF_MEMORY,
    SANDBOX_LOST, SANDBOX_UNREACHABLE, EVICTED,
    DROPPED_COMMIT, SALVAGE_FAILED, PUSH_GATE_REFUSED, BLOCKED_EGRESS,
    HARNESS_EXIT_NONZERO
}
