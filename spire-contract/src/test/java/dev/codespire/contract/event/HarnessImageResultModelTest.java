package dev.codespire.contract.event;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A declared level is held to the rule every later hop applies. The review of PR #167 found an image
 * could declare "x-high": offered, saved, then refused when the task was prepared.
 */
class HarnessImageResultModelTest {

    @Test
    void aDeclaredLevelEveryLaterHopWouldRefuseIsRefusedHere() {
        assertThrows(IllegalArgumentException.class, () -> new HarnessImageResult.Model("TEST-model", "TEST", "medium",
                List.of("medium", "x-high"), true, 1));
        assertThrows(IllegalArgumentException.class, () -> new HarnessImageResult.Model("TEST-model", "TEST", "x-high",
                List.of("medium"), true, 1));
    }

    @Test
    void noDeclaredDefaultIsKeptAsNoneRatherThanRefused() {
        assertEquals("", new HarnessImageResult.Model("TEST-model", "TEST", "", List.of("medium"), true, 1).defaultEffort());
    }
}
