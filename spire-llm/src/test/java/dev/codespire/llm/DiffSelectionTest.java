package dev.codespire.llm;

import dev.codespire.contract.llm.PromptClipping;
import dev.codespire.contract.scm.FilePatch;
import dev.codespire.diff.DiffRenderer;
import dev.codespire.diff.TokenBudget;
import dev.codespire.diff.UnifiedDiffParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A REVIEW diff cut at file boundaries: what goes in, what is skipped, and what is named. */
class DiffSelectionTest {

    /** A git-style diff of one added file of {@code lines} lines. */
    static String fileDiff(String path, int lines) {
        StringBuilder diff = new StringBuilder("diff --git a/" + path + " b/" + path + "\n--- a/" + path
                + "\n+++ b/" + path + "\n@@ -1,1 +1," + lines + " @@\n");
        for (int i = 0; i < lines; i++) {
            diff.append("+TEST line ").append(i).append('\n');
        }
        return diff.toString();
    }

    private static List<FilePatch> patches(String... diffs) {
        return UnifiedDiffParser.parse(String.join("", diffs));
    }

    @Test
    void everythingThatFitsGoesInWhole() {
        List<FilePatch> patches = patches(fileDiff("src/TEST-A.java", 10), fileDiff("src/TEST-B.java", 10));

        DiffSelection selection = DiffSelection.byFile(patches, 24_000);

        assertEquals(DiffRenderer.render(patches), selection.text());
        assertTrue(selection.omittedFiles().isEmpty());
    }

    @Test
    void anOversizedFileIsSkippedAndLaterFilesStillGoIn() {
        List<FilePatch> patches = patches(fileDiff("src/TEST-big.lock", 2_000), fileDiff("src/TEST-small.java", 10));

        DiffSelection selection = DiffSelection.byFile(patches, 1_000);

        assertEquals(List.of("src/TEST-big.lock"), selection.omittedFiles());
        assertTrue(selection.text().contains("## File: 'src/TEST-small.java'"), "the later file is reviewed");
        assertFalse(selection.text().contains("TEST-big.lock"), "the skipped file is not sent in part");
        assertTrue(TokenBudget.estimateTokens(selection.text()) <= 1_000);
    }

    @Test
    void whenNoWholeFileFitsTheFirstIsClippedInsideAndStillNamed() {
        List<FilePatch> patches = patches(fileDiff("src/TEST-first.java", 2_000), fileDiff("src/TEST-second.java", 2_000));

        DiffSelection selection = DiffSelection.byFile(patches, 1_000);

        assertTrue(selection.text().startsWith("## File: 'src/TEST-first.java'"), selection.text().substring(0, 60));
        assertTrue(selection.text().endsWith(PromptClipping.TRUNCATION_MARKER));
        assertEquals(List.of("src/TEST-first.java", "src/TEST-second.java"), selection.omittedFiles(),
                "a file the model saw only the start of is named too");
    }

    @Test
    void filesKeepTheirOrder() {
        List<FilePatch> patches = patches(fileDiff("src/TEST-1.java", 10), fileDiff("src/TEST-big.lock", 2_000),
                fileDiff("src/TEST-2.java", 10));

        String text = DiffSelection.byFile(patches, 1_000).text();

        assertTrue(text.indexOf("TEST-1.java") < text.indexOf("TEST-2.java"));
    }
}
