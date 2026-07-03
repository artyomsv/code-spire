package dev.codespire.diff;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffRendererTest {

    @Test
    void rendersNumberedNewAndOldHunks() {
        String diff = """
                diff --git a/src/App.java b/src/App.java
                --- a/src/App.java
                +++ b/src/App.java
                @@ -10,3 +10,3 @@
                 keep
                -removed line
                +added line
                """;
        String rendered = DiffRenderer.render(UnifiedDiffParser.parse(diff));

        assertTrue(rendered.contains("## File: 'src/App.java' (MODIFIED)"));
        assertTrue(rendered.contains("__new hunk__"));
        // new-side numbering the model cites back for inline anchors
        assertTrue(rendered.contains("10  keep"));
        assertTrue(rendered.contains("11 +added line"));
        assertTrue(rendered.contains("__old hunk__"));
        assertTrue(rendered.contains("11 -removed line"));
    }

    @Test
    void skipsBinaryFiles() {
        String diff = """
                diff --git a/logo.png b/logo.png
                Binary files a/logo.png and b/logo.png differ
                """;
        String rendered = DiffRenderer.render(UnifiedDiffParser.parse(diff));
        assertFalse(rendered.contains("logo.png"));
    }
}
