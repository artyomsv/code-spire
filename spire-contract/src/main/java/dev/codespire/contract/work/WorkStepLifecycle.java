package dev.codespire.contract.work;

import java.util.UUID;

/** RED: the step loop of a binding-6 build (M4 slice 3, §6.6, A17); every decision returns the wrong answer. */
public final class WorkStepLifecycle {
    private WorkStepLifecycle() {}

    public static WorkItemEvent planRegistered(WorkItemEvent registered, int steps) {
        return registered;
    }

    public static boolean asks(WorkItemEvent item, boolean landFix) {
        return false;
    }

    public static WorkItemLifecycle.EntryMode entryMode(WorkItemEvent item, boolean landFix) {
        return landFix ? WorkItemLifecycle.EntryMode.LAND_FIX : WorkItemLifecycle.EntryMode.ORDINARY;
    }

    public static WorkItemEvent next(WorkItemEvent completed) {
        return null;
    }

    public static WorkProgress composedStepCompleted(WorkItemEvent item, WorkProgress progress, boolean landFix) {
        return progress;
    }

    public static boolean routesRunFailure(WorkItemEvent item, boolean landFix) {
        return false;
    }

    public record RunFailure(UUID attemptId, boolean checkpointMissing, String lostRun, WorkDraftLifecycle.RunUsage usage) {}

    public static WorkItemEvent stepRunFailed(WorkItemEvent item, RunFailure failure, WorkItemLifecycle.GateOpening opening) {
        return item;
    }
}
