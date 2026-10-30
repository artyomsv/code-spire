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
