package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.command.ActionCommand.FetchDiff;
import dev.codespire.contract.command.ActionCommand.GenerateReview;
import dev.codespire.contract.lifecycle.ReviewLifecycle;
import dev.codespire.contract.lifecycle.ReviewState;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ADR-013 stale-run pre-check: a superseded/cancelled run must never reach
 * a worker (no LLM spend, no comment) — the central guard, unit-tested.
 */
class CommandDispatcherTest {

    private static final RepoRef REPO = new RepoRef("sandbox", "demo-repo");
    private static final String REVIEW_ID = "review::sandbox/demo-repo#7";

    private CommandDispatcher dispatcher;
    private PipelineTestSupport.RecordingTimeline timeline;
    private List<String> dispatched;
    private ReviewState state;

    @BeforeEach
    void setUp() {
        dispatched = new ArrayList<>();
        timeline = new PipelineTestSupport.RecordingTimeline();

        dispatcher = new CommandDispatcher();
        dispatcher.timeline = timeline;
        dispatcher.lifecycle = new ReviewLifecycleService() {
            @Override
            public ReviewState currentState(String reviewId) {
                return state;
            }
        };
        dispatcher.diffWorker = new DiffWorker() {
            @Override
            public void fetchDiff(FetchDiff command) {
                dispatched.add("fetchDiff");
            }
        };
        dispatcher.contextWorker = new ContextWorker();
        dispatcher.reviewWorker = new ReviewWorker() {
            @Override
            public void generateReview(GenerateReview command) {
                dispatched.add("generateReview");
            }
        };
    }

    private ReviewState reviewing(String commit) {
        ReviewLifecycle decider = new ReviewLifecycle();
        return decider.evolve(decider.initialState(), new DomainEvent.ReviewRequested(commit, "OPENED"));
    }

    @Test
    void freshCommandIsDispatched() {
        state = reviewing("abc123");
        dispatcher.on(new GenerateReview(REVIEW_ID, REPO, 7, "abc123", null, 1, null));
        assertEquals(List.of("generateReview"), dispatched);
    }

    @Test
    void staleCommitIsAbandonedBeforeTheWorker() {
        state = reviewing("def456"); // a newer commit superseded abc123
        dispatcher.on(new GenerateReview(REVIEW_ID, REPO, 7, "abc123", null, 1, null));
        assertTrue(dispatched.isEmpty(), "superseded run must not spend LLM tokens");
        assertTrue(timeline.entries.stream().anyMatch(e -> e.contains("abandoned:GenerateReview")));
    }

    @Test
    void cancelledRunIsAbandoned() {
        ReviewLifecycle decider = new ReviewLifecycle();
        ReviewState s = decider.evolve(decider.initialState(), new DomainEvent.ReviewRequested("abc123", "OPENED"));
        state = decider.evolve(s, new DomainEvent.ReviewCancelled("MERGED"));

        dispatcher.on(new FetchDiff(REVIEW_ID, REPO, 7, "abc123"));
        assertTrue(dispatched.isEmpty());
        assertTrue(timeline.entries.stream().anyMatch(e -> e.contains("abandoned:FetchDiff")));
    }
}
