package dev.codespire.llm;

import dev.codespire.contract.llm.PromptKind;
import org.junit.jupiter.api.Test;

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
        assertEquals(REVIEW_DEFAULT, DiffBudget.tokens(REVIEW_DEFAULT, OTHERS, 1, DiffBudget.Limits.UNKNOWN));
        assertEquals(RECONCILE_DEFAULT, DiffBudget.tokens(RECONCILE_DEFAULT, OTHERS, 1, DiffBudget.Limits.UNKNOWN));
    }

    @Test
    void anUnknownWindowIgnoresALargerSetting() {
        assertEquals(REVIEW_DEFAULT, DiffBudget.tokens(REVIEW_DEFAULT, OTHERS, 1, new DiffBudget.Limits(null, 500_000)),
                "unknown means today's budgets; the setting only ever lowers");
    }

    @Test
    void aLargeWindowLetsTheDiffFarPastTheDefault() {
        int budget = DiffBudget.tokens(REVIEW_DEFAULT, OTHERS, 1, new DiffBudget.Limits(983_616, null));

        assertEquals((int) Math.floor(983_616 * 0.75) - OTHERS, budget, "75% of the input limit, less the rest");
    }

    @Test
    void aWindowAboveTheDefaultGivesExactlyTheRoomWithItsMargin() {
        // room = 45,000 - 5,000 = 40,000; full = 55,000. The margin, not the whole window, is the answer.
        assertEquals(40_000, DiffBudget.tokens(REVIEW_DEFAULT, OTHERS, 1, new DiffBudget.Limits(60_000, null)));
    }

    @Test
    void aWindowJustAboveTheDefaultStillGetsTheDefault() {
        // room = 22,500 - 5,000 = 17,500 is under today's 24,000, and the window can hold 25,000: the floor holds.
        assertEquals(REVIEW_DEFAULT, DiffBudget.tokens(REVIEW_DEFAULT, OTHERS, 1, new DiffBudget.Limits(30_000, null)));
    }

    @Test
    void aWindowTooSmallForTheDefaultGetsWhatItCanHold() {
        // room = 15,000 - 5,000 = 10,000; full = 15,000 < 24,000. Today's call would have overflowed this window.
        assertEquals(15_000, DiffBudget.tokens(REVIEW_DEFAULT, OTHERS, 1, new DiffBudget.Limits(20_000, null)));
    }

    @Test
    void theSettingLowersTheBudget() {
        assertEquals(90_000, DiffBudget.tokens(REVIEW_DEFAULT, OTHERS, 1, new DiffBudget.Limits(983_616, 90_000)));
    }

    @Test
    void theSettingNeverLowersTheBudgetUnderTheDefault() {
        assertEquals(REVIEW_DEFAULT, DiffBudget.tokens(REVIEW_DEFAULT, OTHERS, 1, new DiffBudget.Limits(983_616, 10_000)));
        assertEquals(REVIEW_DEFAULT, DiffBudget.tokens(REVIEW_DEFAULT, OTHERS, 1, new DiffBudget.Limits(983_616, REVIEW_DEFAULT)));
    }

    @Test
    void theSettingAtTheReviewMinimumLeavesAReconcileAtItsOwnDefaultOrMore() {
        assertEquals(REVIEW_DEFAULT,
                DiffBudget.tokens(RECONCILE_DEFAULT, OTHERS, 1, new DiffBudget.Limits(983_616, REVIEW_DEFAULT)));
    }

    @Test
    void noRoomRefusesAndNamesTheInputLimit() {
        // 75% of 10,000 is 7,500, all of it taken by the rest of the prompt.
        DiffBudget.NoRoomForDiff refused = assertThrows(DiffBudget.NoRoomForDiff.class,
                () -> DiffBudget.tokens(REVIEW_DEFAULT, 7_500, 1, new DiffBudget.Limits(10_000, null)));

        assertTrue(refused.getMessage().contains("10000"), refused.getMessage());
    }

    @Test
    void aSliverOfRoomIsNotRefused() {
        assertEquals(2_501, DiffBudget.tokens(REVIEW_DEFAULT, 7_499, 1, new DiffBudget.Limits(10_000, null)),
                "room 1, full 2,501: the window gets what it can hold");
    }

    @Test
    void aDiffUsedTwiceGetsHalfTheRoomPerUse() {
        // room = 150,000 - 5,000 = 145,000, split between two uses.
        assertEquals(72_500, DiffBudget.tokens(REVIEW_DEFAULT, OTHERS, 2, new DiffBudget.Limits(200_000, null)));
        assertEquals(145_000, DiffBudget.tokens(REVIEW_DEFAULT, OTHERS, 1, new DiffBudget.Limits(200_000, null)));
    }

    @Test
    void aCallIsLargeOnlyWhenItSendsMoreDiffThanTodaysBudget() {
        String diff = "TEST line\n".repeat(10_000); // about 31,250 tokens

        assertTrue(DiffBudget.isLarge(diff, 700_000, PromptKind.REVIEW));
        assertFalse(DiffBudget.isLarge("TEST line\n", 700_000, PromptKind.REVIEW), "a large window alone is not");
        assertFalse(DiffBudget.isLarge(diff, REVIEW_DEFAULT, PromptKind.REVIEW), "clipped to today's budget, it is not");
    }

    @Test
    void theLargestWindowDoesNotOverflow() {
        int budget = DiffBudget.tokens(REVIEW_DEFAULT, OTHERS, 1, new DiffBudget.Limits(Integer.MAX_VALUE, null));

        assertTrue(budget > REVIEW_DEFAULT, "a huge window is a huge budget, not a wrapped negative: " + budget);
    }
}
