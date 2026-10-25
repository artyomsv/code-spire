package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.contract.work.WorkItemLifecycle;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;

/**
 * The draft transitions (M4 slice 3, decision 10): every specification or plan run starts here, through the decider
 * under the item lock, in the lock order {@link WorkItemTransitions} keeps.
 */
@ApplicationScoped
public class WorkDraftTransitions {
    @Inject WorkItemTransitions transitions;
    @Inject WorkClock clock;

    /**
     * Starts the item's draft attempt with {@link WorkItemLifecycle.EntryMode#DRAFT}, only while it is still at the
     * revision the sweep captured and still waiting for its draft: an item that moved meanwhile is refused as a second
     * sweep would be. The PHASE_STARTED inserts the attempt's effect row in the same transaction.
     *
     * <p>The authority is the one the sweep observed before buying anything, compared here under the lock as every
     * other phase entry compares it, and the policy is selected again from the ticket as it read then: a source switched
     * off, a removed label or an edited profile stops the run instead of starting it from the stored selection.
     */
    WorkItemTransitions.Outcome start(String id, long expectedRevision, WorkItemTransitions.Observation observed) {
        return transitions.underLock(id, (c, history, item) -> {
            if (!transitions.current(c, observed))
                return new WorkItemTransitions.Outcome(503, "authority_changed_during_read", item);
            if (history.size() != expectedRevision || !WorkDraftSweep.waiting(item))
                return new WorkItemTransitions.Outcome(409, "work_item_changed", item);
            WorkItemEvent checked = item.decision(observed.policy().revision(), WorkItemTransitions.authority(observed.source()),
                    transitions.select(observed, item), item.phase(), item.workflowStatus(), item.reason(), "POLICY_CHECKED",
                    item.gate(), item.progress());
            WorkItemEvent next = transitions.enterPrepared(c, history, checked, false, clock.now(), WorkItemLifecycle.EntryMode.DRAFT);
            next = transitions.decide(c, new WorkItemTransitions.Decision(history, next, "draft-start:" + UUID.randomUUID()));
            return new WorkItemTransitions.Outcome(200, next.reason(), next);
        });
    }
}
