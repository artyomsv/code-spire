package dev.codespire.diff;

import dev.codespire.contract.scm.InlineAnchor;
import dev.codespire.contract.scm.Side;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
