package dev.codespire.orchestrator.work;

import dev.codespire.contract.port.PullRequestLander.BranchDeletion;
import dev.codespire.contract.scm.PullRequestRef;
import dev.codespire.contract.work.WorkExecution;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.contract.work.WorkLanding;
import dev.codespire.orchestrator.work.WorkLandEffects.Effect;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * After the merge (M4 land, spec §7.7): WORK_ITEM_LANDED completes the item at once, then the item branch is deleted,
 * which never holds the completion.
 */
@ApplicationScoped
public class WorkLandCleanup {
    static final int CLEANUP_TRIES = 3;

    @Inject WorkItemStore store;
    @Inject WorkLandTransitions landTransitions;
    @Inject WorkLandEffects effects;
    @Inject WorkClock clock;

    /** merged: WORK_ITEM_LANDED completes the item at once (§7.7); the branch delete follows and never holds it. */
    void record(Effect effect, WorkItemEvent item, WorkLandTarget target) {
        WorkExecution execution = item.progress().execution();
        PullRequestRef pullRequest = execution == null ? null : execution.pullRequest();
        if (pullRequest == null || pullRequest.number() != effect.pullRequest()) { effects.move(effect, "done", WorkLandSweep.WORK_ITEM_CHANGED); return; }
        WorkLanding landing = new WorkLanding(pullRequest, effect.mergeCommit(), effect.method(), effect.mergedBy(), clock.now());
        WorkItemTransitions.Outcome outcome = landTransitions.landed(effect.item(), effect.id(), landing);
        if (outcome.status() != 200) { effects.move(effect, "done", outcome.reason()); return; }
        effects.move(effect, "cleaning", null);
        clean(effect.at("cleaning"), store.load(effect.item()), target);
    }

    /** Delete the item branch only where it was merged; after three failed tries the operator is told (A6, A13). */
    void clean(Effect effect, WorkItemEvent item, WorkLandTarget target) {
        String branch = item.control() == null ? null : item.control().branch();
        String expected = effect.mergedHead() == null ? effect.approvedHead() : effect.mergedHead();
        BranchDeletion result;
        try { result = branch == null ? BranchDeletion.STILL_PRESENT : target.lander().deleteBranch(target.repo(), branch, expected); }
        catch (RuntimeException fault) {
            target.warn(effect, "could not delete its branch", fault);
            result = BranchDeletion.STILL_PRESENT;
        }
        if (result == BranchDeletion.DELETED || result == BranchDeletion.ABSENT) { effects.move(effect, "done", null); return; }
        int tries = effect.cleanupTries() + 1;
        effects.cleanupFailed(effect, tries);
    }
}
