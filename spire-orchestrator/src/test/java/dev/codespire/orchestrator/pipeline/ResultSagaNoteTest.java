package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.LineRange;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The single note field a review row carries, and what it must say about each outcome.
 *
 * <p>The case that matters most is the last one. {@code degraded} is written on every outcome so the
 * attention row can clear, but the note originally was not: a round-2 run that parsed cleanly left
 * round 1's "this run reviewed nothing" text on a row whose flag was now false, whose findings were
 * populated, and which raised no row at all. The two halves of the same fact disagreed, and the note
 * is the half an operator actually reads.
 */
class ResultSagaNoteTest {

    private static final ModelUsage USAGE = ModelUsage.of("TEST-MODEL", 1, 1);

    private static ReviewResult clean() {
        return new ReviewResult(List.of(), "nothing to report", USAGE);
    }

    /** Parsed, but the provider stopped at its output limit: some findings exist, an unknown number do not. */
    private static ReviewResult cutOffPartWay() {
        return new ReviewResult(withFindings().findings(), "partial", USAGE, false, true);
    }

    private static ReviewResult withFindings() {
        return new ReviewResult(
                List.of(new Finding("src/A.java", new LineRange(1, 1), Severity.MAJOR, "TEST finding", null)),
                "one finding", USAGE);
    }

    @Test
    void aCleanRunCarriesNoNote() {
        assertNull(ResultSaga.noteFor(clean()));
    }

    @Test
    void aRunThatProducedNothingSaysSo() {
        String note = ResultSaga.noteFor(ReviewResult.degraded("no output", USAGE));
        assertNotNull(note);
        assertTrue(note.contains("reviewed nothing"), note);
        assertTrue(note.contains("charged"), "the operator paid for it and needs telling: " + note);
    }

    @Test
    void aRunCutOffPartWaySaysPartialRatherThanEmpty() {
        // A different fact from "nothing came back", and it needs a different sentence: telling an
        // operator their review is empty when it is merely partial sends them after the wrong thing.
        String partial = ResultSaga.noteFor(cutOffPartWay());
        assertNotNull(partial);
        assertTrue(partial.contains("partial"), partial);
        assertFalse(partial.contains("reviewed nothing"),
                "a run that produced findings did not review nothing: " + partial);
    }

    @Test
    void aClippedDiffKeepsItsOwnNote() {
        String note = ResultSaga.noteFor(clean().withTruncated(true));
        assertNotNull(note);
        assertTrue(note.contains("Diff exceeded"), note);
    }

    @Test
    void degradedOutranksTruncatedWhenBothAreTrue() {
        // One field, so the more severe fact wins: a run that produced nothing has already told the
        // operator more than "part of the diff went unreviewed" would.
        String note = ResultSaga.noteFor(ReviewResult.degraded("no output", USAGE).withTruncated(true));
        assertTrue(note.contains("reviewed nothing"), note);
    }

    @Test
    void aLaterCleanRunClearsTheNoteRatherThanLeavingTheOldOne() {
        // Null is what CLEARS the column. Without it, round 1's alarming text outlives the run it
        // described and contradicts every other field on the row.
        assertNull(ResultSaga.noteFor(clean()));
        assertNull(ResultSaga.noteFor(withFindings()));
    }

    @Test
    void everyNoteTellsTheOperatorWhatToDo() {
        // A note that states a problem without a next step is the kind an operator learns to skip.
        for (ReviewResult result : List.of(
                ReviewResult.degraded("no output", USAGE),
                cutOffPartWay())) {
            String note = ResultSaga.noteFor(result);
            assertTrue(note.contains("Re-run"), note);
            assertTrue(note.contains("output cap"), note);
        }
        assertTrue(ResultSaga.noteFor(clean().withTruncated(true)).contains("not reviewed"));
    }
}
