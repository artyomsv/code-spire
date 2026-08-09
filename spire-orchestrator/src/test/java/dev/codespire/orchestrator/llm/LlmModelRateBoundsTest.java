package dev.codespire.orchestrator.llm;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A rate is bounded ABOVE as well as below, because {@code tokens × rate} is computed in a {@code long}
 * of millicents: past a certain rate the product wraps and the ledger stores a NEGATIVE cost, which
 * subtracts from the review's total and from any deployment-wide sum. The validator was the only thing
 * between an operator's typo and that row, and it bounded rates in one direction only.
 *
 * <p>Both directions are asserted: the bound must reject a rate that could overflow, and must NOT reject
 * a real price. It exists to catch a typo, not to cap what a model may cost.
 */
class LlmModelRateBoundsTest {

    private static LlmModelInput metered(Map<String, Long> rates) {
        return new LlmModelInput("openai", "TEST-RATE-BOUNDS", "TEST rate bounds", "METERED", rates,
                "MAX_TOKENS", true, null, Map.of(), true);
    }

    @Test
    void aRateLargeEnoughToOverflowACostIsRejectedByName() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> LlmModelPricingValidator.validate(
                        metered(Map.of("INPUT", Long.MAX_VALUE / 2, "OUTPUT", 400_000L))));

        assertTrue(refused.getMessage().contains("INPUT"),
                "the operator has to be told WHICH rate to fix: " + refused.getMessage());
    }

    /**
     * The bound is a typo guard, so it has to stay clear of real prices. $15 in and $200 out per million
     * tokens is already above anything published, and must remain saveable.
     */
    @Test
    void aPriceWellAboveTodaysMostExpensiveModelIsStillAccepted() {
        assertDoesNotThrow(() -> LlmModelPricingValidator.validate(
                metered(Map.of("INPUT", 1_500_000L, "OUTPUT", 20_000_000L))));
    }
}
