package dev.codespire.orchestrator.dlq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Plain unit test (no Quarkus) — the type -> original-topic map must be deterministic. */
class DlqTopicsTest {

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
