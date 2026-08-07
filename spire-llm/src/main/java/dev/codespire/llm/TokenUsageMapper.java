package dev.codespire.llm;

import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.TokenCount;
import dev.codespire.contract.review.TokenType;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.googleai.GoogleAiGeminiTokenUsage;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Maps a vendor's token accounting onto the neutral {@link TokenType} partition.
 *
 * <p>The detail counts live on vendor subclasses, not on the base {@link TokenUsage}, and the vendors
 * disagree about whether those details are INCLUDED in the headline input/output numbers or
 * ADDITIONAL to them. OpenAI's input count includes its cached portion; Anthropic's excludes cache
 * reads entirely. Summing naively would double-count one and undercount the other, and both produce a
 * number that looks right.
 *
 * <p>So every mapping is checked against {@code totalTokenCount()} — arithmetic the vendor computed
 * independently. A mismatch is not smoothed over: the breakdown is discarded in favour of a single
 * {@link TokenType#TOTAL} line marked unreconciled, which is visible to an operator and cannot be
 * mistaken for a priced call.
 */
public final class TokenUsageMapper {

    private TokenUsageMapper() {
    }

    public static ModelUsage map(String model, TokenUsage usage) {
        if (usage == null) {
            return unreconciled(model, 0);
        }
        List<TokenCount> counts = partition(usage);
        int fullTotal = sumOf(counts, EnumSet.allOf(TokenType.class));
        Integer vendorTotal = usage.totalTokenCount();
        // No vendor total means nothing contradicts the partition — trust it and record our own sum.
        if (vendorTotal == null) {
            return new ModelUsage(model, counts, fullTotal, true);
        }
        if (sumOf(counts, coveredByTotal(usage)) != vendorTotal) {
            // Our arithmetic is the suspect party here, so record the vendor's own figure rather than
            // a sum derived from the extraction that just failed its own check.
            return unreconciled(model, vendorTotal);
        }
        return new ModelUsage(model, counts, fullTotal, true);
    }

    /**
     * Which buckets the vendor's own total accounts for.
     *
     * <p>Anthropic's is derived by LangChain4j from input and output alone — its builder cannot even be
     * given a total — so its two cache buckets sit OUTSIDE the cross-check. Including them would fail
     * every cached Anthropic call and leave exactly the cheap calls unpriceable. Every other vendor
     * reports a genuine grand total that covers all of its buckets.
     */
    private static Set<TokenType> coveredByTotal(TokenUsage usage) {
        if (usage instanceof AnthropicTokenUsage) {
            return EnumSet.of(TokenType.INPUT, TokenType.OUTPUT);
        }
        return EnumSet.allOf(TokenType.class);
    }

    private static int sumOf(List<TokenCount> counts, Set<TokenType> covered) {
        int total = 0;
        for (TokenCount count : counts) {
            if (covered.contains(count.type())) {
                total += count.tokens();
            }
        }
        return total;
    }

    private static ModelUsage unreconciled(String model, int total) {
        return new ModelUsage(model, List.of(new TokenCount(TokenType.TOTAL, total)), total, false);
    }

    private static List<TokenCount> partition(TokenUsage usage) {
        int input = zeroIfNull(usage.inputTokenCount());
        int output = zeroIfNull(usage.outputTokenCount());
        return switch (usage) {
            case OpenAiTokenUsage u -> openAi(u, input, output);
            case AnthropicTokenUsage u -> anthropic(u, input, output);
            case GoogleAiGeminiTokenUsage u -> gemini(u, input, output);
            default -> nonEmpty(new TokenCount(TokenType.INPUT, input), new TokenCount(TokenType.OUTPUT, output));
        };
    }

    /** Cached is a SUBSET of the input count, and reasoning a subset of output — both subtracted out. */
    private static List<TokenCount> openAi(OpenAiTokenUsage u, int input, int output) {
        int cached = u.inputTokensDetails() == null ? 0 : zeroIfNull(u.inputTokensDetails().cachedTokens());
        int reasoning = u.outputTokensDetails() == null ? 0
                : zeroIfNull(u.outputTokensDetails().reasoningTokens());
        return nonEmpty(new TokenCount(TokenType.INPUT, input - cached),
                new TokenCount(TokenType.CACHED_INPUT, cached),
                new TokenCount(TokenType.OUTPUT, output - reasoning),
                new TokenCount(TokenType.REASONING, reasoning));
    }

    /** Cache reads and writes are ADDITIONAL to the input count — nothing to subtract. */
    private static List<TokenCount> anthropic(AnthropicTokenUsage u, int input, int output) {
        return nonEmpty(new TokenCount(TokenType.INPUT, input),
                new TokenCount(TokenType.CACHED_INPUT, zeroIfNull(u.cacheReadInputTokens())),
                new TokenCount(TokenType.CACHE_WRITE, zeroIfNull(u.cacheCreationInputTokens())),
                new TokenCount(TokenType.OUTPUT, output));
    }

    /** Cached content is a SUBSET of the input count; thoughts are reported apart from output. */
    private static List<TokenCount> gemini(GoogleAiGeminiTokenUsage u, int input, int output) {
        int cached = zeroIfNull(u.cachedContentTokenCount());
        return nonEmpty(new TokenCount(TokenType.INPUT, input - cached),
                new TokenCount(TokenType.CACHED_INPUT, cached),
                new TokenCount(TokenType.OUTPUT, output),
                new TokenCount(TokenType.REASONING, zeroIfNull(u.thoughtsTokenCount())));
    }

    /** Only dimensions that actually occurred, so a call without caching carries no zero rows. */
    private static List<TokenCount> nonEmpty(TokenCount... candidates) {
        List<TokenCount> kept = new ArrayList<>(candidates.length);
        for (TokenCount candidate : candidates) {
            if (candidate.tokens() > 0) {
                kept.add(candidate);
            }
        }
        return List.copyOf(kept);
    }

    private static int zeroIfNull(Integer value) {
        return value == null ? 0 : value;
    }
}
