package dev.codespire.orchestrator.dlq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Plain unit test (no Quarkus) — the type -> original-topic map must be deterministic. */
class DlqTopicsTest {

    @Test
    void aRunRecordReplaysOntoTheFactorysOwnTopics() {
        // Falling through to cs.commands republished a token-bearing record onto a topic whose
        // consumer cannot read it — the run was never recovered and the credential was copied.
        assertEquals("cs.run-commands", DlqTopics.forType("ExecuteRun"));
        assertEquals("cs.run-results", DlqTopics.forType("RunStarted"));
        assertEquals("cs.run-results", DlqTopics.forType("RunFinished"));
        assertEquals("cs.run-results", DlqTopics.forType("RunFailed"));
    }

    /**
     * Control replays where a listener is reading, which is no longer the work topic.
     *
     * <p>{@code CancelRun} moved when control moved. Replayed onto {@code cs.run-commands} it reaches
     * only the dispatcher, which cannot cancel anything from there — so an operator recovering a
     * dead-lettered cancel would have watched it be acknowledged and do nothing, which is the silent
     * no-op the control topic was created to remove, one topic along.
     *
     * <p>{@code SteerRun} was in no set at all and fell through to {@code cs.commands} — the review
     * worker's topic, whose deserializer cannot read it. That is the exact defect this class's own
     * javadoc records having been fixed once already.
     */
    @Test
    void controlReplaysOntoTheControlTopic() {
        assertEquals("cs.run-control", DlqTopics.forType("CancelRun"));
        assertEquals("cs.run-control", DlqTopics.forType("SteerRun"));
    }

    @Test
    void actionCommandTypeMapsToCommandsTopic() {
        assertEquals("cs.commands", DlqTopics.forType("AnswerFollowUp"));
    }

    @Test
    void resultEventTypeMapsToResultsTopic() {
        assertEquals("cs.results", DlqTopics.forType("DiffFetched"));
    }

    @Test
    void ingressEventTypeMapsToIntegrationTopic() {
        assertEquals("cs.integration", DlqTopics.forType("AuthorReplied"));
    }

    @Test
    void unknownTypeFallsBackToCommandsTopic() {
        assertEquals("cs.commands", DlqTopics.forType(""));
    }
}
