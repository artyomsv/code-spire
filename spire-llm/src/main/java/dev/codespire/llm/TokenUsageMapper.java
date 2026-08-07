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

    /**
     * The degraded path, carrying the vendor's own figure — floored, because that figure is the one
     * number here we have not derived and therefore cannot vouch for. {@code remainder} floors the
     * subtraction path and says why; this is the same reason at the other door. A negative arriving as
     * {@code totalTokenCount()} (a misbehaving OpenAI-compatible proxy answering
     * {@code "total_tokens": -1} suffices) used to pass through unchecked, because it is built into a
     * {@code TOTAL} line directly and never meets {@code nonEmpty}'s {@code > 0} filter. It then broke
     * {@code llm_charge.tokens >= 0} inside the {@code ReviewGenerated} handler, BEFORE the
     * {@code PostComments} emit — a paid call whose findings never reached the pull request, repeating
     * on every redelivery.
     *
     * <p>Zero is the honest floor: the call stays counted and stays flagged unreconciled, which already
     * means "nothing usable was reported about the split" — the shape a null usage produces too.
     */
    private static ModelUsage unreconciled(String model, int total) {
        int reported = Math.max(0, total);
        return new ModelUsage(model, List.of(new TokenCount(TokenType.TOTAL, reported)), reported, false);
    }

    private static List<TokenCount> partition(TokenUsage usage) {
        int input = countOf(usage.inputTokenCount());
        int output = countOf(usage.outputTokenCount());
        return switch (usage) {
            case OpenAiTokenUsage u -> openAi(u, input, output);
            case AnthropicTokenUsage u -> anthropic(u, input, output);
            case GoogleAiGeminiTokenUsage u -> gemini(u, input, output);
            default -> nonEmpty(new TokenCount(TokenType.INPUT, input), new TokenCount(TokenType.OUTPUT, output));
        };
    }

    /** Cached is a SUBSET of the input count, and reasoning a subset of output — both subtracted out. */
    private static List<TokenCount> openAi(OpenAiTokenUsage u, int input, int output) {
        int cached = u.inputTokensDetails() == null ? 0 : countOf(u.inputTokensDetails().cachedTokens());
        int reasoning = u.outputTokensDetails() == null ? 0
                : countOf(u.outputTokensDetails().reasoningTokens());
        return nonEmpty(new TokenCount(TokenType.INPUT, remainder(input, cached)),
                new TokenCount(TokenType.CACHED_INPUT, cached),
                new TokenCount(TokenType.OUTPUT, remainder(output, reasoning)),
                new TokenCount(TokenType.REASONING, reasoning));
    }

    /** Cache reads and writes are ADDITIONAL to the input count — nothing to subtract. */
    private static List<TokenCount> anthropic(AnthropicTokenUsage u, int input, int output) {
        return nonEmpty(new TokenCount(TokenType.INPUT, input),
                new TokenCount(TokenType.CACHED_INPUT, countOf(u.cacheReadInputTokens())),
                new TokenCount(TokenType.CACHE_WRITE, countOf(u.cacheCreationInputTokens())),
                new TokenCount(TokenType.OUTPUT, output));
    }

    /** Cached content is a SUBSET of the input count; thoughts are reported apart from output. */
    private static List<TokenCount> gemini(GoogleAiGeminiTokenUsage u, int input, int output) {
        int cached = countOf(u.cachedContentTokenCount());
        return nonEmpty(new TokenCount(TokenType.INPUT, remainder(input, cached)),
                new TokenCount(TokenType.CACHED_INPUT, cached),
                new TokenCount(TokenType.OUTPUT, output),
                new TokenCount(TokenType.REASONING, countOf(u.thoughtsTokenCount())));
    }

    /**
     * The part of a whole not accounted for by one of its subsets, floored at zero.
     *
     * <p>Every subtraction here rests on a vendor's documented claim that the detail count is INSIDE
     * the headline one. A vendor that contradicts its own documentation — or a LangChain4j mapping
     * that populates the wrong field — makes the remainder negative, and a negative token count is
     * meaningless in every direction: it violates {@code llm_charge.tokens >= 0}, so the charge
     * dead-letters instead of being recorded, and it makes the partition sum SMALLER than the truth,
     * which is the one direction the cross-check against the vendor's total cannot catch.
     *
     * <p>Flooring keeps the failure loud in the safe direction: the missing tokens stay in the subset
     * bucket, the partition sums HIGHER than the vendor's total, the cross-check trips, and the call
     * degrades to one unreconciled {@code TOTAL} line carrying the vendor's own figure. Non-negativity
     * is now stated rather than inferred — {@link TokenCount} refuses a negative — so this floor and
     * {@link #countOf} are what keep that refusal a backstop instead of a live failure path.
     */
    private static int remainder(int whole, int part) {
        return Math.max(0, whole - part);
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

    /**
     * A vendor's reported field as a usable count: absent and negative both read as zero.
     *
     * <p>The negative half matters because {@link TokenCount} now REFUSES a negative rather than
     * carrying one to the ledger. Every raw field goes through here, so the refusal is a backstop
     * against a future construction site and never a new way for a paid call to dead-letter: before,
     * a negative input count was dropped by {@code nonEmpty}'s {@code > 0} filter — silently, and only
     * as an accident of that filter's bound. Left unfloored, the same input would now throw out of
     * {@code map} instead, which is the very outage being closed, just relocated.
     */
    private static int countOf(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }
}
