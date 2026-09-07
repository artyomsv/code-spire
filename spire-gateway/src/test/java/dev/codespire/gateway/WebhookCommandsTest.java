package dev.codespire.gateway;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebhookCommandsTest {

    /**
     * The exact set, not a containment check. A command the orchestrator does not handle would be
     * translated into a {@code ManualCommandReceived} that reaches the saga's {@code default} branch
     * and is logged as "no handler" — which reads to an operator exactly like a lost webhook.
     */
    @Test
    void recognisesTheCommandsTheOrchestratorHandles() {
        assertEquals(Set.of("review", "finding", "fix"), WebhookCommands.SUPPORTED);
    }

    @Test
    void isImmutable() {
        // Each ingress does Set.copyOf on construction, but the shared constant is the thing three
        // endpoints hand out — a mutable one would let any of them change the others' behaviour.
        assertThrows(UnsupportedOperationException.class, () -> WebhookCommands.SUPPORTED.add("drop-table"));
    }
}
