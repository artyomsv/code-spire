package dev.codespire.llm;

import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.llm.PromptKind;
import dev.codespire.contract.llm.PromptTemplate;
import dev.codespire.diff.TokenBudget;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The review input limit's one budget function (design 2026-11-26). Each case names the rule it holds, and each rule
 * has a case that fails when its line is removed.
 */
class DiffBudgetTest {

    private static final int REVIEW_DEFAULT = 24_000;
    private static final int RECONCILE_DEFAULT = 12_000;
    private static final int OTHERS = 5_000;

    @Test
    void theDefaultsAreTodaysPaletteBudgets() {
        assertEquals(REVIEW_DEFAULT, DiffBudget.defaultFor(PromptKind.REVIEW));
        assertEquals(RECONCILE_DEFAULT, DiffBudget.defaultFor(PromptKind.RECONCILE));
        assertEquals(12_000, DiffBudget.defaultFor(PromptKind.FOLLOWUP));
    }

    @Test
    void anUnknownWindowKeepsTodaysBudget() {
        assertEquals(REVIEW_DEFAULT, DiffBudget.tokens(shape(REVIEW_DEFAULT, OTHERS, 1), DiffBudget.Limits.UNKNOWN));
        assertEquals(RECONCILE_DEFAULT, DiffBudget.tokens(shape(RECONCILE_DEFAULT, OTHERS, 1), DiffBudget.Limits.UNKNOWN));
    }

    @Test
    void anUnknownWindowIgnoresALargerSetting() {
        assertEquals(REVIEW_DEFAULT, DiffBudget.tokens(shape(REVIEW_DEFAULT, OTHERS, 1), new DiffBudget.Limits(null, 500_000)),
                "unknown means today's budgets; the setting only ever lowers");
    }

    @Test
    void aLargeWindowLetsTheDiffFarPastTheDefault() {
        int budget = DiffBudget.tokens(shape(REVIEW_DEFAULT, OTHERS, 1), new DiffBudget.Limits(983_616, null));

        assertEquals((int) Math.floor(983_616 * 0.75) - OTHERS, budget, "75% of the input limit, less the rest");
    }

    @Test
    void aWindowAboveTheDefaultGivesExactlyTheRoomWithItsMargin() {
        // room = 45,000 - 5,000 = 40,000; full = 55,000. The margin, not the whole window, is the answer.
        assertEquals(40_000, DiffBudget.tokens(shape(REVIEW_DEFAULT, OTHERS, 1), new DiffBudget.Limits(60_000, null)));
    }

    @Test
    void aWindowJustAboveTheDefaultStillGetsTheDefault() {
        // room = 22,500 - 5,000 = 17,500 is under today's 24,000, and the window can hold 25,000: the floor holds.
        assertEquals(REVIEW_DEFAULT, DiffBudget.tokens(shape(REVIEW_DEFAULT, OTHERS, 1), new DiffBudget.Limits(30_000, null)));
    }

    @Test
    void aWindowTooSmallForTheDefaultGetsWhatItCanHold() {
        // room = 15,000 - 5,000 = 10,000; full = 15,000 < 24,000. Today's call would have overflowed this window.
        assertEquals(15_000, DiffBudget.tokens(shape(REVIEW_DEFAULT, OTHERS, 1), new DiffBudget.Limits(20_000, null)));
    }

    @Test
    void theSettingLowersTheBudget() {
        assertEquals(90_000, DiffBudget.tokens(shape(REVIEW_DEFAULT, OTHERS, 1), new DiffBudget.Limits(983_616, 90_000)));
    }

    @Test
    void theSettingNeverLowersTheBudgetUnderTheDefault() {
        assertEquals(REVIEW_DEFAULT, DiffBudget.tokens(shape(REVIEW_DEFAULT, OTHERS, 1), new DiffBudget.Limits(983_616, 10_000)));
        assertEquals(REVIEW_DEFAULT, DiffBudget.tokens(shape(REVIEW_DEFAULT, OTHERS, 1), new DiffBudget.Limits(983_616, REVIEW_DEFAULT)));
    }

    @Test
    void theSettingAtTheReviewMinimumLeavesAReconcileAtItsOwnDefaultOrMore() {
        assertEquals(REVIEW_DEFAULT,
                DiffBudget.tokens(shape(RECONCILE_DEFAULT, OTHERS, 1), new DiffBudget.Limits(983_616, REVIEW_DEFAULT)));
    }

    @Test
    void noRoomRefusesAndNamesTheInputLimit() {
        // 75% of 10,000 is 7,500, all of it taken by the rest of the prompt.
        DiffBudget.NoRoomForDiff refused = assertThrows(DiffBudget.NoRoomForDiff.class,
                () -> DiffBudget.tokens(shape(REVIEW_DEFAULT, 7_500, 1), new DiffBudget.Limits(10_000, null)));

        assertTrue(refused.getMessage().contains("10000"), refused.getMessage());
    }

    @Test
    void aSliverOfRoomIsNotRefused() {
        assertEquals(2_501, DiffBudget.tokens(shape(REVIEW_DEFAULT, 7_499, 1), new DiffBudget.Limits(10_000, null)),
                "room 1, full 2,501: the window gets what it can hold");
    }

