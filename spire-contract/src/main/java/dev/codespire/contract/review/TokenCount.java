package dev.codespire.contract.review;

/**
 * One token-billing dimension's count for a single LLM call.
 *
 * <p>Non-negative, refused rather than coerced. A vendor reporting a negative count (a proxy answering
 * {@code "total_tokens": -1} is enough — no hostile actor required) used to travel all the way to the
 * ledger's {@code llm_charge.tokens >= 0} insert and throw inside the {@code ReviewGenerated} handler
 * BEFORE comments were posted: the call is paid, the findings are computed, nothing reaches the pull
 * request, and every redelivery repeats it. Each reader that can produce a negative floors it at the
 * point it reads (see {@code TokenUsageMapper}); this constructor is the backstop, so a construction
 * site added later cannot reintroduce that outage by forgetting to.
 *
 * <p>It throws instead of clamping because a caller with a negative count has a bug in ITS arithmetic,
 * and a silent clamp here would hide it — the same "unknown quietly became a number" shape ADR-023
 * exists to remove. The floor belongs where the vendor's figure is read and can be judged.
 */
public record TokenCount(TokenType type, int tokens) {

    public TokenCount {
        if (tokens < 0) {
            throw new IllegalArgumentException("Token count for " + type + " is negative (" + tokens
                    + "). Floor a vendor's reported figure where it is read, not here.");
        }
    }
}
