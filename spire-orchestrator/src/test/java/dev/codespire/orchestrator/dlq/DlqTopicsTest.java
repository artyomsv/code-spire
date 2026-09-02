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
        assertEquals("cs.run-commands", DlqTopics.forType("CancelRun"));
        assertEquals("cs.run-results", DlqTopics.forType("RunStarted"));
        assertEquals("cs.run-results", DlqTopics.forType("RunFinished"));
        assertEquals("cs.run-results", DlqTopics.forType("RunFailed"));
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
