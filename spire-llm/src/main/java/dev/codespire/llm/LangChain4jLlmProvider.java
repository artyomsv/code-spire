package dev.codespire.llm;

import dev.codespire.contract.llm.Completion;
import dev.codespire.contract.llm.ModelParamProfile;
import dev.codespire.contract.llm.ModelParams;
import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.port.LlmProvider;
import dev.codespire.contract.review.ModelUsage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.output.TokenUsage;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Default LlmProvider implementation behind LangChain4j (ADR: LangChain4j is
 * the default impl of the port, swappable). Synchronous under the hood —
 * callers are @Blocking worker threads; the CompletionStage is the port's
 * contract, not a promise of internal async I/O.
 *
 * <p>One class serves every backend: the wrapped {@link ChatModel} varies
 * (OpenAI-compatible, Anthropic, Gemini) and so does {@code paramFactory} — how
 * a request's parameters are shaped. OpenAI uses the per-model {@link
 * ModelParamProfile} dialect ({@link #requestParameters}); the native Anthropic
 * and Gemini clients take the profile-free {@link #nativeParameters} (temperature
 * + output cap only), which is all their APIs need.
 */
public class LangChain4jLlmProvider implements LlmProvider {

    /**
     * Output cap applied when the caller leaves {@link ModelParams#maxTokens()}
     * unset — the paid call must never run without a bound (cost control).
     */
    static final int DEFAULT_MAX_OUTPUT_TOKENS = 4096;

    private final ChatModel model;
    private final String id;
    private final Function<ModelParams, ChatRequestParameters> paramFactory;

    /** Defaults to the OpenAI dialect — the historical behavior + the {@link #openAiCompatible} path. */
    public LangChain4jLlmProvider(ChatModel model, String id) {
        this(model, id, LangChain4jLlmProvider::requestParameters);
    }

    public LangChain4jLlmProvider(ChatModel model, String id,
                                  Function<ModelParams, ChatRequestParameters> paramFactory) {
        this.model = model;
        this.id = id;
        this.paramFactory = paramFactory;
    }

    /** Builds the provider for any OpenAI-compatible endpoint from explicit config. */
    public static LangChain4jLlmProvider openAiCompatible(LlmConfig config) {
        // Temperature/max-tokens are applied per-request from the model's parameter
        // profile, NOT baked into the model here — otherwise a baked-in temperature
        // would leak onto reasoning models that reject it (it survives the merge).
        ChatModel chatModel = OpenAiChatModel.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey())
                .modelName(config.model())
                .timeout(Duration.ofSeconds(60)) // ADR-013 LLM budget
                .build();
        return new LangChain4jLlmProvider(chatModel, "openai-compatible/" + config.model());
    }

    /** Builds the provider for Anthropic's native API from explicit config. */
    public static LangChain4jLlmProvider anthropic(LlmConfig config) {
        // Anthropic requires max_tokens on every request; nativeParameters always sends
        // one (the operator's cap or DEFAULT_MAX_OUTPUT_TOKENS), and a build-time default
        // is set too so the paid call is bounded even if a request slips through unset.
        ChatModel chatModel = AnthropicChatModel.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey())
                .modelName(config.model())
                .maxTokens(DEFAULT_MAX_OUTPUT_TOKENS)
                .timeout(Duration.ofSeconds(60)) // ADR-013 LLM budget
                .build();
        return new LangChain4jLlmProvider(chatModel, "anthropic/" + config.model(),
                LangChain4jLlmProvider::nativeParameters);
    }

    /** Builds the provider for Google's native Gemini API from explicit config. */
    public static LangChain4jLlmProvider gemini(LlmConfig config) {
        ChatModel chatModel = GoogleAiGeminiChatModel.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey())
                .modelName(config.model())
                .timeout(Duration.ofSeconds(60)) // ADR-013 LLM budget
                .build();
        return new LangChain4jLlmProvider(chatModel, "gemini/" + config.model(),
                LangChain4jLlmProvider::nativeParameters);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public CompletionStage<Completion> complete(Prompt prompt, ModelParams params) {
        try {
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(
                            SystemMessage.from(prompt.system()),
                            UserMessage.from(prompt.user())))
                    .parameters(paramFactory.apply(params))
                    .build();
            ChatResponse response = model.chat(request);
            TokenUsage usage = response.tokenUsage();
            return CompletableFuture.completedFuture(new Completion(
                    response.aiMessage().text(),
                    new ModelUsage(params.model(),
                            usage != null && usage.inputTokenCount() != null ? usage.inputTokenCount() : 0,
                            usage != null && usage.outputTokenCount() != null ? usage.outputTokenCount() : 0,
                            0L))); // cost accounting lands with the pricing table
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Build the OpenAI request parameters from the model's parameter profile — no
     * dialect is hardcoded. The output cap goes on {@code max_tokens} or
     * {@code max_completion_tokens} per the profile (or neither), temperature is
     * omitted for models that only accept the default, and {@code reasoning_effort}
     * / any operator-supplied extra params are passed through.
     *
     * <p>Passed to {@code model.chat(...)} via the request parameters: the real
     * {@link OpenAiChatModel} keeps its defaults as {@link OpenAiChatRequestParameters}
     * so the merge preserves these OpenAI-specific fields on the wire. (A bare mock
     * {@code ChatModel} would merge through the base interface and drop them — which
     * is why this method is unit-tested directly, not through a mock's merge.)
     * Package-private for that direct test.
     */
    static OpenAiChatRequestParameters requestParameters(ModelParams params) {
        ModelParamProfile profile = params.profile();
        OpenAiChatRequestParameters.Builder b = OpenAiChatRequestParameters.builder();

        if (profile.supportsTemperature()) {
            b.temperature(params.temperature());
        }
        // The paid call carries a hard output cap (cost control) unless the model rejects one.
        Integer cap = params.maxTokens() != null ? params.maxTokens() : DEFAULT_MAX_OUTPUT_TOKENS;
        switch (profile.outputTokenParam()) {
            case MAX_TOKENS -> b.maxOutputTokens(cap);
            case MAX_COMPLETION_TOKENS -> b.maxCompletionTokens(cap);
            case NONE -> { /* model rejects an explicit output cap */ }
        }
        if (profile.reasoningEffort() != null) {
            b.reasoningEffort(profile.reasoningEffort());
        }
        if (!profile.extraParams().isEmpty()) {
            b.customParameters(profile.extraParams());
        }
        return b.build();
    }

    /**
     * Request parameters for the native Anthropic/Gemini clients: a hard output cap
     * plus temperature — the only knobs those APIs need here. The OpenAI-specific
     * {@link ModelParamProfile} dialect (max_completion_tokens, reasoning_effort,
     * custom params) does not apply and is ignored, but {@code supportsTemperature}
     * DOES: newer Claude models (e.g. Fable) deprecate {@code temperature} and reject
     * the request outright when it is sent, so it is omitted when the profile says so.
     * Package-private for direct unit testing.
     */
    static ChatRequestParameters nativeParameters(ModelParams params) {
        // The paid call always carries a bound (cost control) — the operator's cap or the default.
        int cap = params.maxTokens() != null ? params.maxTokens() : DEFAULT_MAX_OUTPUT_TOKENS;
        var b = ChatRequestParameters.builder().maxOutputTokens(cap);
        if (params.profile().supportsTemperature()) {
            b.temperature(params.temperature());
        }
        return b.build();
    }
}
