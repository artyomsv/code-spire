package dev.codespire.harness;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunEventSummaryTest {

    private static final Instant AT = Instant.parse("2026-09-01T12:00:00Z");

    @Test
    void ofDetectsAnOutputEvent() {
        // sawAnyOutput is what CodexAdapter.classify reads to tell "the harness crashed before it
        // said anything" (NO_MODEL_RESPONSE) from "it worked and then failed" (HARNESS_EXIT_NONZERO).
        RunEventSummary summary = RunEventSummary.of(List.of(
                new RunEvent.Thinking(AT, "considering"),
                new RunEvent.Output(AT, "done")));

        assertTrue(summary.sawAnyOutput());
    }

    @Test
    void ofFindsNoOutputWhenThereIsNone() {
        // Thinking and ToolUse are not output. A run that only ever reasoned produced no answer.
        RunEventSummary summary = RunEventSummary.of(List.of(
                new RunEvent.Thinking(AT, "considering"),
                new RunEvent.ToolUse(AT, "bash", "ls -la")));

        assertFalse(summary.sawAnyOutput());
    }

    @Test
    void ofOnAnEmptyRunSawNothing() {
        assertFalse(RunEventSummary.of(List.of()).sawAnyOutput());
    }

    @Test
    void doesNotAliasTheCallersList() {
        // The worker accumulates events into a mutable list as it reads the child's stdout, then
        // hands it here. If the summary were a view, classification would race the reader.
        List<RunEvent> mutable = new ArrayList<>();
        mutable.add(new RunEvent.Output(AT, "first"));

        RunEventSummary summary = new RunEventSummary(mutable, true);
        mutable.add(new RunEvent.Output(AT, "second"));

        assertEquals(1, summary.events().size(), "the summary must be a snapshot, not a view");
    }

    @Test
    void theEventListIsNeverNull() {
        assertThrows(NullPointerException.class, () -> new RunEventSummary(null, false));
    }
}
