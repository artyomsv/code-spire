package dev.codespire.worker.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Raw HTML in model text is escaped except where the forge renders it as code (docs/SECURITY.md, output
 * sanitization). A code region is only what a CommonMark renderer treats as one: anything else that merely looks
 * like code must stay escaped, or the forge renders the markup (review of PR 364, security/M-1).
 */
class MarkdownHtmlEscapeTest {

    private static String escape(String text) {
        return MarkdownHtmlEscape.outsideCode(text);
    }

    @Test
    void escapesHtmlInPlainText() {
        assertEquals("a &lt;b>TEST&lt;/b> c", escape("a <b>TEST</b> c"));
    }

    @Test
    void keepsACodeSpanAsWritten() {
        assertEquals("takes `List<Double>` and ``Map<K, List<V>>``", escape("takes `List<Double>` and ``Map<K, List<V>>``"));
    }

    @Test
    void aMismatchedRunIsNotCode() {
        assertEquals("``&lt;b>TEST HTML&lt;/b>`", escape("``<b>TEST HTML</b>`"));
    }

    @Test
    void anEscapedOpenerIsNotCode() {
        assertEquals("\\`&lt;b>TEST&lt;/b>`", escape("\\`<b>TEST</b>`"));
    }

    @Test
    void aCodeSpanDoesNotCrossABlankLine() {
        assertEquals("`a\n\n&lt;b>TEST&lt;/b>`", escape("`a\n\n<b>TEST</b>`"));
    }

    /** A heading interrupts a paragraph without a blank line, so no span runs across it (review of PR 364, M-1). */
    @Test
    void aHeadingEndsTheParagraph() {
        assertEquals("`a\n# &lt;b>TEST HTML&lt;/b>`", escape("`a\n# <b>TEST HTML</b>`"));
    }

    /** GitHub splits a table cell at a pipe even inside backticks, so the two halves are not code there. */
    @Test
    void aTableCellSplitsAtAPipe() {
        assertEquals("| h | i |\n|---|---|\n| `a|&lt;b>TEST&lt;/b>` |",
                escape("| h | i |\n|---|---|\n| `a|<b>TEST</b>` |"));
    }

    /**
     * Escaped pipes shorten a table cell's text while the parser keeps reporting positions in the original line, so a
     * code span's position lands earlier than the span (commonmark 0.24.0). Code positions inside a table cell are
     * not trusted: everything there is escaped (review of PR 364, M-4).
     */
    @Test
    void codeInATableCellIsEscapedLikeText() {
        assertEquals("| h |\n|---|\n| \\|\\|\\|&lt;b>`TEST CODE`&lt;/b> |",
                escape("| h |\n|---|\n| \\|\\|\\|<b>`TEST CODE`</b> |"));
    }

    @Test
    void aCodeSpanMayCrossASingleLineBreak() {
        assertEquals("`List<\nDouble>`", escape("`List<\nDouble>`"));
    }

    @Test
    void anUnclosedRunIsLiteralAndScanningGoesOn() {
        assertEquals("`` &lt;x> then `y<z>`", escape("`` <x> then `y<z>`"));
    }

    @Test
    void aFencedBlockIsCodeEvenAcrossBlankLines() {
        String fenced = "intro &lt;i>\n```java\nList<A> a;\n\nMap<B, C> b;\n```\nafter &lt;u>";
        assertEquals(fenced, escape("intro <i>\n```java\nList<A> a;\n\nMap<B, C> b;\n```\nafter <u>"));
    }

    @Test
    void anUnclosedFenceRunsToTheEnd() {
        assertEquals("x &lt;y>\n~~~\n<kept>", escape("x <y>\n~~~\n<kept>"));
    }

    @Test
    void nullIsEmpty() {
        assertEquals("", escape(null));
    }
}
