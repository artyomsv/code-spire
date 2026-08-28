package dev.codespire.llm;

import java.time.Duration;

/**
 * LLM configuration. There is NO default provider or model — the operator
 * chooses at configuration time and missing values fail fast (ADR-001).
 *
 * @param baseUrl OpenAI-compatible endpoint (OpenAI, Azure, Ollama, vLLM, a gateway)
 * @param apiKey  the operator's own key ("none" is acceptable for local runtimes)
 * @param model   model name — required, no default
 * @param timeout how long one request may take. Unlike the credentials above this DOES default:
 *                it is an operational bound rather than something only the operator can know.
 */
public record LlmConfig(String baseUrl, String apiKey, String model, double temperature, Duration timeout) {

    /**
     * Request budget when the caller names none.
     *
     * <p>Was 60s, hardcoded at three call sites. That is not enough for a reasoning model on a real
     * diff: a 17k-input-token review of an 11-file pull request spent its whole output budget and was
     * still cut off, and raising the output cap only moved the failure onto this timeout. It is also
     * the value that must stay below the Kafka ack threshold — see the worker's {@code commands-in}
     * channel, where the two were equal and a slow call killed the consumer.
     */
    public static final String DEFAULT_TIMEOUT_SECONDS = "180";

    /**
     * The same value as a {@link Duration}. Derived from the string rather than written twice: the
     * annotation form has to be a compile-time constant, and the number had already begun to spread
     * across the config property, this class and the deployment descriptor.
     */
    public static final Duration DEFAULT_TIMEOUT =
            Duration.ofSeconds(Long.parseLong(DEFAULT_TIMEOUT_SECONDS));

    public LlmConfig {
        require(baseUrl, "baseUrl");
        require(apiKey, "apiKey");
        require(model, "model");
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("LLM config 'timeout' must be positive, got " + timeout);
        }
    }

    /** The common case: every credential field named, the request budget left at its default. */
    public LlmConfig(String baseUrl, String apiKey, String model, double temperature) {
        this(baseUrl, apiKey, model, temperature, DEFAULT_TIMEOUT);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "LLM config '" + name + "' is required — no defaults, choose your provider explicitly");
        }
    }
}
