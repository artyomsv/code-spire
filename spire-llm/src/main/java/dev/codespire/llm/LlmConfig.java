package dev.codespire.llm;

/**
 * LLM configuration. There is NO default provider or model — the operator
 * chooses at configuration time and missing values fail fast (ADR-001).
 *
 * @param baseUrl OpenAI-compatible endpoint (OpenAI, Azure, Ollama, vLLM, a gateway)
 * @param apiKey  the operator's own key ("none" is acceptable for local runtimes)
 * @param model   model name — required, no default
 */
public record LlmConfig(String baseUrl, String apiKey, String model, double temperature) {

    public LlmConfig {
        require(baseUrl, "baseUrl");
        require(apiKey, "apiKey");
        require(model, "model");
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "LLM config '" + name + "' is required — no defaults, choose your provider explicitly");
        }
    }
}
