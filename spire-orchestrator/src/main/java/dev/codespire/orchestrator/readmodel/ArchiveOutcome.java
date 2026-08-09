package dev.codespire.orchestrator.readmodel;

/**
 * Why an archive attempt did or did not happen. A boolean cannot carry this: the UPDATE's WHERE
 * matches zero rows for all three failure cases, so the caller could not tell "no such review" from
 * "already archived" from "still running" — and each needs a different answer to the operator.
 */
public enum ArchiveOutcome {
    ARCHIVED,
    ALREADY_ARCHIVED,
    STILL_RUNNING,
    NOT_FOUND
}
