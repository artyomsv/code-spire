package dev.codespire.orchestrator.llm;

import java.util.Map;

/**
 * Create/update payload for a catalog model.
 *
 * <p>{@code pricingMode} is "METERED" or "UNMETERED". Under METERED, {@code rates} maps a
 * {@code TokenType} name to millicents per 1,000,000 tokens and must contain a rate greater than zero
 * for at least INPUT and OUTPUT — the two dimensions every vendor reports on every call. The optional
 * dimensions (CACHED_INPUT, CACHE_WRITE, REASONING) may be omitted, because a model that does not bill
 * for them cannot be asked to price them.
 *
 * <p>Under UNMETERED, {@code rates} must be empty: the cost is an asserted zero.
 */
public record LlmModelInput(
        String type,
        String name,
        String label,
        String pricingMode,
        Map<String, Long> rates,
        String outputTokenParam,
        Boolean supportsTemperature,
        String reasoningEffort,
        Map<String, Object> extraParams,
        Boolean enabled) {
}
