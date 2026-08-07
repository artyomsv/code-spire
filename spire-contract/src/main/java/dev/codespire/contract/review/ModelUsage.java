package dev.codespire.contract.review;

import java.util.List;

/**
 * What an LLM adapter reports about one call: which model, and how many tokens of each billing
 * dimension.
 *
 * <p><b>No money.</b> Pricing needs the operator-entered catalog, which only the orchestrator owns
 * (ADR-018), so an adapter cannot compute a cost and — after this type lost its cost field — cannot
 * express one either. The field it replaced was always zero and its own comment said pricing happened
 * elsewhere, which is the kind of documented lie that eventually gets believed.
 *
 * @param counts        a partition — each token counted once, under exactly one {@link TokenType}
 * @param reportedTotal the vendor's OWN total for the call, kept as the independent check on the
 *                      partition rather than as a derived convenience
 * @param reconciled    whether {@code counts} sums to {@code reportedTotal}. False means the
 *                      breakdown could not be trusted and {@code counts} holds a single
 *                      {@link TokenType#TOTAL} line instead.
 */
public record ModelUsage(String model, List<TokenCount> counts, int reportedTotal, boolean reconciled) {

    public ModelUsage {
        counts = counts == null ? List.of() : List.copyOf(counts);
    }

    /** Tokens recorded for one dimension; 0 when the vendor did not report it. */
    public int tokensOf(TokenType type) {
        int total = 0;
        for (TokenCount count : counts) {
            if (count.type() == type) {
                total += count.tokens();
            }
        }
        return total;
    }

    /** A plain input/output call — the shape every vendor reports and most tests need. */
    public static ModelUsage of(String model, int input, int output) {
        return new ModelUsage(model,
                List.of(new TokenCount(TokenType.INPUT, input), new TokenCount(TokenType.OUTPUT, output)),
                input + output, true);
    }
}
