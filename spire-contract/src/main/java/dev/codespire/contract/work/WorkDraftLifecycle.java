package dev.codespire.contract.work;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** The decisions of a generated item's draft (M4 slice 3); {@link WorkItemLifecycle} delegates to it. */
public final class WorkDraftLifecycle {
    private WorkDraftLifecycle() {}

    private static final Set<String> DRAFT_PHASES = Set.of("spec", "plan");
    /** Where a fresh admission reaches spec before anything is written: spec auto, or spec approve (§6.5). */
    private static final Set<String> SPEC_ADMISSION_REASONS = Set.of("specification_required", "spec_approval_unavailable");

    /** How {@link WorkItemLifecycle#enter} was asked to enter a phase, as this class needs it. */
    public record Entry(boolean draftRun, boolean available, UUID decisionId, Instant now) {}

    /**
     * Records the draft on an item the repository admits as generated (A16). Only an item not admitted before is
     * drafted: never observed, or observed while no label selected it, so it carries no admitted profile. An item
     * selected before the repository switched keeps its admitted profile, so it stays parked where it is, and a ticket
     * written first and labelled later is still drafted (Review Focus 31). The draft is recorded whatever the phase, so
     * an item that reaches spec through an intake gate is drafted too (Review Focus 23).
     *
     * @param reconciled the item {@link WorkItemLifecycle#reconcile} decided from {@code previous}
     */
    public static WorkItemEvent admitGenerated(WorkItemEvent previous, WorkItemEvent reconciled) {
        boolean admittedBefore = previous.admittedProfile() != null;
        if (admittedBefore || reconciled.admittedProfile() == null || reconciled.draft() != null) return reconciled;
        return awaitSpecification(reconciled.drafted(WorkDraft.admitted()), "DRAFT_ADMITTED");
    }

    /**
     * A drafted item that reaches spec through admission waits for the draft sweep instead of a copy or a permission
     * gate (§6.5). {@link WorkItemLifecycle#reconcile} asks it too when it rebuilds an item already drafted, which
     * {@link #admitGenerated} leaves alone.
     */
    static WorkItemEvent awaitSpecification(WorkItemEvent drafted, String milestone) {
        if (!"spec".equals(drafted.phase()) || !SPEC_ADMISSION_REASONS.contains(drafted.reason())) return drafted;
        return WorkItemLifecycle.state(drafted, "awaiting_input", "specification_to_write", milestone, drafted.gate(),
                drafted.progress().reserve(false));
    }

    /**
     * Entering spec or plan while the draft lacks that phase's artifact (decision 10). An ordinary entry parks for the
     * draft sweep, with no gate and no attempt; only the sweep's {@link WorkItemLifecycle.EntryMode#DRAFT} entry starts
     * the run, and it skips the permission gate, because the setting admits the run (A6). Null when the draft has
     * nothing to say about this entry.
     */
    static WorkItemEvent enter(WorkItemEvent item, Entry entry) {
        WorkDraft draft = item.draft();
        String phase = item.phase();
        if (draft == null || !DRAFT_PHASES.contains(phase) || draft.artifactFor(phase) != null) return null;
        WorkProgress idle = item.progress().reserve(false);
        if (!entry.draftRun())
            return WorkItemLifecycle.state(item, "awaiting_input", "spec".equals(phase) ? "specification_to_write" : "plan_to_write",
                    "DRAFT_WAITING", item.gate(), idle);
        if (!entry.available())
            return WorkItemLifecycle.state(item, "capability_unavailable", phase + "_capability_unavailable", "CAPABILITY_UNAVAILABLE", item.gate(), idle);
        return WorkItemLifecycle.state(item, "active", "phase_started", "PHASE_STARTED", item.gate(),
                item.progress().start(entry.decisionId(), phase, entry.now()));
    }
}
