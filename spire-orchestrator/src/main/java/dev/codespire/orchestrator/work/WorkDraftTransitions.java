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
     */
    WorkItemTransitions.Outcome start(String id, long expectedRevision) {
        return transitions.underLock(id, (c, history, item) -> {
            if (history.size() != expectedRevision || !WorkDraftSweep.waiting(item))
                return new WorkItemTransitions.Outcome(409, "work_item_changed", item);
            WorkItemEvent next = transitions.enterPrepared(c, history, item, false, clock.now(), WorkItemLifecycle.EntryMode.DRAFT);
            next = transitions.decide(c, new WorkItemTransitions.Decision(history, next, "draft-start:" + UUID.randomUUID()));
            return new WorkItemTransitions.Outcome(200, next.reason(), next);
        });
    }
}
