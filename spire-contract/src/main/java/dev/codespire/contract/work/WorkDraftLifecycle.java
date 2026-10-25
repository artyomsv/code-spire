package dev.codespire.contract.work;

import java.time.Instant;
import java.util.UUID;

/** The decisions of a generated item's draft (M4 slice 3); {@link WorkItemLifecycle} delegates to it. */
public final class WorkDraftLifecycle {
    private WorkDraftLifecycle() {}

    /** How {@link WorkItemLifecycle#enter} was asked to enter a phase, as this class needs it. */
    public record Entry(boolean draftRun, boolean available, UUID decisionId, Instant now) {}

    public static WorkItemEvent admitGenerated(WorkItemEvent previous, WorkItemEvent reconciled) {
        return reconciled;
    }

    static WorkItemEvent enter(WorkItemEvent item, Entry entry) {
        return null;
    }
}
