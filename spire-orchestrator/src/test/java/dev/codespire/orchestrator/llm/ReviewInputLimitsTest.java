package dev.codespire.orchestrator.llm;

import dev.codespire.contract.llm.ModelParams;
import dev.codespire.orchestrator.settings.AppSettingRepository;
import dev.codespire.orchestrator.settings.ReviewDiffLimit;
import dev.codespire.orchestrator.settings.ReviewDiffLimitFixture;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The input a review may send: the catalog window minus the output cap the call really sends. */
class ReviewInputLimitsTest {

    @Test
    void anUnknownWindowIsUnknown() {
        assertNull(ReviewInputLimits.maxInputTokens(null, 4_096), "a manual model keeps today's budgets");
    }

    @Test
    void theProvidersOutputCapIsReserved() {
        assertEquals(1_000_000 - 4_096, ReviewInputLimits.maxInputTokens(1_000_000L, 4_096));
    }

    @Test
    void withoutAProviderCapTheDefaultOutputCapIsReserved() {
        assertEquals(1_000_000 - 16_384, ReviewInputLimits.maxInputTokens(1_000_000L, null));
        assertEquals(16_384, ModelParams.DEFAULT_MAX_OUTPUT_TOKENS, "the cap the provider sends when none is set");
    }

    @Test
    void aTwoMillionWindowDoesNotOverflow() {
        assertEquals(2_000_000 - 16_384, ReviewInputLimits.maxInputTokens(2_000_000L, null));
    }

    @Test
    void aWindowPastIntIsClampedRatherThanWrapped() {
        assertEquals(Integer.MAX_VALUE, ReviewInputLimits.maxInputTokens(Long.MAX_VALUE, null));
    }

    @Test
    void aWindowSmallerThanTheOutputCapLeavesNoInputRatherThanANegativeOne() {
        assertEquals(0, ReviewInputLimits.maxInputTokens(8_000L, null));
    }

    // ---- the Review diff limit beside the window (PR 456, security/M-1) ----

    @Test
    void aStoredLimitRidesBesideTheWindow() {
        ReviewInputLimits.Limits limits = limitsWith(storedLimit(60_000)).forModel("TEST-model", null);

        assertEquals(1_000_000 - ModelParams.DEFAULT_MAX_OUTPUT_TOKENS, limits.maxInputTokens());
        assertEquals(60_000, limits.diffTokenCap());
    }

    @Test
    void noStoredLimitIsNoCap() {
        assertNull(limitsWith(storedLimit(null)).forModel("TEST-model", null).diffTokenCap());
    }

    /** A failed read must not read as "unset": on a million-token window, unset sends about 700,000 tokens of diff. */
    @Test
    void aFailedLimitReadCapsTheDiffAtTheLowestLimit() {
        ReviewDiffLimit failing = ReviewDiffLimitFixture.over(new AppSettingRepository() {
            @Override
            public Optional<String> getStrictly(String key) {
                throw new ReadFailed(key, new SQLException("TEST-unreachable"));
            }
        });

        assertEquals(ReviewDiffLimit.MIN_TOKENS, limitsWith(failing).forModel("TEST-model", null).diffTokenCap());
    }

    /** A known window, so an unset limit is the large answer. */
    private static ReviewInputLimits limitsWith(ReviewDiffLimit diffLimit) {
        ReviewInputLimits limits = new ReviewInputLimits() {
            @Override
            Optional<Long> contextWindow(String model) {
                return Optional.of(1_000_000L);
            }
        };
        limits.diffLimit = diffLimit;
        return limits;
    }

    private static ReviewDiffLimit storedLimit(Integer stored) {
        return new ReviewDiffLimit() {
            @Override
            public Optional<Integer> current() {
                return Optional.ofNullable(stored);
            }
        };
    }
}
