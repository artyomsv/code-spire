package dev.codespire.orchestrator.llm;

import java.time.Instant;
import java.util.Map;

/**
 * A catalog model as the API returns it. {@code pricingMode} is "METERED" or "UNMETERED"; {@code rates}
 * maps a {@code TokenType} name to millicents per 1,000,000 tokens (how providers quote pricing) and is
 * empty under UNMETERED. The parameter profile ({@code outputTokenParam}/{@code supportsTemperature}/
 * {@code reasoningEffort}/{@code extraParams}) declares the model's API dialect (ADR-018).
 */
public record LlmModelView(
        String id,
        String type,
        String name,
        String label,
        String pricingMode,
        Map<String, Long> rates,
        String outputTokenParam,
        boolean supportsTemperature,
        String reasoningEffort,
        Map<String, Object> extraParams,
        boolean enabled,
        Instant createdAt) {
}
