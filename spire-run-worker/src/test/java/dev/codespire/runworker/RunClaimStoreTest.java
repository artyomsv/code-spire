package dev.codespire.runworker;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class RunClaimStoreTest {

    @Inject
    RunClaimStore claims;

    @Test
    void aSecondClaimOnTheSameSlotIsRefused() {
        String runId = "run::github:acme/app:finding-1:1";

        assertTrue(claims.claim(runId, "execute"), "the first delivery does the work");
        assertFalse(claims.claim(runId, "execute"), "a redelivery must not run the agent twice");
    }

    @Test
    void aDifferentAttemptIsADifferentRunAndClaimsFreely() {
        assertTrue(claims.claim("run::github:acme/app:finding-2:1", "execute"));
        assertTrue(claims.claim("run::github:acme/app:finding-2:2", "execute"),
                "attempt 2 is a genuine second run, not a redelivery");
    }

    @Test
    void slotsAreIndependentWithinOneRun() {
        // The run id alone is not the key. A run takes several claims over its life, and one
        // taken for execution must not silently consume the one a later step needs.
        String runId = "run::github:acme/app:finding-3:1";

        assertTrue(claims.claim(runId, "execute"));
        assertTrue(claims.claim(runId, "salvage"));
        assertFalse(claims.claim(runId, "execute"));
    }
}
