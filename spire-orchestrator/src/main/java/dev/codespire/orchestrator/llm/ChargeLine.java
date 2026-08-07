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

    /**
     * A priced line: {@code tokens × rate ÷ 1e6}, truncated to whole millicents.
     *
     * <p>The truncation is per LINE, so a call's total is the sum of truncated parts rather than the
     * truncation of the sum — the comment here used to claim it "rounds once, at the end", which is not
     * what this does and is not achievable at this grain: the line is the ledger's grain and each one
     * stores its own money figure. Rounding once would mean keeping a call's cost undivided, which is
     * exactly the per-type breakdown ADR-023 exists to record.
     *
     * <p>The loss is bounded by one millicent ($0.00001) per line, and a genuinely sub-millicent line
     * (100 cached tokens at $0.075/1M) records an exact {@code 0} — still distinguishable from an
     * asserted zero, because {@code mode} is {@code METERED} and the rate is on the row.
     */
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
