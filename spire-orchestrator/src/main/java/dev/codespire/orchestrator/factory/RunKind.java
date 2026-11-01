package dev.codespire.orchestrator.factory;

import dev.codespire.orchestrator.llm.ChargeKind;

/**
 * What a factory run was dispatched to do — {@code factory_run.kind}.
 *
 * <p>An enum because the alternative had grown to FOUR spellings of one vocabulary: V42's
 * {@code llm_charge} CHECK, V54's {@code factory_run_kind_closed} CHECK, and two Java string
 * literals. A typo in a writer compiles and fails at INSERT — or worse, produces a row that no cap
 * counts and no filter matches, which is what V54's own comment says that constraint exists to
 * prevent.
 *
 * <p><b>Not the same enum as {@link dev.codespire.orchestrator.llm.ChargeKind}, and the difference is
 * the point.</b> That one names a KIND OF CALL against the ledger and includes {@code REVIEW},
 * {@code RECONCILE} and {@code FOLLOWUP}, which no factory run can be; this one names a kind of RUN
 * and nothing else. Every run kind is also a charge kind ({@link #chargeKind}), but not the reverse,
 * and merging them would force this one to carry members V54's column refuses.
 *
 * <p>The values match V54's CHECK exactly. Nothing enforces that they stay matched — that is stated
 * in the migration too, honestly, rather than claimed as a guarantee no mechanism provides.
 */
public enum RunKind {

    /** A run dispatched against a work item or a REST request: the M0 and M1 shape. */
    BUILD,

    /** A run dispatched to fix a review finding (FR-F27). Names the review and the finding it fixes. */
    FIX,

    /** M4: a read-only run that refines a ticket into a specification. */
    SPEC,

    /** M4: a read-only run that decomposes the approved specification into ordered steps. */
    PLAN,

    /** Harness reviews: a read-only run that reviews a pull request's head. Names the review it answers. */
    REVIEW,

    /** Harness reviews: a read-only run that re-reviews a new head against the review's prior findings. */
    RECONCILE,

    /** Harness reviews: a read-only run that answers a reply on one of the review's threads. */
    FOLLOWUP;

    /** The ledger's name for this run's spend, so a run's charge row carries the run's own kind. */
    public ChargeKind chargeKind() {
        return switch (this) {
            case BUILD -> ChargeKind.BUILD;
            case FIX -> ChargeKind.FIX;
            case SPEC -> ChargeKind.SPEC;
            case PLAN -> ChargeKind.PLAN;
            case REVIEW, RECONCILE, FOLLOWUP -> ChargeKind.BUILD;
        };
    }

    /** Whether this run answers a review call, and is charged under the review rather than as a run. */
    public boolean isReview() {
        return false;
    }
}
