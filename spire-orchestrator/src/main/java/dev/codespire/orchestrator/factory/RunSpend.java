package dev.codespire.orchestrator.factory;

import java.util.Map;

/** Priced subtotal in millicents, missing prices, and recorded tokens; a subtotal is not a total. */
public record RunSpend(long priced, int unpricedLines, Map<String, Long> tokensByType) {

    public RunSpend {
        tokensByType = Map.copyOf(tokensByType);
    }

    public RunCost cost() {
        if (tokensByType.isEmpty()) return RunCost.unknown();
        return unpricedLines > 0 ? RunCost.unknown() : RunCost.of(priced);
    }
}
