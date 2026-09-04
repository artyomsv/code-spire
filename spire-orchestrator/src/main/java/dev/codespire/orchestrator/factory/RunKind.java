package dev.codespire.orchestrator.factory;

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
 * and includes {@code SPEC} and {@code PLAN}, which are not calls the ledger charges yet. They
 * overlap on two names, and merging them would force each to carry members the other's column
 * refuses.
 *
 * <p>The values match V54's CHECK exactly. Nothing enforces that they stay matched — that is stated
 * in the migration too, honestly, rather than claimed as a guarantee no mechanism provides.
 */
public enum RunKind {

    /** A run dispatched against a work item or a REST request: the M0 and M1 shape. */
    BUILD,

    /** A run dispatched to fix a review finding (FR-F27). Names the review and the finding it fixes. */
    FIX,

    /** M4: a vague ticket refined into outcome, context and acceptance criteria. Not built. */
    SPEC,

    /** M4: decomposition into ordered vertical slices. Not built. */
    PLAN
}
