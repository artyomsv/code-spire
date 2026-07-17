package dev.codespire.orchestrator.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pure-logic coverage for the ADR-011 conversation-turn preview helper. */
class PreviewsTest {

    @Test
    void nullTextYieldsEmptyString() {
        assertEquals("", Previews.of(null));
    }

    @Test
    void collapsesNewlinesAndRepeatedWhitespaceToASingleSpace() {
        assertEquals("line one line two", Previews.of("line one\n\nline   two"));
        assertEquals("padded", Previews.of("  padded  \n"));
    }

    @Test
    void truncatesTextLongerThan160CharsWithATrailingEllipsis() {
        String longText = "a".repeat(200);
        String preview = Previews.of(longText);
        assertEquals(161, preview.length());
        assertEquals("a".repeat(160) + "…", preview);
    }

    @Test
    void shortTextIsReturnedUnchanged() {
        assertEquals("looks good, thanks!", Previews.of("looks good, thanks!"));
    }
}
