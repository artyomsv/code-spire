package dev.codespire.contract.work;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** The decisions of a generated item's draft (M4 slice 3); {@link WorkItemLifecycle} delegates to it. */
public final class WorkDraftLifecycle {
    private WorkDraftLifecycle() {}

    private static final String SPEC = "spec", PLAN = "plan";
    private static final Set<String> DRAFT_PHASES = Set.of(SPEC, PLAN);
    /** Where a fresh admission reaches spec before anything is written: spec auto, or spec approve (§6.5). */
    private static final Set<String> SPEC_ADMISSION_REASONS = Set.of("specification_required", "spec_approval_unavailable");

    /** The reasons a spec or plan gate carries when it asks about a written artifact rather than permission (A6). */
    public static final Set<String> RESULT_REASONS = Set.of("specification_written", "plan_written");

    /** What a draft run spent, as the orchestrator measured it; unknown usage blocks further work, as a build's does. */
    public record RunUsage(long wallSeconds, long costMillicents, long calls, boolean known) {
        WorkProgress finish(WorkProgress progress) {
            WorkProgress finished = progress.finish(wallSeconds, costMillicents, calls);
            return known ? finished : finished.unknownUsage();
        }
    }

    /** The new attempt an automatic retry starts, and when. */
    public record Restart(UUID attemptId, Instant now) {}

    /**
     * A draft run's artifact was accepted (§6.3): the attempt is finished with its usage and the draft records the
     * artifact. A new specification clears any plan and both approvals ({@link WorkDraft#withSpecification}). What
     * follows, a result gate or the approval, is {@link #afterWritten}, a decision of its own.
     */
    public static WorkItemEvent written(WorkItemEvent item, WorkDraft.Written written, RunUsage usage) {
        String phase = requireRunning(item);
        WorkDraft draft = SPEC.equals(phase) ? item.draft().withSpecification(written)
                : item.draft().withPlan(written.artifact(), written.setupRevision());
        WorkItemEvent finished = WorkItemLifecycle.state(item, "awaiting_input", resultReason(phase), "DRAFT_ARTIFACT_WRITTEN",
                item.gate(), usage.finish(item.progress()));
        return finished.drafted(draft);
    }

    /**
     * Under {@code approve} a result gate opens on the artifact's digest, modelled on the verify result gate: the run
     * was admitted by the setting, so the person approves what it wrote (A6). Under {@code auto} the artifact is
     * approved at once. A phase switched {@code off} while the run worked stops there.
     */
    public static WorkItemEvent afterWritten(WorkItemEvent written, WorkItemLifecycle.GateOpening opening) {
        String phase = written.phase();
        String mode = written.policy().effective().getOrDefault(WorkPolicy.Phase.valueOf(phase.toUpperCase(Locale.ROOT)), "off");
        if ("off".equals(mode))
            return WorkItemLifecycle.state(written, "not_eligible", phase + "_off", "WORK_ITEM_REFUSED", written.gate(), written.progress().reserve(false));
        if (!"approve".equals(mode)) return approved(written);
        Instant now = opening.now();
        WorkGate gate = new WorkGate(opening.decisionId(), 1, "OPEN", phase, written.generation(), opening.historySize() + 1,
                written.policyRevision(), written.authority(), WorkGate.artifactOf(written), now,
                now.plusSeconds(written.policy().limits().gateTtlSeconds()), null, null, null, null);
        return WorkItemLifecycle.state(written, "waiting_approval", resultReason(phase), "GATE_OPENED", gate, written.progress().reserve(true));
    }

    /**
     * The written artifact is approved, by its result gate or by {@code auto}, and the draft records it. An approved
     * specification moves the item to plan, which the caller then enters as an ordinary entry: with no plan written it
     * parks for the draft sweep (decision 10), so approving starts no run here. An approved plan stays in plan until the
     * caller registers it ({@link #registered}), which decides whether the item may build (A4).
     */
    public static WorkItemEvent approved(WorkItemEvent item) {
        String phase = item.phase();
        if (item.draft() == null || !DRAFT_PHASES.contains(phase) || item.draft().artifactFor(phase) == null)
            throw new IllegalStateException("Only a written " + phase + " can be approved");
        return item.decision(item.policyRevision(), item.authority(), item.policy(), PLAN, "awaiting_input", "phase_completed",
                "DRAFT_APPROVED", item.gate(), item.progress().reserve(false)).drafted(item.draft().approved(phase));
    }

    /**
     * The first artifact was refused by validation: one automatic retry in a new attempt, its spend checked against the
     * caps as an entry would check it (§6.3). It counts no build run.
     */
    public static WorkItemEvent retryRequested(WorkItemEvent item, RunUsage usage, Restart restart) {
        String phase = requireRunning(item);
        WorkProgress finished = usage.finish(item.progress());
        if (finished.usageUnknown())
            return WorkItemLifecycle.state(item, "awaiting_input", "run_usage_unknown", "WORK_ITEM_REFUSED", item.gate(), finished);
        if (!finished.within(item.policy().limits(), phase))
            return WorkItemLifecycle.state(item, "stopped", "policy_cap_reached", "WORK_ITEM_REFUSED", item.gate(), finished);
        return WorkItemLifecycle.state(item, "active", "phase_started", "DRAFT_RETRY_REQUESTED", item.gate(),
                finished.start(restart.attemptId(), phase, restart.now()));
    }

