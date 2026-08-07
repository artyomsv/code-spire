package dev.codespire.orchestrator.llm;

/**
 * How a model's tokens are costed. Pricing is orchestrator-owned (ADR-018), so this deliberately does
 * not live in the shared contract.
 */
public enum PricingMode {
    /** Operator-entered rates apply. Every rate must be greater than zero. */
    METERED,
    /**
     * Self-hosted or otherwise unbilled inference: cost is an ASSERTED zero. Distinct from
     * {@link #UNKNOWN} on purpose — conflating the two is what let an unpriced model read as free.
     */
    UNMETERED,
    /** Pricing could not be determined. Cost is NULL, never zero. */
    UNKNOWN
}
