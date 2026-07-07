package dev.codespire.orchestrator.llm;

import java.util.Map;

/**
 * Create/update payload for a catalog model. Prices are millicents per 1,000,000
 * tokens (the UI accepts plain dollars and converts). The parameter-profile fields
 * are optional and default to the classic Chat Completions dialect (max_tokens +
 * temperature) when omitted.
 */
public record LlmModelInput(
        String type,
        String name,
        String label,
        Long inputPriceMillicentsPerMillion,
        Long outputPriceMillicentsPerMillion,
        String outputTokenParam,
        Boolean supportsTemperature,
        String reasoningEffort,
        Map<String, Object> extraParams,
        Boolean enabled) {
}
