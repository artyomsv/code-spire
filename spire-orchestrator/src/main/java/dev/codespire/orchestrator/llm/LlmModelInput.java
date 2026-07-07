package dev.codespire.orchestrator.llm;

/**
 * Create/update payload for a catalog model. Prices are millicents per 1,000,000
 * tokens (the UI accepts plain dollars and converts).
 */
public record LlmModelInput(
        String type,
        String name,
        String label,
        Long inputPriceMillicentsPerMillion,
        Long outputPriceMillicentsPerMillion,
        Boolean enabled) {
}
