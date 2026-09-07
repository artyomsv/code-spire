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
    FOLLOWUP,
    /**
     * A factory run: one agent, one wall clock, one branch.
     *
     * <p>The run is charged as ONE call although the agent made many model calls inside its
     * sandbox. That is not an approximation glossed over — the harness reports only its own
     * totals and the worker never sees the individual calls, so a finer grain would be invented
     * rather than measured.
     */
    BUILD,
    /**
     * A factory run dispatched to fix a review finding (FR-F27).
     *
     * <p>Its own kind rather than a BUILD, because the two answer different questions of the same
     * ledger: what a repository costs to build against, versus what the reviewer costs when it fixes
     * what it finds. Collapsing them would make the second unanswerable, and it is the one M2 exists
     * to make true. The V42 CHECK has admitted this value since the ledger learned about runs.
     */
    FIX
}
