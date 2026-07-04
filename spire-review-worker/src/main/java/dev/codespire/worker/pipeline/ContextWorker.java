package dev.codespire.worker.pipeline;

import dev.codespire.contract.event.IntegrationEvent.ContextAssembled;
import dev.codespire.contract.event.IntegrationEvent.ContextContributed;
import dev.codespire.contract.event.IntegrationEvent.ContextRequested;
import dev.codespire.contract.review.ContextContribution;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.review.ContextRequest;
import dev.codespire.contract.review.ContribStatus;
import dev.codespire.contract.command.ActionCommand.GatherContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Set;

/**
 * P1 placeholder for the context pipeline: emits the fan-out signal and a
 * static RULES contribution, then assembles immediately. The real
 * ContextProvider plugins + the DB-backed completeness/timeout aggregator
 * (CONTRACT §8) land in Phase 2 — this class keeps the event shapes flowing
 * so the P2 swap is drop-in.
 */
@ApplicationScoped
public class ContextWorker {

    @Inject
    ResultsEmitter results;

    public void gatherContext(GatherContext command) {
        Set<String> expected = Set.of("RULES");
        results.emit(new ContextRequested(new ContextRequest(
                command.reviewId(), command.repo(), command.prId(), command.commit(),
                command.ticketKeys(), command.links(), expected)));
        results.emit(new ContextContributed(command.reviewId(), new ContextContribution(
                "RULES", ContribStatus.OK,
                List.of(new ContextItem("RULE", "House rule",
                        "Prefer descriptive names; flag swallowed exceptions.", null)),
                1)));
        results.emit(new ContextAssembled(command.reviewId(), command.prId(), command.commit(),
                null, expected, Set.of()));
    }
}
