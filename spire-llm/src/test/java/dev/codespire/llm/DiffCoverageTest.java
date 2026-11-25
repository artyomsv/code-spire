package dev.codespire.llm;

import dev.codespire.diff.DiffRenderer;
import dev.codespire.diff.TokenBudget;
import dev.codespire.diff.UnifiedDiffParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.codespire.llm.DiffSelectionTest.fileDiff;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which files a line-clipped RECONCILE diff leaves the model not fully seeing. */
class DiffCoverageTest {

    @Test
    void aCompareDiffCutInsideTheSecondFileNamesItAndEveryLaterFile() {
        String full = fileDiff("src/TEST-a.java", 10) + fileDiff("src/TEST-b.java", 500) + fileDiff("src/TEST-c.java", 10);
        String kept = full.substring(0, full.indexOf("+TEST line 250", full.indexOf("TEST-b.java")));

        assertEquals(List.of("src/TEST-b.java", "src/TEST-c.java"), DiffCoverage.omittedPaths(full, kept));
    }

    @Test
    void aClipOfACompareDiffNamesTheFilesItCut() {
        String full = fileDiff("src/TEST-a.java", 10) + fileDiff("src/TEST-b.java", 3_000);

        List<String> omitted = DiffCoverage.omittedPaths(full, TokenBudget.clip(full, 2_000));

        assertEquals(List.of("src/TEST-b.java"), omitted, "the marker is not read as a line of the cut file");
    }

    @Test
    void theRenderedFallbackIsMeasuredExactly() {
        String full = DiffRenderer.render(UnifiedDiffParser.parse(
                fileDiff("src/TEST-a.java", 10) + fileDiff("src/TEST-b.java", 500) + fileDiff("src/TEST-c.java", 10)));
        String kept = full.substring(0, full.indexOf("## File: 'src/TEST-b.java'"));

        assertEquals(List.of("src/TEST-b.java", "src/TEST-c.java"), DiffCoverage.omittedPaths(full, kept),
                "the first file ends before the cut, so it was seen");
    }
}
