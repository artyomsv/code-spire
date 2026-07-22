package dev.codespire.diff;

import dev.codespire.contract.scm.FilePatch;
import dev.codespire.contract.scm.InlineAnchor;
import dev.codespire.contract.scm.Side;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnchorsTest {

    private static final String DIFF = """
            diff --git a/src/App.java b/src/App.java
            --- a/src/App.java
            +++ b/src/App.java
            @@ -10,3 +10,3 @@
             keep
            -old
            +new line
            """;

    @Test
    void resolvesAddedLineOnNewSide() {
        Optional<InlineAnchor> anchor = Anchors.resolveNewLine(
                UnifiedDiffParser.parse(DIFF), "src/App.java", 11);
        assertTrue(anchor.isPresent());
        assertEquals(Side.NEW, anchor.get().side());
        assertEquals(11, anchor.get().newLine());
        assertEquals("src/App.java", anchor.get().srcPath());
    }

    @Test
    void resolvesContextLineWithBothNumbers() {
        InlineAnchor anchor = Anchors.resolveNewLine(
                UnifiedDiffParser.parse(DIFF), "src/App.java", 10).orElseThrow();
        assertEquals(10, anchor.oldLine());
        assertEquals(10, anchor.newLine());
    }

    @Test
    void unknownPathOrLineYieldsEmpty() {
        var patches = UnifiedDiffParser.parse(DIFF);
        assertTrue(Anchors.resolveNewLine(patches, "src/Other.java", 11).isEmpty());
        assertTrue(Anchors.resolveNewLine(patches, "src/App.java", 999).isEmpty());
    }

    // --- OLD-side and multi-line range resolution (Task 6) ---

    private static final String PURE_DELETION = """
            diff --git a/a.java b/a.java
            --- a/a.java
            +++ b/a.java
            @@ -6,2 +6,1 @@
             line6
            -line7
            """;

    @Test
    void resolvesARemovedLineOnTheOldSide() {
        List<FilePatch> patches = UnifiedDiffParser.parse(PURE_DELETION);
        Optional<InlineAnchor> anchor = Anchors.resolveLine(patches, "a.java", 7, 7);
        assertTrue(anchor.isPresent());
        assertEquals(Side.OLD, anchor.get().side());
        assertEquals(7, anchor.get().oldLine());
        assertNull(anchor.get().endNewLine());
    }

    private static final String MULTI_LINE_ADDITION = """
            diff --git a/a.java b/a.java
            --- a/a.java
            +++ b/a.java
            @@ -4,1 +4,5 @@
             keep4
            +added5
            +added6
            +added7
            +added8
            """;

    @Test
    void resolvesAMultiLineRangeWithinOneHunk() {
        List<FilePatch> patches = UnifiedDiffParser.parse(MULTI_LINE_ADDITION);
        InlineAnchor anchor = Anchors.resolveLine(patches, "a.java", 5, 7).orElseThrow();
        assertEquals(5, anchor.newLine());
        assertEquals(7, anchor.endNewLine());
        assertEquals("a.java:5:NEW", anchor.anchorKey());
    }

    @Test
    void rangeEndOutsideTheHunkDegradesToSingleLine() {
        List<FilePatch> patches = UnifiedDiffParser.parse(MULTI_LINE_ADDITION);
        InlineAnchor anchor = Anchors.resolveLine(patches, "a.java", 5, 99).orElseThrow();
        assertNull(anchor.endNewLine());
    }

    private static final String TWO_HUNKS = """
            diff --git a/a.java b/a.java
            --- a/a.java
            +++ b/a.java
            @@ -1,1 +1,2 @@
             keep1
            +added2
            @@ -5,1 +6,2 @@
             keep6
            +added7
            """;

    @Test
    void rangeEndInADifferentHunkDegradesToSingleLine() {
        // startLine (2) resolves in hunk 1; endLine (7) only exists in hunk 2 of the SAME file —
        // distinct from "end absent anywhere" (rangeEndOutsideTheHunkDegradesToSingleLine): this
        // proves the end must resolve in the SAME hunk as the start, not merely somewhere in the file.
        List<FilePatch> patches = UnifiedDiffParser.parse(TWO_HUNKS);
        InlineAnchor anchor = Anchors.resolveLine(patches, "a.java", 2, 7).orElseThrow();
        assertEquals(2, anchor.newLine());
        assertNull(anchor.endNewLine());
    }

    private static final String MODIFIED_LINE = """
            diff --git a/a.java b/a.java
            --- a/a.java
            +++ b/a.java
            @@ -1,3 +1,3 @@
             keep1
             keep2
            -old3
            +new3
            """;

    @Test
    void newSideStillWinsOverOldSide() {
        List<FilePatch> patches = UnifiedDiffParser.parse(MODIFIED_LINE);
        assertEquals(Side.NEW, Anchors.resolveLine(patches, "a.java", 3, 3).orElseThrow().side());
    }
}
