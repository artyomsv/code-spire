package dev.codespire.diff;

import dev.codespire.contract.scm.ChangeType;
import dev.codespire.contract.scm.DiffLine;
import dev.codespire.contract.scm.FilePatch;
import dev.codespire.contract.scm.LineType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedDiffParserTest {

    private static final String MODIFIED = """
            diff --git a/src/App.java b/src/App.java
            index 1111111..2222222 100644
            --- a/src/App.java
            +++ b/src/App.java
            @@ -10,7 +10,8 @@ class App {
                 int a = 1;
            -    int b = 2;
            +    int b = 3;
            +    int c = 4;
                 int d = 5;
            @@ -30,3 +31,3 @@ class App {
                 // tail
            -    old();
            +    updated();
            """;

    @Test
    void parsesModifiedFileWithDualLineNumbers() {
        List<FilePatch> patches = UnifiedDiffParser.parse(MODIFIED);
        assertEquals(1, patches.size());
        FilePatch p = patches.getFirst();
        assertEquals("src/App.java", p.newPath());
        assertEquals("src/App.java", p.oldPath());
        assertEquals(ChangeType.MODIFIED, p.change());
        assertEquals("java", p.language());
        assertEquals(2, p.hunks().size());

        List<DiffLine> h1 = p.hunks().getFirst().lines();
        // context line: both numbers advance
        assertEquals(new DiffLine(LineType.CONTEXT, 10, 10, "    int a = 1;"), h1.get(0));
        // removed: old side only
        assertEquals(new DiffLine(LineType.REMOVED, 11, null, "    int b = 2;"), h1.get(1));
        // added: new side only — and new numbering continues where context left off
        assertEquals(new DiffLine(LineType.ADDED, null, 11, "    int b = 3;"), h1.get(2));
        assertEquals(new DiffLine(LineType.ADDED, null, 12, "    int c = 4;"), h1.get(3));
        assertEquals(new DiffLine(LineType.CONTEXT, 12, 13, "    int d = 5;"), h1.get(4));

        // second hunk starts at its own header numbers
        List<DiffLine> h2 = p.hunks().get(1).lines();
        assertEquals(new DiffLine(LineType.CONTEXT, 30, 31, "    // tail"), h2.get(0));
        assertEquals(new DiffLine(LineType.REMOVED, 31, null, "    old();"), h2.get(1));
        assertEquals(new DiffLine(LineType.ADDED, null, 32, "    updated();"), h2.get(2));
    }

    @Test
    void parsesAddedFile() {
        String diff = """
                diff --git a/docs/new.md b/docs/new.md
                new file mode 100644
                index 0000000..3333333
                --- /dev/null
                +++ b/docs/new.md
                @@ -0,0 +1,2 @@
                +# Title
                +body
                """;
        FilePatch p = UnifiedDiffParser.parse(diff).getFirst();
        assertEquals(ChangeType.ADDED, p.change());
        assertNull(p.oldPath());
        assertEquals("docs/new.md", p.newPath());
        assertEquals(new DiffLine(LineType.ADDED, null, 1, "# Title"), p.hunks().getFirst().lines().getFirst());
    }

    @Test
    void parsesDeletedFile() {
        String diff = """
                diff --git a/old.txt b/old.txt
                deleted file mode 100644
                --- a/old.txt
                +++ /dev/null
                @@ -1,2 +0,0 @@
                -line one
                -line two
                """;
        FilePatch p = UnifiedDiffParser.parse(diff).getFirst();
        assertEquals(ChangeType.DELETED, p.change());
        assertNull(p.newPath());
        assertEquals("old.txt", p.oldPath());
        assertEquals(new DiffLine(LineType.REMOVED, 2, null, "line two"), p.hunks().getFirst().lines().get(1));
    }

    @Test
    void parsesRename() {
        String diff = """
                diff --git a/a/OldName.java b/a/NewName.java
                similarity index 96%
                rename from a/OldName.java
                rename to a/NewName.java
                """;
        FilePatch p = UnifiedDiffParser.parse(diff).getFirst();
        assertEquals(ChangeType.RENAMED, p.change());
        assertEquals("a/OldName.java", p.oldPath());
        assertEquals("a/NewName.java", p.newPath());
        assertTrue(p.hunks().isEmpty());
    }

    @Test
    void flagsBinaryFiles() {
        String diff = """
                diff --git a/logo.png b/logo.png
                index 1111111..2222222 100644
                Binary files a/logo.png and b/logo.png differ
                """;
        FilePatch p = UnifiedDiffParser.parse(diff).getFirst();
        assertTrue(p.binary());
        assertTrue(p.hunks().isEmpty());
    }

    @Test
    void parsesMultipleFiles() {
        List<FilePatch> patches = UnifiedDiffParser.parse(MODIFIED + """
                diff --git a/b.txt b/b.txt
                --- a/b.txt
                +++ b/b.txt
                @@ -1 +1 @@
                -x
                +y
                """);
        assertEquals(2, patches.size());
        assertEquals("b.txt", patches.get(1).newPath());
        // header without explicit counts defaults to 1
        assertEquals(1, patches.get(1).hunks().getFirst().oldLines());
    }

    @Test
    void skipsNoNewlineMarker() {
        String diff = """
                diff --git a/x.txt b/x.txt
                --- a/x.txt
                +++ b/x.txt
                @@ -1 +1 @@
                -a
                \\ No newline at end of file
                +b
                \\ No newline at end of file
                """;
        FilePatch p = UnifiedDiffParser.parse(diff).getFirst();
        assertEquals(2, p.hunks().getFirst().lines().size());
    }

    @Test
    void emptyAndNullInputYieldNoPatches() {
        assertTrue(UnifiedDiffParser.parse(null).isEmpty());
        assertTrue(UnifiedDiffParser.parse("  ").isEmpty());
        assertTrue(UnifiedDiffParser.parse("not a diff at all").isEmpty());
    }

    @Test
    void bareEmptyLineIsContextWhileBothSidesExpectContent() {
        String diff = """
                diff --git a/x.txt b/x.txt
                --- a/x.txt
                +++ b/x.txt
                @@ -1,3 +1,3 @@
                 a

                 c
                """;
        List<DiffLine> lines = UnifiedDiffParser.parse(diff).getFirst().hunks().getFirst().lines();
        assertEquals(3, lines.size());
        assertEquals(new DiffLine(LineType.CONTEXT, 2, 2, ""), lines.get(1));
        assertEquals(new DiffLine(LineType.CONTEXT, 3, 3, "c"), lines.get(2));
    }

    @Test
    void bareEmptyLineEndsHunkWhenOneSideIsExhausted() {
        // A context line consumes one line on BOTH sides; when the old side is
        // spent, a bare empty line is noise — consuming it would drift every
        // later anchor in the hunk.
        String diff = """
                diff --git a/x.txt b/x.txt
                --- a/x.txt
                +++ b/x.txt
                @@ -1,1 +1,3 @@
                 ctx

                +a
                +b
                """;
        List<DiffLine> lines = UnifiedDiffParser.parse(diff).getFirst().hunks().getFirst().lines();
        assertEquals(1, lines.size());
        assertEquals(new DiffLine(LineType.CONTEXT, 1, 1, "ctx"), lines.getFirst());
    }

    @Test
    void absurdHunkCountsDoNotAbortTheParse() {
        // counts beyond even a long must clamp, not throw away the whole PR
        String diff = """
                diff --git a/big.txt b/big.txt
                --- a/big.txt
                +++ b/big.txt
                @@ -1,99999999999999999999999 +1,2 @@
                +a
                +b
                diff --git a/other.txt b/other.txt
                --- a/other.txt
                +++ b/other.txt
                @@ -1 +1 @@
                -x
                +y
                """;
        List<FilePatch> patches = UnifiedDiffParser.parse(diff);
        assertEquals(2, patches.size());
        assertEquals("other.txt", patches.get(1).newPath());
        assertEquals(2, patches.getFirst().hunks().getFirst().lines().size());
    }

    @Test
    void malformedHunkHeaderDropsRemainingHunksButKeepsOtherFiles() {
        String diff = """
                diff --git a/x.txt b/x.txt
                --- a/x.txt
                +++ b/x.txt
                @@ not a real header @@
                +orphan
                diff --git a/y.txt b/y.txt
                --- a/y.txt
                +++ b/y.txt
                @@ -1 +1 @@
                -x
                +y
                """;
        List<FilePatch> patches = UnifiedDiffParser.parse(diff);
        assertEquals(2, patches.size());
        assertTrue(patches.getFirst().hunks().isEmpty());
        assertEquals(2, patches.get(1).hunks().getFirst().lines().size());
    }

    @Test
    void binaryFilePathContainingSpaceBSlashParsesCorrectly() {
        // no ---/+++ lines for binary files, so the header split must not be
        // fooled by " b/" inside the path itself
        String diff = """
                diff --git a/assets/img b/logo.png b/assets/img b/logo.png
                Binary files a/assets/img b/logo.png and b/assets/img b/logo.png differ
                """;
        FilePatch p = UnifiedDiffParser.parse(diff).getFirst();
        assertTrue(p.binary());
        assertEquals("assets/img b/logo.png", p.oldPath());
        assertEquals("assets/img b/logo.png", p.newPath());
    }
}
