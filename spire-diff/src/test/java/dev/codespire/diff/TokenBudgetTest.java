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
}
