package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.lifecycle.ReviewLifecycle;
import dev.codespire.contract.lifecycle.ReviewState;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;

import java.util.ArrayList;
import java.util.List;

/**
 * The review aggregate held in memory: every record command goes through the real {@link ReviewLifecycle} decide table
 * and its events are folded into the state the saga reads next. So a terminal {@code RecordFailure} really ends
 * REVIEWING here, which {@code HarnessSagaFakes.lifecycle}'s fixed answer cannot show. The real service loads and
 * appends to the event store and publishes to Kafka; both of its methods are overridden (CLAUDE.md, saga test fakes).
 */
final class InMemoryLifecycle extends ReviewLifecycleService {

    private final ReviewLifecycle decider = new ReviewLifecycle();
    private final List<RecordCommand> recorded = new ArrayList<>();
    private ReviewState state = decider.initialState();

    /** A review requested on {@code commit}, so it is REVIEWING there. */
    InMemoryLifecycle(String reviewId, String commit) {
        handle(reviewId, new RecordCommand.RequestReview(commit, "TEST-trigger", false));
        recorded.clear();
    }

    @Override
    public List<DomainEvent> handle(String reviewId, RecordCommand command) {
        recorded.add(command);
        List<DomainEvent> events = decider.decide(command, state);
        for (DomainEvent event : events) {
            state = decider.evolve(state, event);
        }
        return events;
    }

    @Override
    public ReviewState currentState(String reviewId) {
        return state;
    }

    /** The record commands the saga sent, the seeding request excluded. */
    List<RecordCommand> recorded() {
        return recorded;
    }
}
