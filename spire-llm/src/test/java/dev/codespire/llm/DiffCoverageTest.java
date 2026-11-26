package dev.codespire.llm;

import dev.codespire.diff.DiffRenderer;
import dev.codespire.diff.TokenBudget;
import dev.codespire.diff.UnifiedDiffParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.codespire.llm.DiffSelectionTest.fileDiff;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which files of a line-clipped RECONCILE diff the model saw whole, and which it did not. */
class DiffCoverageTest {

    @Test
    void aCompareDiffCutInsideTheSecondFileNamesItAndEveryLaterFile() {
        String full = fileDiff("src/TEST-a.java", 10) + fileDiff("src/TEST-b.java", 500) + fileDiff("src/TEST-c.java", 10);
        String kept = full.substring(0, full.indexOf("+TEST line 250", full.indexOf("TEST-b.java")));

        DiffCoverage coverage = DiffCoverage.clipped(full, kept);

        assertEquals(List.of("src/TEST-b.java", "src/TEST-c.java"), coverage.omittedFiles());
        assertTrue(coverage.sawWhole("src/TEST-a.java"));
        assertFalse(coverage.sawWhole("src/TEST-b.java"));
        assertFalse(coverage.sawWhole("src/TEST-c.java"));
    }

    @Test
    void aClipOfACompareDiffNamesTheFilesItCut() {
        String full = fileDiff("src/TEST-a.java", 10) + fileDiff("src/TEST-b.java", 3_000);

        List<String> omitted = DiffCoverage.clipped(full, TokenBudget.clip(full, 2_000)).omittedFiles();

        assertEquals(List.of("src/TEST-b.java"), omitted, "the marker is not read as a line of the cut file");
    }

    @Test
    void theRenderedFallbackIsMeasuredExactly() {
        String full = DiffRenderer.render(UnifiedDiffParser.parse(
                fileDiff("src/TEST-a.java", 10) + fileDiff("src/TEST-b.java", 500) + fileDiff("src/TEST-c.java", 10)));
        String kept = full.substring(0, full.indexOf("## File: 'src/TEST-b.java'"));

        DiffCoverage coverage = DiffCoverage.clipped(full, kept);

        assertEquals(List.of("src/TEST-b.java", "src/TEST-c.java"), coverage.omittedFiles(),
                "the first file ends before the cut, so it was seen");
        assertTrue(coverage.sawWhole("src/TEST-a.java"));
    }

    /** A path that appears nowhere in what the parser read is unseen: coverage is what was established, not assumed. */
    @Test
    void aPathTheDiffNeverNamedIsNotSeen() {
        String full = fileDiff("src/TEST-a.java", 10) + fileDiff("src/TEST-b.java", 3_000);

        DiffCoverage coverage = DiffCoverage.clipped(full, TokenBudget.clip(full, 2_000));

        assertFalse(coverage.sawWhole("src/TEST-elsewhere.java"));
    }

    /**
     * PR 456, H-3: a rename from a file with a prior finding to a path Git C-quotes, after a large section the cut
     * fell inside. Neither the old nor the new path may count as seen.
     */
    @Test
    void aQuotedRenameBeyondTheCutIsNotSeenUnderEitherPath() {
        String full = fileDiff("src/TEST-big.java", 3_000) + QUOTED_RENAME;

        DiffCoverage coverage = DiffCoverage.clipped(full, TokenBudget.clip(full, 2_000));

        assertTrue(coverage.isClipped());
        assertFalse(coverage.sawWhole("src/A.java"), "the old path");
        assertFalse(coverage.sawWhole("src/TEST-" + E_ACUTE + ".java"), "the decoded new path");
        assertTrue(coverage.omittedFiles().contains("src/TEST-" + E_ACUTE + ".java"), coverage.omittedFiles().toString());
    }

    /** A section no parser can read is not named and not seen: the omitted list may be short, the judgement not. */
    @Test
    void anUnreadableSectionIsNeverSeen() {
        String full = fileDiff("src/TEST-a.java", 10) + "diff --git \"a/unterminated b/x\n+junk\n"
                + fileDiff("src/TEST-b.java", 3_000);

        DiffCoverage coverage = DiffCoverage.clipped(full, TokenBudget.clip(full, 2_000));

        assertFalse(coverage.sawWhole("unterminated"));
        assertFalse(coverage.sawWhole("src/TEST-b.java"));
    }

    @Test
    void anUnclippedDiffSawEverything() {
        assertFalse(DiffCoverage.whole().isClipped());
        assertTrue(DiffCoverage.whole().sawWhole("src/TEST-any.java"));
        assertTrue(DiffCoverage.whole().omittedFiles().isEmpty());
    }

    private static final String E_ACUTE = String.valueOf((char) 0xE9);

    static final String QUOTED_RENAME = """
            diff --git a/src/A.java "b/src/TEST-\\303\\251.java"
            similarity index 90%
            rename from src/A.java
            rename to "src/TEST-\\303\\251.java"
            --- a/src/A.java
            +++ "b/src/TEST-\\303\\251.java"
            @@ -5,1 +5,1 @@
            -leak();
            +close();
            """;
}
