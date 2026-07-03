package dev.codespire.llm;

import dev.codespire.contract.llm.Completion;
import dev.codespire.contract.llm.ModelParams;
import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.port.LlmProvider;
import dev.codespire.contract.review.ModelUsage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
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

    private final ChatModel model;
    private final String id;

    public LangChain4jLlmProvider(ChatModel model, String id) {
        this.model = model;
        this.id = id;
    }

    /** Builds the provider for any OpenAI-compatible endpoint from explicit config. */
    public static LangChain4jLlmProvider openAiCompatible(LlmConfig config) {
        ChatModel chatModel = OpenAiChatModel.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey())
                .modelName(config.model())
                .temperature(config.temperature())
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
            ChatResponse response = model.chat(List.of(
                    SystemMessage.from(prompt.system()),
                    UserMessage.from(prompt.user())));
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
}
