package dev.codespire.contract.work;

/**
 * The specification and plan a generated item is writing (M4 slice 3, A4), until both are approved and a
 * {@link WorkPreparation} is registered from them. Only references live here: the artifacts' text is stored
 * encrypted by the orchestrator and never enters aggregate history.
 */
public record WorkDraft(String mode, WorkPreparation.Artifact specification, WorkPreparation.Artifact plan,
                        int specificationRevisions, int planRevisions, boolean specificationApproved,
                        boolean planApproved, String baseCommit, long setupRevision) {
    public static final String GENERATED = "generated";
    public static final int MAX_REVISIONS = 3;

    /** A specification run's accepted result: the artifact, the commit it read and the setup it was dispatched with. */
    public record Written(WorkPreparation.Artifact artifact, String baseCommit, long setupRevision) {}

    public static WorkDraft admitted() { return new WorkDraft(GENERATED, null, null, 0, 0, false, false, null, 0); }

    public WorkDraft withSpecification(Written written) { throw new UnsupportedOperationException("RED"); }

    public WorkDraft withPlan(WorkPreparation.Artifact artifact, long revision) { throw new UnsupportedOperationException("RED"); }

    public WorkDraft approved(String phase) { throw new UnsupportedOperationException("RED"); }

    public WorkDraft revised(String phase) { throw new UnsupportedOperationException("RED"); }

    public WorkPreparation.Artifact artifactFor(String phase) { throw new UnsupportedOperationException("RED"); }
}
