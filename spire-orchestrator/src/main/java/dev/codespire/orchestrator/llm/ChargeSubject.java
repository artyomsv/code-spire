package dev.codespire.orchestrator.llm;

/**
 * What a charge is attributed to. The ledger's {@code subject_kind} CHECK lists these names verbatim.
 *
 * <p>An enum rather than a string for the reason {@link ChargeKind} is one, and for a second reason
 * that is specific to this column: the writer used to bind the literal {@code "REVIEW"} under a
 * comment explaining that a writer relying on the column DEFAULT would mislabel every row the day
 * the default changed. That reasoning was right, and the value became wrong the moment a second kind
 * of subject existed. A charge with the wrong subject kind is money attributed to the wrong thing —
 * it leaves the deployment total correct and puts a run's spend on some unrelated pull request's
 * cost card.
 */
public enum ChargeSubject {
    /** The subject id is a reviewId. */
    REVIEW,
    /** The subject id is a runId, which unlike a reviewId already carries its platform. */
    RUN
}
