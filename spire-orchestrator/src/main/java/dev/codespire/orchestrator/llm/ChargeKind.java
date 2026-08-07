package dev.codespire.orchestrator.llm;

/**
 * Which paid call a charge belongs to. An enum rather than a string because the ledger's {@code kind}
 * CHECK lists these names verbatim: a typo'd literal would otherwise pass compilation and fail the
 * INSERT at runtime, dead-lettering a result whose money has already been spent.
 */
public enum ChargeKind {
    /** The review generation call. */
    REVIEW,
    /** The ADR-019 reconcile call that verdicts a prior run's findings. */
    RECONCILE,
    /** A conversation follow-up answer. */
    FOLLOWUP
}