    @Test
    void aDiffUsedTwiceGetsHalfTheRoomPerUse() {
        // room = 150,000 - 5,000 = 145,000, split between two uses.
        assertEquals(72_500, DiffBudget.tokens(shape(REVIEW_DEFAULT, OTHERS, 2), new DiffBudget.Limits(200_000, null)));
        assertEquals(145_000, DiffBudget.tokens(shape(REVIEW_DEFAULT, OTHERS, 1), new DiffBudget.Limits(200_000, null)));
    }

    @Test
    void aCallIsLargeOnlyWhenItSendsMoreDiffThanTodaysBudget() {
        String diff = "TEST line\n".repeat(10_000); // about 31,250 tokens

        assertEquals(PromptSize.LARGE, DiffBudget.sizeOf(diff, 700_000, PromptKind.REVIEW));
        assertEquals(PromptSize.WITHIN_DEFAULT, DiffBudget.sizeOf("TEST line\n", 700_000, PromptKind.REVIEW), "a large window alone is not");
        assertEquals(PromptSize.WITHIN_DEFAULT, DiffBudget.sizeOf(diff, REVIEW_DEFAULT, PromptKind.REVIEW), "clipped to today's budget, it is not");
    }

    @Test
    void theLargestWindowDoesNotOverflow() {
        int budget = DiffBudget.tokens(shape(REVIEW_DEFAULT, OTHERS, 1), new DiffBudget.Limits(Integer.MAX_VALUE, null));

        assertTrue(budget > REVIEW_DEFAULT, "a huge window is a huge budget, not a wrapped negative: " + budget);
    }

    // ---- the fence around each use of the diff (PR 456, code-quality/M-1) ----

    /** A window that cannot hold today's 24,000 twice: the budget is the whole window, the branch with no margin. */
    private static final int SMALL_WINDOW = 20_000;

    @Test
    void theRestOfThePromptCountsTheFenceOfEveryDiffUse() {
        PromptTemplate twice = new PromptTemplate(PromptKind.REVIEW, "persona", "{{pr_title}}\n{{diff}}\n{{diff}}");
        Map<String, String> values = new HashMap<>(Map.of("pr_title", "TEST title"));
        values.put("diff", "");
        Prompt empty = PromptRenderer.render(twice, values).prompt();

        assertEquals(TokenBudget.estimateTokens(empty.system()) + TokenBudget.estimateTokens(empty.user())
                        + 2 * PromptRenderer.fenceTokens(), DiffBudget.otherTokens(twice, values),
                "an empty diff renders without its fence, so each use reserves it");
    }

    @Test
    void aDiffOfExactlyTheBudgetUsedTwiceStaysInsideTheWindow() {
        assertRendersInsideTheWindow(new PromptTemplate(PromptKind.REVIEW, "persona",
                "{{pr_title}}\n{{diff}}\n{{diff}}"));
    }

    @Test
    void aDiffOfExactlyTheBudgetUsedOnceStaysInsideTheWindow() {
        assertRendersInsideTheWindow(new PromptTemplate(PromptKind.REVIEW, "persona", "{{pr_title}}\n{{diff}}"));
    }

    /**
     * Sizes the diff to exactly its budget by estimate, renders it, and measures the whole prompt the way the budget
     * does. Without the fences in {@code others}, each use overshoots the window by the fence's estimate.
     */
    private static void assertRendersInsideTheWindow(PromptTemplate template) {
        Map<String, String> values = Map.of("pr_title", "TEST title");
        DiffBudget.Limits limits = new DiffBudget.Limits(SMALL_WINDOW, null);
        int budget = DiffBudget.forTemplate(template, values, limits);
        assertTrue(budget < REVIEW_DEFAULT, "the full-window branch, where the margin does not absorb the fence");
        String diff = textOfAtMost(budget);
        assertTrue(TokenBudget.estimateTokens(diff) >= budget - 1, "the diff fills its budget");

        Map<String, String> withDiff = new HashMap<>(values);
        withDiff.put("diff", diff);
        PromptRenderer.Rendered rendered = PromptRenderer.render(template, withDiff, Map.of("diff", budget));

        assertFalse(rendered.truncated(), "a diff of exactly the budget is not clipped");
        int total = TokenBudget.estimateTokens(rendered.prompt().system())
                + TokenBudget.estimateTokens(rendered.prompt().user());
        assertTrue(total <= SMALL_WINDOW, "rendered " + total + " estimated tokens into a " + SMALL_WINDOW + " window");
    }

    private static DiffBudget.PromptShape shape(int defaultBudget, int otherTokens, int diffUses) {
        return new DiffBudget.PromptShape(defaultBudget, otherTokens, diffUses);
    }

    /** The longest text whose estimate is within {@code tokens}: the estimate rounds up, so it may be one short. */
    private static String textOfAtMost(int tokens) {
        int length = tokens * 16 / 5 + 1; // 3.2 characters per token, and one over
        while (TokenBudget.estimateTokens("x".repeat(length)) > tokens) {
            length--;
        }
        return "x".repeat(length);
    }
}
