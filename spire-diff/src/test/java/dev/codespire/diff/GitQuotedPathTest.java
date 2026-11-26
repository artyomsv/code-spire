package dev.codespire.diff;

import dev.codespire.contract.scm.ChangeType;
import dev.codespire.contract.scm.FilePatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Git C-quotes a path holding a non-ASCII byte, a quote, a backslash or a control character. The parser dropped such
 * a file section silently, which let a clipped reconcile pass a renamed file's finding as seen (PR 456, H-3).
 */
class GitQuotedPathTest {

    /** The character Git writes as two octal escapes (its UTF-8 bytes), kept out of the source text's encoding. */
    private static final String E_ACUTE = String.valueOf((char) 0xE9);

    @Test
    void octalEscapesDecodeAsUtf8() {
        assertEquals("src/TEST-caf" + E_ACUTE + ".java", GitQuotedPath.unquoteIfQuoted("\"src/TEST-caf\\303\\251.java\""));
    }

    @Test
    void simpleEscapesDecode() {
        assertEquals("src/TEST \"q\"\t\\.java", GitQuotedPath.unquoteIfQuoted("\"src/TEST \\\"q\\\"\\t\\\\.java\""));
    }

    @Test
    void aLiteralSupplementaryCharacterSurvivesBesideAnEscape() {
        String emoji = new String(Character.toChars(0x1F600));
        assertEquals("src/TEST-" + emoji + "\t.java", GitQuotedPath.unquoteIfQuoted("\"src/TEST-" + emoji + "\\t.java\""));
    }

    @Test
    void anUnquotedPathIsLeftAsWritten() {
        assertEquals("src/TEST a.java", GitQuotedPath.unquoteIfQuoted("src/TEST a.java"));
    }

    @Test
    void anUnterminatedOrMalformedQuoteIsNotAPath() {
        assertNull(GitQuotedPath.unquoteIfQuoted("\"src/TEST.java"));
        assertNull(GitQuotedPath.unquoteIfQuoted("\"src/TEST\\q.java\""));
        assertNull(GitQuotedPath.unquoteIfQuoted("\"src/TEST.java\" trailing"));
    }

    @Test
    void aHeaderWithBothPathsQuotedSplits() {
        assertArrayEquals(new String[]{"src/TEST-" + E_ACUTE + ".java", "src/TEST-" + E_ACUTE + ".java"},
                GitQuotedPath.splitQuotedPair("\"a/src/TEST-\\303\\251.java\" \"b/src/TEST-\\303\\251.java\""));
    }

    @Test
    void aHeaderWithOnlyTheNewPathQuotedSplits() {
        assertArrayEquals(new String[]{"src/TEST old.java", "src/TEST-" + E_ACUTE + ".java"},
                GitQuotedPath.splitQuotedPair("a/src/TEST old.java \"b/src/TEST-\\303\\251.java\""));
    }

    @Test
    void aQuotedRenameIsParsedWithBothPaths() {
        String diff = """
                diff --git a/src/A.java "b/src/TEST-\\303\\251.java"
                similarity index 90%
                rename from src/A.java
                rename to "src/TEST-\\303\\251.java"
                --- a/src/A.java
                +++ "b/src/TEST-\\303\\251.java"
                @@ -1,1 +1,1 @@
                -old
                +new
                """;

        List<FilePatch> patches = UnifiedDiffParser.parse(diff);

        assertEquals(1, patches.size(), "the quoted section is read, not dropped");
        assertEquals("src/A.java", patches.getFirst().oldPath());
        assertEquals("src/TEST-" + E_ACUTE + ".java", patches.getFirst().newPath());
        assertEquals(ChangeType.RENAMED, patches.getFirst().change());
    }
}
