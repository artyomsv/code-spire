package dev.codespire.orchestrator.llm;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A model as the API returns it, manual or from the catalog. {@code pricingMode} is "METERED" or
 * "UNMETERED"; {@code rates} maps a {@code TokenType} name to millicents per 1,000,000 tokens (how
 * providers quote pricing) and is empty under UNMETERED. The parameter profile
 * ({@code outputTokenParam}/{@code supportsTemperature}/{@code reasoningEffort}/{@code extraParams})
 * declares the model's API dialect (ADR-018).
 *
 * <p>{@code notBilled} lists the token types asserted not to be charged for. A type in neither
 * {@code rates} nor here is unpriced, and a call reporting it has an unknown cost.
 *
 * <p>{@code source} is "MANUAL" or "CATALOG". A catalog model has no row of its own, so its {@code id}
 * and {@code createdAt} are null; it cannot be edited or deleted here.
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
        Instant createdAt,
        List<String> notBilled,
        String source) {
}
