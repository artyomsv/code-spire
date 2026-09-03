package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The codec for {@code factory_run.blocked_changes}, and the sentence an operator reads.
 *
 * <p><b>Written because the class had no test at all.</b> Two production files read it — a run's
 * detail and the attention row — and its entire documented contract, that a malformed row degrades
 * rather than throwing, was asserted nowhere. The round-trip through {@code FactoryRunProjection}
 * covers only well-formed input that this same class wrote, which proves the two halves agree with
 * each other and nothing about what happens when the column holds something else.
 *
 * <p>That matters more than it looks: V53 rewrites legacy rows, so the one input shape most likely
 * to be wrong is the one no writer here produced.
 */
class BlockedChangesTest {

    @Test
    void aRoundTripKeepsBothTheOrderAndTheKinds() {
        List<RunResult.BlockedChange> blocked = List.of(
                new RunResult.BlockedChange(".github/workflows/ci.yml", "DELETED"),
                new RunResult.BlockedChange("Jenkinsfile", "MODIFIED"));

        assertEquals(blocked, BlockedChanges.fromJson(BlockedChanges.toJson(blocked)));
    }

    /**
     * A null kind survives, because it is a value rather than a gap.
     *
     * <p>Every row V53 converts has one: the run reported a kind and the code that stored it threw
     * the value away, so null is what is actually known. Coercing it to a string here would invent a
     * fact about a run nobody can go back and ask.
     */
    @Test
    void aNullKindSurvivesTheRoundTrip() {
        List<RunResult.BlockedChange> blocked = List.of(new RunResult.BlockedChange("Jenkinsfile", null));

        String json = BlockedChanges.toJson(blocked);

        assertTrue(json.contains("\"kind\":null"),
                "the field is written even when null, so every row has the same shape and a reader "
                        + "never has to tell \"no kind\" from \"an older writer\": " + json);
        assertEquals(blocked, BlockedChanges.fromJson(json));
    }

    /**
     * Unparseable text answers empty rather than throwing — the class's stated contract.
     *
     * <p>This is read while rendering the attention panel and a run's detail page. A malformed row
     * must not take either down, because the row's own status still says the run was refused, which
     * is the fact that matters most.
     */
    @Test
    void unparseableTextIsReportedAsNoPathsRatherThanThrowing() {
        assertEquals(List.of(), BlockedChanges.fromJson("{not json"));
        assertEquals(List.of(), BlockedChanges.fromJson(""));
        assertEquals(List.of(), BlockedChanges.fromJson("   "));
        assertEquals(List.of(), BlockedChanges.fromJson(null));
    }

    /** JSON that parses but is not an array is the same answer, and for the same reason. */
    @Test
    void wellFormedJsonOfTheWrongShapeIsAlsoNoPaths() {
        assertEquals(List.of(), BlockedChanges.fromJson("{\"path\": \"ci.yml\"}"));
        assertEquals(List.of(), BlockedChanges.fromJson("\"ci.yml\""));
    }

    /**
     * An entry with no path is skipped, and the entries around it are not.
     *
     * <p>A path is the only thing that makes an entry actionable, so one without it carries nothing
     * — but discarding the whole list because of it would lose the paths that ARE readable, which is
     * the opposite of degrading gracefully.
     */
    @Test
    void anEntryWithNoPathIsSkippedWithoutLosingItsNeighbours() {
        String json = "[{\"kind\": \"ADDED\"}, {\"path\": \"Jenkinsfile\", \"kind\": \"MODIFIED\"}]";

        assertEquals(List.of(new RunResult.BlockedChange("Jenkinsfile", "MODIFIED")),
                BlockedChanges.fromJson(json));
    }

    @Test
    void describeNamesEachPathWithWhatHappenedToIt() {
        String described = BlockedChanges.describe(BlockedChanges.toJson(List.of(
                new RunResult.BlockedChange(".github/workflows/ci.yml", "DELETED"),
                new RunResult.BlockedChange("Jenkinsfile", "MODIFIED"))));

        assertEquals(".github/workflows/ci.yml (deleted), Jenkinsfile (modified)", described);
    }

    /** A wire constant should not show through into a sentence an operator reads. */
    @Test
    void describeSpellsAMultiWordKindAsWords() {
        String described = BlockedChanges.describe(
                BlockedChanges.toJson(List.of(new RunResult.BlockedChange("DOCS.md", "RENAMED_TO"))));

        assertEquals("DOCS.md (renamed to)", described);
    }

    /** An absent kind is left out entirely; {@code (null)} would read as a third kind. */
    @Test
    void describeOmitsAKindTheProducerNeverReported() {
        String described = BlockedChanges.describe(
                BlockedChanges.toJson(List.of(new RunResult.BlockedChange("Jenkinsfile", null))));

        assertEquals("Jenkinsfile", described);
    }

    /**
     * An unreadable row says so, rather than trailing off mid-sentence.
     *
     * <p>The attention row builds "The push gate refused run X: it changed &lt;this&gt;. …", so an empty
     * answer produced "it changed ." — which reads as a rendering bug and tells an operator nothing.
     */
    @Test
    void describeSaysWhenItCanReportNoPathsAtAll() {
        String described = BlockedChanges.describe("{not json");

        assertFalse(described.isBlank(),
                "an empty answer becomes \"it changed .\" in the operator's attention panel");
        assertTrue(described.contains("unreadable"), described);
    }
}