    /**
     * The first artifact was refused: the attempt is finished with its usage before {@link #retryRequested} starts the
     * next one, so the refused attempt is recorded completed and only one attempt of the phase is ever started.
     */
    public static WorkItemEvent artifactRefused(WorkItemEvent item, RunUsage usage) {
        String phase = requireRunning(item);
        return WorkItemLifecycle.state(item, "active", SPEC.equals(phase) ? "spec_invalid" : "plan_invalid", "DRAFT_ARTIFACT_REFUSED",
                item.gate(), usage.finish(item.progress()));
    }

    /** The retry's artifact was refused too: the item waits for a person, never fails (FR-F20). */
    public static WorkItemEvent invalid(WorkItemEvent item, RunUsage usage) {
        String phase = requireRunning(item);
        return WorkItemLifecycle.state(item, "awaiting_input", SPEC.equals(phase) ? "spec_invalid" : "plan_invalid", "DRAFT_INVALID",
                item.gate(), usage.finish(item.progress()));
    }

    /** The run itself failed (decision 6): the item waits at draft_run_failed, never at failed (FR-F20). */
    public static WorkItemEvent runFailed(WorkItemEvent item, RunUsage usage) {
        requireRunning(item);
        return WorkItemLifecycle.state(item, "awaiting_input", "draft_run_failed", "DRAFT_RUN_FAILED", item.gate(), usage.finish(item.progress()));
    }

    /** Who asked for a revision, under which answer identity, and what they wrote (decision 8). */
    public record Revision(String resolver, String key, String note) {}

    /**
     * Revise with a note (§6.5, A6, decision 8): the result gate resolves REJECTED with the note, the phase's artifact is
     * cleared (a revised specification takes its plan with it), and the item waits where the draft sweep looks. No
     * attempt starts here: the sweep's own checks run first, and the note reaches the next run's prompt through the gate.
     *
     * @throws IllegalStateException {@code revision_limit_reached} after {@link WorkDraft#MAX_REVISIONS}, or when the
     *     open gate is not a result gate
     */
    public static WorkItemEvent revised(WorkItemEvent item, Revision revision) {
        if (!resultGate(item, item.gate()) || !"OPEN".equals(item.gate().state()))
            throw new IllegalStateException("Only an open specification or plan result gate can be revised");
        String phase = item.phase();
        WorkDraft draft = item.draft().revised(phase);
        WorkGate rejected = item.gate().resolve("REJECTED", revision.resolver(), "dashboard", revision.key(), revision.note());
        return WorkItemLifecycle.state(item, "awaiting_input", SPEC.equals(phase) ? "specification_to_write" : "plan_to_write", "DRAFT_REVISED",
                rejected, item.progress().reserve(false)).drafted(draft);
    }

    /**
     * The approved specification and plan are registered as the preparation the build runs (A4): the item moves to build,
     * which the caller then enters as an ordinary entry, so a build {@code approve} still asks first.
     */
    public static WorkItemEvent registered(WorkItemEvent item, WorkPreparation preparation) {
        WorkDraft draft = item.draft();
        if (draft == null || !PLAN.equals(item.phase()) || !draft.specificationApproved() || !draft.planApproved())
            throw new IllegalStateException("Only an approved specification and plan can be registered");
        if (!draft.specification().equals(preparation.specification()) || !draft.plan().equals(preparation.plan()))
            throw new IllegalArgumentException("A registered preparation carries the approved draft artifacts");
        return item.decision(item.policyRevision(), item.authority(), item.policy(), "build", "awaiting_input", "artifacts_registered",
                "DRAFT_REGISTERED", item.gate(), item.progress().reserve(false)).prepared(preparation).withMilestone("DRAFT_REGISTERED");
    }

    /** The approved plan cannot be registered (decision 15, or more steps than a build runs yet): the item waits in plan. */
    public static WorkItemEvent registrationRefused(WorkItemEvent item, String reason) {
        return WorkItemLifecycle.state(item, "awaiting_input", reason, "DRAFT_REGISTRATION_REFUSED", item.gate(), item.progress().reserve(false));
    }

    /** Whether this gate asks about a written artifact of the item's draft, rather than permission to run (A6). */
    public static boolean resultGate(WorkItemEvent item, WorkGate gate) {
        return gate != null && item.draft() != null && DRAFT_PHASES.contains(gate.phase()) && gate.phase().equals(item.phase())
                && item.draft().artifactFor(gate.phase()) != null && RESULT_REASONS.contains(item.reason());
    }

    private static String resultReason(String phase) {
        return SPEC.equals(phase) ? "specification_written" : "plan_written";
    }

    /** A draft result applies only to the running attempt of a drafted spec or plan; the caller checks the attempt. */
    private static String requireRunning(WorkItemEvent item) {
        String phase = item.phase();
        if (item.draft() == null || !DRAFT_PHASES.contains(phase) || !"active".equals(item.workflowStatus()))
            throw new IllegalStateException("A draft result applies to a running spec or plan, not "
                    + phase + " / " + item.workflowStatus() + " / " + item.reason());
        return phase;
    }

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
        boolean admittedBefore = previous != null && previous.admittedProfile() != null;
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
