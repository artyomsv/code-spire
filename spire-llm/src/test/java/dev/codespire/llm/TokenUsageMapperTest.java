package dev.codespire.llm;

import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.TokenType;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.googleai.GoogleAiGeminiTokenUsage;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mapper's one invariant: every token lands in exactly one bucket, and the buckets sum to the
 * total the VENDOR computed. That cross-check is what makes the mapping trustworthy without trusting
 * anyone's memory of each vendor's caching semantics — the vendors disagree on whether detail counts
 * are included in or additional to the headline numbers, and a wrong guess yields a plausible number.
 *
 * <p>All counts here are obviously-synthetic round numbers driving a pure function; none becomes
 * user-visible state.
 */
class TokenUsageMapperTest {

    private static void assertPartitions(ModelUsage usage) {
        int summed = 0;
        for (TokenType type : TokenType.values()) {
            summed += usage.tokensOf(type);
        }
        assertTrue(usage.reconciled(),
                "the buckets the vendor's total covers must agree with that total");
        assertEquals(usage.reportedTotal(), summed,
                "reportedTotal must be the sum of EVERY bucket — the call's true token count, which on "
                        + "Anthropic exceeds the vendor's own input+output total");
    }

    @Test
    void openAiSplitsCachedOutOfInputAndReasoningOutOfOutput() {
        OpenAiTokenUsage vendor = OpenAiTokenUsage.builder()
                .inputTokenCount(1000)
                .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(400).build())
                .outputTokenCount(300)
                .outputTokensDetails(OpenAiTokenUsage.OutputTokensDetails.builder().reasoningTokens(100).build())
                .totalTokenCount(1300)
                .build();

        ModelUsage usage = TokenUsageMapper.map("TEST-MODEL", vendor);

        assertEquals(600, usage.tokensOf(TokenType.INPUT));
        assertEquals(400, usage.tokensOf(TokenType.CACHED_INPUT));
        assertEquals(200, usage.tokensOf(TokenType.OUTPUT));
        assertEquals(100, usage.tokensOf(TokenType.REASONING));
        assertPartitions(usage);
    }

    /**
     * Anthropic reports cache reads and writes as line items ADDITIONAL to its input count, and its
     * builder cannot be given a total at all — LangChain4j derives one as input + output, excluding
     * both cache buckets. So the partition sums to more than the vendor's "total", and the cross-check
     * must cover only INPUT + OUTPUT. Checking all four against that total would fail on every cached
     * call and make cached calls the only unpriceable ones.
     */
    @Test
    void anthropicTreatsCacheCountsAsAdditiveLineItemsOutsideItsTotal() {
        AnthropicTokenUsage vendor = AnthropicTokenUsage.builder()
                .inputTokenCount(600)
                .cacheReadInputTokens(400)
                .cacheCreationInputTokens(50)
                .outputTokenCount(200)
                .build();

        ModelUsage usage = TokenUsageMapper.map("TEST-MODEL", vendor);

        assertEquals(600, usage.tokensOf(TokenType.INPUT));
        assertEquals(400, usage.tokensOf(TokenType.CACHED_INPUT));
        assertEquals(50, usage.tokensOf(TokenType.CACHE_WRITE));
        assertEquals(200, usage.tokensOf(TokenType.OUTPUT));
        assertPartitions(usage);
        // reportedTotal is the TRUE token count (all four buckets), not the vendor's partial figure.
        assertEquals(1250, usage.reportedTotal());
    }

    /**
     * Pins the Anthropic semantics the mapper depends on, so a LangChain4j upgrade that starts folding
     * cache tokens into the derived total is caught here rather than by every cached call silently
     * degrading to an unpriceable TOTAL line.
     */
    @Test
    void anthropicsDerivedTotalStillExcludesCacheTokens() {
        AnthropicTokenUsage vendor = AnthropicTokenUsage.builder()
                .inputTokenCount(600)
                .cacheReadInputTokens(400)
                .cacheCreationInputTokens(50)
                .outputTokenCount(200)
                .build();

        assertEquals(800, vendor.totalTokenCount(),
                "LangChain4j derives Anthropic's total as input + output only. If this now includes the "
                        + "cache buckets, TokenUsageMapper's Anthropic cross-check must cover them too.");
    }

    @Test
    void geminiSplitsCachedContentOutOfInputAndReportsThoughtsSeparately() {
        GoogleAiGeminiTokenUsage vendor = GoogleAiGeminiTokenUsage.builder()
                .inputTokenCount(1000)
                .cachedContentTokenCount(250)
                .outputTokenCount(300)
                .thoughtsTokenCount(120)
                .totalTokenCount(1420)
                .build();

        ModelUsage usage = TokenUsageMapper.map("TEST-MODEL", vendor);

        assertEquals(750, usage.tokensOf(TokenType.INPUT));
        assertEquals(250, usage.tokensOf(TokenType.CACHED_INPUT));
        assertEquals(300, usage.tokensOf(TokenType.OUTPUT));
        assertEquals(120, usage.tokensOf(TokenType.REASONING));
        assertPartitions(usage);
    }

    /** A vendor we have no mapping for still yields a usable two-bucket partition. */
    @Test
    void aPlainTokenUsageMapsToInputAndOutput() {
        ModelUsage usage = TokenUsageMapper.map("TEST-MODEL", new TokenUsage(700, 300, 1000));

        assertEquals(700, usage.tokensOf(TokenType.INPUT));
        assertEquals(300, usage.tokensOf(TokenType.OUTPUT));
        assertPartitions(usage);
    }

    /**
     * The degraded path. When the buckets cannot be made to sum to the vendor's total — a new billing
     * dimension we do not map yet — record the vendor's own total and say so, rather than publishing a
     * breakdown that quietly loses tokens.
     */
    @Test
    void anIrreconcilableBreakdownCollapsesToASingleUnreconciledTotal() {
        ModelUsage usage = TokenUsageMapper.map("TEST-MODEL", new TokenUsage(700, 300, 1500));

        assertFalse(usage.reconciled());
        assertEquals(1500, usage.reportedTotal());
        assertEquals(1500, usage.tokensOf(TokenType.TOTAL));
        assertEquals(1, usage.counts().size());
    }

    /** No usage at all is still a countable call, not a crash and not an invented number. */
    @Test
    void nullUsageYieldsAZeroTotalLine() {
        ModelUsage usage = TokenUsageMapper.map("TEST-MODEL", null);

        assertEquals(0, usage.reportedTotal());
        assertEquals(1, usage.counts().size());
        assertEquals(0, usage.tokensOf(TokenType.TOTAL));
    }
}
