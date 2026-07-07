package dev.codespire.worker.pipeline;

import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.ContextAssembled;
import dev.codespire.contract.event.IntegrationEvent.ContextContributed;
import dev.codespire.contract.event.IntegrationEvent.ContextRequested;
import dev.codespire.contract.command.ActionCommand.GatherContext;
import dev.codespire.contract.review.ContribStatus;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ContextWorker unit tests (mirrors the DiffWorkerTest harness): the P1
 * placeholder emits Requested -> Contributed(RULES) -> Assembled with a
 * consistent expected/contributing source set, and an emit failure (the
 * ResultsEmitter throws after a broker nack/timeout) propagates so the
 * incoming command is nacked to cs.dlq instead of half-completing silently.
 */
class ContextWorkerTest {

    private static final RepoRef REPO = new RepoRef("sandbox", "demo-repo");
    private static final GatherContext COMMAND = new GatherContext(
            "review::sandbox/demo-repo#7", REPO, 7, "abc123", Set.of("CS-42"), List.of("https://issue/42"));

    private ContextWorker worker;
    private List<IntegrationEvent> emitted;
    private RuntimeException failAfter; // thrown by the emitter AFTER recording, when set
    private int failOnEmitNumber;

    @BeforeEach
    void setUp() {
        emitted = new ArrayList<>();
        failAfter = null;
        failOnEmitNumber = -1;
        worker = new ContextWorker();
        worker.results = new ResultsEmitter() {
            @Override
            public void emit(IntegrationEvent event) {
                emitted.add(event);
                if (failAfter != null && emitted.size() == failOnEmitNumber) {
                    throw failAfter;
                }
            }
        };
    }

    @Test
    void happyPathEmitsRequestedContributedAssembledInOrder() {
        worker.gatherContext(COMMAND);
        assertEquals(3, emitted.size());
        assertInstanceOf(ContextRequested.class, emitted.get(0));
        assertInstanceOf(ContextContributed.class, emitted.get(1));
        assertInstanceOf(ContextAssembled.class, emitted.get(2));
    }

    @Test
    void requestedCarriesTheCommandCoordinatesAndTheExpectedSources() {
        worker.gatherContext(COMMAND);
        ContextRequested requested = assertInstanceOf(ContextRequested.class, emitted.get(0));
        assertEquals(COMMAND.reviewId(), requested.request().reviewId());
        assertEquals(REPO, requested.request().repo());
        assertEquals(7, requested.request().prId());
        assertEquals("abc123", requested.request().commit());
        assertEquals(Set.of("CS-42"), requested.request().ticketKeys());
        assertEquals(List.of("https://issue/42"), requested.request().links());
        assertEquals(Set.of("RULES"), requested.request().expectedSources());
    }

    @Test
    void contributionIsTheStaticRulesSourceWithContent() {
        worker.gatherContext(COMMAND);
        ContextContributed contributed = assertInstanceOf(ContextContributed.class, emitted.get(1));
        assertEquals(COMMAND.reviewId(), contributed.reviewId());
        assertEquals("RULES", contributed.contribution().source());
        assertEquals(ContribStatus.OK, contributed.contribution().status());
        assertFalse(contributed.contribution().items().isEmpty(), "the placeholder rule must carry an item");
        assertEquals("RULE", contributed.contribution().items().getFirst().kind());
    }

    @Test
    void assembledCoversEveryExpectedSourceWithNothingMissing() {
        worker.gatherContext(COMMAND);
        ContextAssembled assembled = assertInstanceOf(ContextAssembled.class, emitted.get(2));
        assertEquals(COMMAND.reviewId(), assembled.reviewId());
        assertEquals(7, assembled.prId());
        assertEquals("abc123", assembled.commit());
        assertEquals(Set.of("RULES"), assembled.contributingSources());
        assertEquals(Set.of(), assembled.missingSources(),
                "the P1 placeholder assembles immediately — nothing may be reported missing");
    }

    @Test
    void emitFailureOnTheFirstEventPropagates() {
        failAfter = new CompletionException(new IllegalStateException("broker nack"));
        failOnEmitNumber = 1;
        assertThrows(CompletionException.class, () -> worker.gatherContext(COMMAND),
                "a nacked publish must fail the @Incoming handler so the command lands on cs.dlq");
        assertEquals(1, emitted.size(), "nothing after the failed emit");
    }

    @Test
    void emitFailureMidwayStopsTheRemainingEmits() {
        failAfter = new CompletionException(new IllegalStateException("broker nack"));
        failOnEmitNumber = 2;
        assertThrows(CompletionException.class, () -> worker.gatherContext(COMMAND));
        assertEquals(2, emitted.size(), "the Assembled event must not be emitted after a failed publish");
        assertTrue(emitted.get(1) instanceof ContextContributed);
    }
}
