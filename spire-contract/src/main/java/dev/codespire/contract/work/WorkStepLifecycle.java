package dev.codespire.contract.work;

import java.util.List;
import java.util.UUID;

/**
 * The step loop of a binding-6 build (M4 slice 3, §6.6, A17): build step k, verify it, then build step k+1 or deliver.
 * {@link WorkItemLifecycle} keeps the phase order; this class decides only what a plan step changes in it, so the
 * hard-coded order stays as it is for every other item.
 */
public final class WorkStepLifecycle {
    private WorkStepLifecycle() {}

    /** A registered binding-6 plan gets its cursor (A17); any other preparation builds as one step, with none. */
    public static WorkItemEvent planRegistered(WorkItemEvent registered, int steps) {
        WorkPreparation preparation = registered.preparation();
        if (preparation == null || preparation.bindingVersion() != WorkPreparation.STEPS_BINDING) return registered;
        return registered.stepped(WorkSteps.of(steps));
    }

    /**
     * Whether the step loop decides what follows a completed phase: a cursor not finished yet, outside a land fix. A land
     * fix after a delivered plan is one more build on its pull request, never a plan step (decision 12).
     */
    public static boolean asks(WorkItemEvent item, boolean landFix) {
        return item.steps() != null && !item.steps().done() && !landFix;
    }

    /**
     * How the next entry of this item is made, derived under the item lock (decision 13): {@link
     * WorkItemLifecycle.EntryMode#STEP} between the steps of a plan, so a later step opens no permission gate. Step 0 is the
     * first build of the approved plan and asks as today's single build does; a land fix wins.
     */
    public static WorkItemLifecycle.EntryMode entryMode(WorkItemEvent item, boolean landFix) {
        if (landFix) return WorkItemLifecycle.EntryMode.LAND_FIX;
        WorkSteps steps = item.steps();
        return steps != null && steps.index() > 0 && steps.index() < steps.total()
                ? WorkItemLifecycle.EntryMode.STEP : WorkItemLifecycle.EntryMode.ORDINARY;
    }

    /**
     * What follows a completed phase of a plan step, or null when the hard-coded order decides it. A passed verify
     * completes the step: the next one builds, or the last one delivers.
     *
     * @param completed the item as the phase's completion left it, with the passed execution on its progress
     * @throws IllegalStateException on a finished cursor, so a missed guard fails instead of moving past the plan
     */
    public static WorkItemEvent next(WorkItemEvent completed) {
        WorkSteps steps = completed.steps();
        if (steps == null || steps.done())
            throw new IllegalStateException("No plan step of " + completed.workItemId() + " is in progress, so nothing completes one");
        if (!"verify".equals(completed.phase())) return null;
        return stepPassed(completed);
    }

    /** RED stub: whether an empty ready may complete the current step. */
    public static boolean acceptsEmpty(WorkItemEvent item, boolean landFix, String head) {
        return true;
    }

    /** Counted on completion, never on a start (A11): a retried step counts once. */
    private static WorkItemEvent stepPassed(WorkItemEvent item) {
        WorkExecution passed = item.progress().execution();
        WorkSteps steps = item.steps().completedWith(new WorkSteps.StepResult(item.steps().index(), passed, false, true));
        WorkProgress progress = item.progress().completeStep();
        if (steps.done()) progress = progress.withExecution(delivered(steps));
        return item.decision(item.policyRevision(), item.authority(), item.policy(), steps.done() ? "deliver" : "build",
                "awaiting_input", "phase_completed", "STEP_COMPLETED", item.gate(), progress).stepped(steps);
    }

    /**
     * What deliver publishes (decision 9): the newest step run that holds a bundle, whose handoff carries the whole chain,
     * with the head and passed verification of the last step that changed something.
     */
    static WorkExecution delivered(WorkSteps steps) {
        WorkExecution chain = steps.chainRun().orElseThrow(() -> new IllegalStateException("No step holds a bundle to deliver")).execution();
        WorkExecution last = steps.lastNonEmpty().orElseThrow(() -> new IllegalStateException("No step changed anything to deliver")).execution();
        return new WorkExecution(chain.runId(), chain.build(), last.head(), last.verificationAttempt(), last.verification(), null, null, null);
    }

    /** An item without a cursor completes its one step when verify passes, and a land fix completes none (decision 12). */
    public static WorkProgress composedStepCompleted(WorkItemEvent item, WorkProgress progress, boolean landFix) {
        return item.steps() == null && !landFix && "verify".equals(item.phase()) ? progress.completeStep() : progress;
    }

    /**
     * Whether a failed run of the current step opens the verify gate instead of failing the item (decision 7): only when
     * an earlier step's run holds a bundle. With none there is no execution to put under a gate, and the run's failure is
     * today's.
     */
    public static boolean routesRunFailure(WorkItemEvent item, boolean landFix) {
        return "build".equals(item.phase()) && asks(item, landFix) && item.steps().chainRun().isPresent();
    }

    /**
     * A step run that failed before it was verified.
     *
     * @param attemptId the failed build attempt, which the unverified result names
     * @param lostRun the run whose checkpoint the start found gone; null unless {@code checkpointMissing}
     */
    public record RunFailure(UUID attemptId, boolean checkpointMissing, String lostRun, WorkDraftLifecycle.RunUsage usage) {}

    /**
     * The run of a later step failed (decision 7, A2): the completed steps are kept and the verify gate opens on the chain
     * run's head, unverified, so Retry builds this step again from where it started and Stop stops. A missing checkpoint
     * records the lost run, so no retry starts from it again (decision 11).
     */
    public static WorkItemEvent stepRunFailed(WorkItemEvent item, RunFailure failure, WorkItemLifecycle.GateOpening opening) {
        if (!routesRunFailure(item, false) || !"active".equals(item.workflowStatus()))
            throw new IllegalStateException("Only a running later step with commits behind it fails into the verify gate, not "
                    + item.phase() + " / " + item.workflowStatus() + " / " + item.reason());
        WorkExecution chain = item.steps().chainRun().orElseThrow().execution();
        WorkVerification unverified = new WorkVerification(failure.attemptId(), chain.head(), WorkVerification.Outcome.UNVERIFIED,
                failure.checkpointMissing() ? "checkpoint_missing" : "step_run_failed", List.of());
        WorkSteps steps = failure.checkpointMissing() && failure.lostRun() != null ? item.steps().lostCheckpoint(failure.lostRun()) : item.steps();
        WorkProgress progress = failure.usage().finish(item.progress()).withExecution(chain.verified(unverified));
        WorkItemEvent atVerify = item.decision(item.policyRevision(), item.authority(), item.policy(), "verify", "active",
                "step_run_failed", "STEP_RUN_FAILED", item.gate(), progress).stepped(steps);
        return WorkItemLifecycle.verifyResultGate(atVerify, item, opening.historySize(), opening.now(), opening.decisionId(), unverified);
    }
}
