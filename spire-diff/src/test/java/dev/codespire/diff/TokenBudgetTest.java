package dev.codespire.diff;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenBudgetTest {

    @Test
    void estimatesRoughTokenCount() {
        assertEquals(0, TokenBudget.estimateTokens(null));
        assertEquals(0, TokenBudget.estimateTokens(""));
        assertTrue(TokenBudget.estimateTokens("a".repeat(320)) == 100);
    }

    @Test
    void underBudgetTextIsUntouched() {
        String text = "short text";
        assertEquals(text, TokenBudget.clip(text, 1000));
    }

    @Test
    void overBudgetTextIsClippedWithMarker() {
        String text = "x".repeat(10_000);
        String clipped = TokenBudget.clip(text, 100);
        assertTrue(clipped.length() < text.length());
        assertTrue(clipped.endsWith("...(truncated to fit the model context)"));
        // safety factor keeps us under budget
        assertTrue(TokenBudget.estimateTokens(clipped) <= 110);
    }

    @Test
    void zeroBudgetYieldsEmpty() {
        assertEquals("", TokenBudget.clip("anything", 0));
    }

    @Test
    void clipsAtLineBoundaryNotMidLine() {
        String text = "142 +some particularly long line of code with content\n".repeat(100);
        String clipped = TokenBudget.clip(text, 200);

        String marker = "\n...(truncated to fit the model context)";
        assertTrue(clipped.endsWith(marker));
        String content = clipped.substring(0, clipped.length() - marker.length());
        // the kept content ends exactly at a line boundary of the input — no dangling fragment
        assertTrue(text.startsWith(content + "\n"));
        assertTrue(content.endsWith("long line of code with content"));
    }

    @Test
    void fallsBackToRawCutWhenTextHasNoNewline() {
        String clipped = TokenBudget.clip("y".repeat(10_000), 100);
        assertTrue(clipped.endsWith("...(truncated to fit the model context)"));
        assertTrue(clipped.length() < 10_000);
    }

    @Test
    void truncationMarkerCountsAgainstTheBudget() {
        // the ~13-token marker must not push a small budget over the limit
        String clipped = TokenBudget.clip("x".repeat(1_000), 14);
        assertTrue(clipped.endsWith("...(truncated to fit the model context)"));
        assertTrue(TokenBudget.estimateTokens(clipped) <= 14);
    }
}
