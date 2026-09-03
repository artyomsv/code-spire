package dev.codespire.contract.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FR-F5: a run emits a normalized event stream, tailable live and retained for a bounded window.
 *
 * <p>This is the envelope that carries one such event between services. It is deliberately NOT the
 * harness's {@code RunEvent} sealed hierarchy, and that is ADR-034 rather than an oversight: the
 * high-volume vocabulary stays in {@code spire-harness} because putting it in the contract module
 * would imply a durability guarantee this tier does not have, and would invite a later change to
 * persist it "since it is already in the contract". What crosses the wire is a flat record with a
 * kind as a string.
 *
 * <p><b>The sequence number is the ordering, not the timestamp.</b> Two events from one agent can
 * share a millisecond, and the harness stamps them from inside the sandbox — a clock this service
 * does not control and would be wrong to sort a transcript by.
 */
class RunEventRecordTest {

    private static final String RUN_ID = "run::github:TEST-acme/app:subject-1:1";

    @Test
    void anEventCarriesItsRunItsPlaceInTheStreamAndItsKind() {
        RunEventRecord event = new RunEventRecord(RUN_ID, 7, Instant.EPOCH, "TOOL_USE",
                "ran the build", false);

        assertEquals(RUN_ID, event.runId());
        assertEquals(7, event.sequence());
        assertEquals("TOOL_USE", event.kind());
        assertEquals("ran the build", event.text());
    }

    @Test
    void aRunIdIsRequired() {
        // Every message in this system is keyed by the run it belongs to. An event with no run is
        // not a partial event, it is an event that can never be read back or routed.
        assertThrows(NullPointerException.class,
                () -> new RunEventRecord(null, 1, Instant.EPOCH, "OUTPUT", "x", false));
    }

    @Test
    void aSequenceStartsAtOneAndNeverGoesBackwards() {
        // Zero and negatives are refused rather than accepted and sorted oddly later: the sequence
        // is what orders a transcript, so a value outside the ordering is not a lesser event, it is
        // an event that cannot be placed.
        assertThrows(IllegalArgumentException.class,
                () -> new RunEventRecord(RUN_ID, 0, Instant.EPOCH, "OUTPUT", "x", false));
        assertThrows(IllegalArgumentException.class,
                () -> new RunEventRecord(RUN_ID, -1, Instant.EPOCH, "OUTPUT", "x", false));
    }

    @Test
    void aKindIsRequiredAndNeverBlank() {
        // A stream row with no kind is a row a reader cannot render and a query cannot group.
        assertThrows(IllegalArgumentException.class,
                () -> new RunEventRecord(RUN_ID, 1, Instant.EPOCH, "  ", "x", false));
        assertThrows(NullPointerException.class,
                () -> new RunEventRecord(RUN_ID, 1, Instant.EPOCH, null, "x", false));
    }

    @Test
    void theTextIsClippedRatherThanRefused() {
        // The agent writes to the same stream the harness does, at full access, so the volume is
        // influenced by whatever the model produced. A single enormous line must not be able to
        // refuse an event — which would lose the transcript — nor to arrive whole, which would put
        // an unbounded string on the bus and into a row.
        String enormous = "x".repeat(RunEventRecord.MAX_TEXT_CHARS * 3);

        RunEventRecord event = new RunEventRecord(RUN_ID, 1, Instant.EPOCH, "OUTPUT", enormous, false);

        assertTrue(event.text().length() <= RunEventRecord.MAX_TEXT_CHARS,
                "the text is " + event.text().length() + " characters");
        assertTrue(event.text().endsWith(RunEventRecord.CLIPPED),
                "a clipped line must say it was clipped, or a reader takes a truncated tool call "
                        + "for the whole of what the agent did");
    }

    @Test
    void aMissingTextIsEmptyRatherThanNull() {
        // A state change carries no text. Null would make every reader and every column nullable
        // for a case that means "nothing to show" rather than "unknown".
        RunEventRecord event = new RunEventRecord(RUN_ID, 1, Instant.EPOCH, "STATE_CHANGE", null, false);

        assertNotNull(event.text());
        assertEquals("", event.text());
    }

    @Test
    void anErrorFlagIsCarriedSoAFailedToolCallReadsAsOne() {
        // A failed tool call and a successful one are the same kind with opposite meanings, and a
        // reader scanning a long transcript for what went wrong needs the difference to be data
        // rather than something inferred from the summary text.
        RunEventRecord failed = new RunEventRecord(RUN_ID, 1, Instant.EPOCH, "TOOL_RESULT", "exit 1", true);
        RunEventRecord fine = new RunEventRecord(RUN_ID, 2, Instant.EPOCH, "TOOL_RESULT", "exit 0", false);

        assertTrue(failed.error());
        assertTrue(!fine.error());
    }
}
