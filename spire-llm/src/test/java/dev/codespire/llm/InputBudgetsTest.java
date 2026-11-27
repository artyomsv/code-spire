package dev.codespire.llm;

import dev.codespire.contract.llm.PromptKind;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class InputBudgetsTest {

    @Test
    void anUnknownWindowKeepsThePaletteBudgets() {
        assertEquals(Map.of(), InputBudgets.forWindow(PromptKind.REVIEW, DiffBudget.Limits.UNKNOWN));
    }

    @Test
    void theReferenceWindowKeepsThePaletteBudgets() {
        assertEquals(Map.of(), InputBudgets.forWindow(PromptKind.REVIEW, new DiffBudget.Limits(200_000, null)));
    }

    @Test
    void aWindowTwiceTheReferenceDoublesEachBackgroundBudget() {
        Map<String, Integer> budgets = InputBudgets.forWindow(PromptKind.REVIEW, new DiffBudget.Limits(400_000, null));

        assertEquals(Map.of("context", 8_000, "code_context", 12_000, "prior_findings", 8_000), budgets);
    }

    @Test
    void aHugeWindowStopsAtTheMaximumScale() {
        Map<String, Integer> budgets = InputBudgets.forWindow(PromptKind.REVIEW,
                new DiffBudget.Limits(10_000_000, null));

        assertEquals(20_000, budgets.get("context"));
        assertEquals(30_000, budgets.get("code_context"));
        assertEquals(20_000, budgets.get("prior_findings"));
    }

    @Test
    void theDiffIsNeverABackgroundInput() {
        Map<String, Integer> budgets = InputBudgets.forWindow(PromptKind.RECONCILE,
                new DiffBudget.Limits(1_000_000, null));

        assertFalse(budgets.containsKey("diff"), "the diff budget comes from DiffBudget alone");
        assertEquals(20_000, budgets.get("prior_findings"), "the reconcile's prior findings grow too");
    }

    @Test
    void theDiffBudgetIsAddedBesideTheBackground() {
        assertEquals(Map.of("context", 8_000, "diff", 50_000),
                InputBudgets.withDiff(Map.of("context", 8_000), 50_000));
    }
}
