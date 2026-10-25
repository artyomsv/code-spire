package dev.codespire.contract.event;

import dev.codespire.contract.work.WorkDraftBinding;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The artifact a read-only run hands back: bounded, never blank, and an empty usage map is not a measurement. */
class RunWorkArtifactTest {

    private static final String RUN_ID = "run::github:TEST-work/app:TEST-draft:1";

    private static final WorkDraftBinding DRAFT = new WorkDraftBinding("TEST-item", 1,
            UUID.fromString("00000000-0000-4000-8000-000000000001"), WorkDraftBinding.Kind.PLAN, "d".repeat(64));

    private static RunResult.RunWorkArtifact artifact(String text, Map<String, Long> usage) {
        return new RunResult.RunWorkArtifact(RUN_ID, DRAFT, text, false, usage, 3);
    }

    @Test
    void anOverCapTextIsRefused() {
        assertDoesNotThrow(() -> artifact("x".repeat(32 * 1024), null), "the cap itself is allowed");
        assertThrows(IllegalArgumentException.class, () -> artifact("x".repeat(32 * 1024 + 1), null));
        // The cap is in UTF-8 bytes, as the publisher measures it: 16,385 two-byte characters are over it.
        assertThrows(IllegalArgumentException.class, () -> artifact("é".repeat(16 * 1024 + 1), null));
    }

    @Test
    void aBlankTextIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> artifact(" \n", null));
        assertThrows(IllegalArgumentException.class, () -> artifact(null, null));
    }

    @Test
    void anEmptyUsageMapIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> artifact("TEST-spec", Map.of()));
    }

    @Test
    void anArtifactNamesItsRunAndDraftAndANonNegativeWall() {
        assertThrows(IllegalArgumentException.class,
                () -> new RunResult.RunWorkArtifact(" ", DRAFT, "TEST-spec", false, null, 3));
        assertThrows(NullPointerException.class,
                () -> new RunResult.RunWorkArtifact(RUN_ID, null, "TEST-spec", false, null, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new RunResult.RunWorkArtifact(RUN_ID, DRAFT, "TEST-spec", false, null, -1));
    }

    @Test
    void withUsageEnumeratesEveryComponent() {
        RunResult.RunWorkArtifact original = new RunResult.RunWorkArtifact(RUN_ID, DRAFT, "TEST-spec", true, null, 7);

        RunResult.RunWorkArtifact measured = original.withUsage(Map.of("INPUT", 11L));

        assertEquals(new RunResult.RunWorkArtifact(RUN_ID, DRAFT, "TEST-spec", true, Map.of("INPUT", 11L), 7), measured);
    }
}
