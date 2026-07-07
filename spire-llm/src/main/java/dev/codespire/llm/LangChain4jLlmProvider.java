package dev.codespire.llm;

import dev.codespire.contract.llm.Completion;
import dev.codespire.contract.llm.ModelParamProfile;
import dev.codespire.contract.llm.ModelParams;
import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.port.LlmProvider;
import dev.codespire.contract.review.ModelUsage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.output.TokenUsage;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Default LlmProvider implementation behind LangChain4j (ADR: LangChain4j is
 * the default impl of the port, swappable). Synchronous under the hood —
 * callers are @Blocking worker threads; the CompletionStage is the port's
 * contract, not a promise of internal async I/O.
 */
public class LangChain4jLlmProvider implements LlmProvider {

    /**
     * Output cap applied when the caller leaves {@link ModelParams#maxTokens()}
     * unset — the paid call must never run without a bound (cost control).
     */
    static final int DEFAULT_MAX_OUTPUT_TOKENS = 4096;

    private final ChatModel model;
    private final String id;

    public LangChain4jLlmProvider(ChatModel model, String id) {
        this.model = model;
        this.id = id;
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
                    .parameters(requestParameters(params))
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
}
