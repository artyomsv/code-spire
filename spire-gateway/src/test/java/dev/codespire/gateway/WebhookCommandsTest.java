package dev.codespire.gateway;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebhookCommandsTest {

    @Test
    void recognisesTheCommandsTheOrchestratorHandles() {
        assertEquals(Set.of("review", "finding"), WebhookCommands.SUPPORTED);
    }

    @Test
    void isImmutable() {
        // Each ingress does Set.copyOf on construction, but the shared constant is the thing three
        // endpoints hand out — a mutable one would let any of them change the others' behaviour.
        assertThrows(UnsupportedOperationException.class, () -> WebhookCommands.SUPPORTED.add("drop-table"));
    }
}
