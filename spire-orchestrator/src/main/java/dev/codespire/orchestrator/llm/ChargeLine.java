package dev.codespire.orchestrator.llm;

import dev.codespire.contract.review.TokenType;

/**
 * One token dimension of one LLM call, priced.
 *
 * <p>The rate is carried, not just the cost, so the figure is reproducible as
 * {@code tokens x rate / 1_000_000} and a later catalog edit cannot reach it.
 *
 * @param rateMillicentsPerMillion null exactly when {@code mode} is {@link PricingMode#UNKNOWN}
 * @param costMillicents           null exactly when {@code mode} is {@link PricingMode#UNKNOWN} —
 *                                 never 0, which would be indistinguishable from an asserted zero
 */
public record ChargeLine(TokenType tokenType, int tokens, Long rateMillicentsPerMillion,
                         Long costMillicents, PricingMode mode) {

    /** A priced line. Rounds once, at the end, per the money rule. */
    public static ChargeLine metered(TokenType type, int tokens, long rate) {
        return new ChargeLine(type, tokens, rate, (long) tokens * rate / 1_000_000L, PricingMode.METERED);
    }

    public static ChargeLine unmetered(TokenType type, int tokens) {
        return new ChargeLine(type, tokens, 0L, 0L, PricingMode.UNMETERED);
    }

    public static ChargeLine unknown(TokenType type, int tokens) {
        return new ChargeLine(type, tokens, null, null, PricingMode.UNKNOWN);
    }
}
