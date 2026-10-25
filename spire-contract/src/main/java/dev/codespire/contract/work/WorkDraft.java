package dev.codespire.contract.work;

/**
 * The specification and plan a generated item is writing (M4 slice 3, A4), until both are approved and a
 * {@link WorkPreparation} is registered from them. Only references live here: the artifacts' text is stored
 * encrypted by the orchestrator and never enters aggregate history.
 *
 * @param baseCommit the commit the specification run read; set once a specification exists
 * @param setupRevision the build setup revision the latest artifact was dispatched with (decision 15): the
 *     specification's when it is written, replaced by the plan's
 */
public record WorkDraft(String mode, WorkPreparation.Artifact specification, WorkPreparation.Artifact plan,
                        int specificationRevisions, int planRevisions, boolean specificationApproved,
                        boolean planApproved, String baseCommit, long setupRevision) {
    public static final String GENERATED = "generated";
    /** Revisions a person may ask for per phase and generation (§6.5); then only Approve or Reject remain. */
    public static final int MAX_REVISIONS = 3;
    private static final String SPEC = "spec", PLAN = "plan";

    /** A specification run's accepted result: the artifact, the commit it read and the setup it was dispatched with. */
    public record Written(WorkPreparation.Artifact artifact, String baseCommit, long setupRevision) {}

    public WorkDraft {
        if (!GENERATED.equals(mode)) throw new IllegalArgumentException("Only a generated draft exists, not " + mode);
        requireStored(specification);
        requireStored(plan);
        if (specificationRevisions < 0 || specificationRevisions > MAX_REVISIONS || planRevisions < 0 || planRevisions > MAX_REVISIONS)
            throw new IllegalArgumentException("Revisions run from 0 to " + MAX_REVISIONS);
        if (plan != null && specification == null) throw new IllegalArgumentException("A plan is made from a specification");
        if (specificationApproved && specification == null || planApproved && plan == null)
            throw new IllegalArgumentException("Only a written artifact can be approved");
        if (specification != null && (baseCommit == null || !baseCommit.matches("[0-9a-f]{40}") || setupRevision < 1))
            throw new IllegalArgumentException("A written specification names its full base commit and its setup revision");
    }

    /** Text the factory wrote is stored here; a tracker reference would let an edit change what was approved. */
    private static void requireStored(WorkPreparation.Artifact artifact) {
        if (artifact != null && artifact.origin() != WorkPreparation.Origin.STORED)
            throw new IllegalArgumentException("A draft artifact is stored, never a tracker reference");
    }

    public static WorkDraft admitted() { return new WorkDraft(GENERATED, null, null, 0, 0, false, false, null, 0); }

    /** A new specification invalidates the plan made from the old one, and both approvals (§6.5). */
    public WorkDraft withSpecification(Written written) {
        return new WorkDraft(mode, written.artifact(), null, specificationRevisions, planRevisions, false, false,
                written.baseCommit(), written.setupRevision());
    }

    /** The plan names the setup it was made for, which registration compares (decision 15). */
    public WorkDraft withPlan(WorkPreparation.Artifact artifact, long revision) {
        if (specification == null) throw new IllegalStateException("A plan is made from a written specification");
        return new WorkDraft(mode, specification, artifact, specificationRevisions, planRevisions, specificationApproved, false,
                baseCommit, revision);
    }

    public WorkDraft approved(String phase) {
        if (artifactFor(phase) == null) throw new IllegalStateException("Nothing is written to approve for " + phase);
        return SPEC.equals(phase)
                ? new WorkDraft(mode, specification, plan, specificationRevisions, planRevisions, true, planApproved, baseCommit, setupRevision)
                : new WorkDraft(mode, specification, plan, specificationRevisions, planRevisions, specificationApproved, true, baseCommit, setupRevision);
    }

    /** Counts the revision and clears that artifact; a revised specification takes its plan with it. */
    public WorkDraft revised(String phase) {
        boolean spec = SPEC.equals(phase);
        int used = spec ? specificationRevisions : revisionsOf(phase);
        if (used >= MAX_REVISIONS) throw new IllegalStateException("revision_limit_reached");
        if (spec) return new WorkDraft(mode, null, null, used + 1, planRevisions, false, false, null, 0);
        return new WorkDraft(mode, specification, null, specificationRevisions, used + 1, specificationApproved, false, baseCommit, setupRevision);
    }

    private int revisionsOf(String phase) {
        if (!PLAN.equals(phase)) throw new IllegalArgumentException("A draft has no " + phase);
        return planRevisions;
    }

    /** The artifact written for that phase, or null while it is still to write. */
    public WorkPreparation.Artifact artifactFor(String phase) {
        if (SPEC.equals(phase)) return specification;
        if (PLAN.equals(phase)) return plan;
        throw new IllegalArgumentException("A draft has no " + phase);
    }
}
