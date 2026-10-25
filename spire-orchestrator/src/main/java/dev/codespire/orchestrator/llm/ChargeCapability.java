package dev.codespire.orchestrator.llm;

/**
 * Which capability pack caused the spend (ADR-035). The ledger's {@code capability} CHECK lists these
 * names verbatim.
 *
 * <p>Recorded on the row rather than derived later, and {@code V42} says why: a row that did not
 * record its capability cannot have one inferred afterwards. That is the same reasoning ADR-023 used
 * to snapshot a rate onto the row instead of re-deriving it from a mutable catalog.
 *
 * <p>Only {@link #REVIEW} and {@link #BUILD} have producers today. The other three are in the
 * schema's CHECK because the column was added once, with its full vocabulary, rather than migrated
 * per milestone; they are named here so a future producer picks the constant the database already
 * accepts instead of inventing a spelling that fails the INSERT after the money is spent.
 */
public enum ChargeCapability {
    /** The pull-request reviewer. */
    REVIEW,
    /** The factory's build runs. */
    BUILD,
    /** Autonomous work selection. Not produced yet. */
    AUTONOMY,
    /** Repository knowledge building. Not produced yet. */
    KNOWLEDGE,
    /** Analytics and reporting. Not produced yet. */
    INSIGHT
}
