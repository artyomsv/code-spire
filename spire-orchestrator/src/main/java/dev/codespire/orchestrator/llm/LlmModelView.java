package dev.codespire.orchestrator.llm;

import java.time.Instant;
import java.util.Map;

/**
 * A catalog model as the API returns it. Prices are millicents (1/100,000 dollar)
 * per 1,000,000 tokens (how providers quote pricing). The parameter profile
 * ({@code outputTokenParam}/{@code supportsTemperature}/{@code reasoningEffort}/
 * {@code extraParams}) declares the model's API dialect (ADR-018).
 */
public record LlmModelView(
        String id,
        String type,
        String name,
        String label,
        long inputPriceMillicentsPerMillion,
        long outputPriceMillicentsPerMillion,
        String outputTokenParam,
        boolean supportsTemperature,
        String reasoningEffort,
        Map<String, Object> extraParams,
        boolean enabled,
        Instant createdAt) {
}
