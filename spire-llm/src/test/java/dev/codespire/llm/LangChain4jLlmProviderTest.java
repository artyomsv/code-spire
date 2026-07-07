package dev.codespire.llm;

import dev.codespire.contract.llm.Completion;
import dev.codespire.contract.llm.ModelParams;
import dev.codespire.contract.llm.Prompt;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangChain4jLlmProviderTest {

    private static final Prompt PROMPT = new Prompt("system", "user");
    private static final ModelParams PARAMS = new ModelParams("test-model", 0.2, null);

    private static ChatModel returning(ChatResponse response) {
        return new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                return response;
            }
        };
    }

    private static ChatModel capturing(AtomicReference<ChatRequest> captured) {
        return new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                captured.set(request);
                return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
            }
        };
    }

    @Test
    void appliesPerCallTemperatureAndMaxTokens() {
        var captured = new AtomicReference<ChatRequest>();
        var provider = new LangChain4jLlmProvider(capturing(captured), "test");

        provider.complete(PROMPT, new ModelParams("test-model", 0.7, 512)).toCompletableFuture().join();

        assertEquals(0.7, captured.get().temperature());
        assertEquals(512, captured.get().maxOutputTokens());
    }

    @Test
    void unsetMaxTokensStillEnforcesAnOutputCap() {
        var captured = new AtomicReference<ChatRequest>();
        var provider = new LangChain4jLlmProvider(capturing(captured), "test");

        provider.complete(PROMPT, PARAMS).toCompletableFuture().join(); // PARAMS has maxTokens == null

        assertEquals(LangChain4jLlmProvider.DEFAULT_MAX_OUTPUT_TOKENS, captured.get().maxOutputTokens());
        assertEquals(0.2, captured.get().temperature());
    }

    @Test
    void mapsTextAndTokenUsage() throws Exception {
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("the review"))
                .tokenUsage(new TokenUsage(120, 34))
                .build();
        var provider = new LangChain4jLlmProvider(returning(response), "test");

        Completion completion = provider.complete(PROMPT, PARAMS).toCompletableFuture().get();
        assertEquals("the review", completion.text());
        assertEquals(120, completion.usage().tokensIn());
        assertEquals(34, completion.usage().tokensOut());
        assertEquals("test-model", completion.usage().model());
    }

    @Test
    void nullTokenUsageBecomesZeros() throws Exception {
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("ok"))
                .build();
        var provider = new LangChain4jLlmProvider(returning(response), "test");

        Completion completion = provider.complete(PROMPT, PARAMS).toCompletableFuture().get();
        assertEquals(0, completion.usage().tokensIn());
        assertEquals(0, completion.usage().tokensOut());
    }

    @Test
    void modelFailureSurfacesAsFailedStage() {
        ChatModel failing = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                throw new IllegalStateException("provider down");
            }
        };
        var provider = new LangChain4jLlmProvider(failing, "test");

        var future = provider.complete(PROMPT, PARAMS).toCompletableFuture();
        assertTrue(future.isCompletedExceptionally());
        var thrown = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(IllegalStateException.class, thrown.getCause());
    }

    @Test
    void openAiCompatibleRequiresFullConfig() {
        assertThrows(IllegalArgumentException.class,
                () -> new LlmConfig("", "key", "model", 0.2));
        assertThrows(IllegalArgumentException.class,
                () -> new LlmConfig("http://localhost:34999/v1", "key", " ", 0.2));
        // fully-specified config builds without contacting the endpoint
        var provider = LangChain4jLlmProvider.openAiCompatible(
                new LlmConfig("http://localhost:34999/v1", "test-key", "test-model", 0.2));
        assertEquals("openai-compatible/test-model", provider.id());
    }

    @Test
    void completionExceptionUnwrapsFromJoin() {
        ChatModel failing = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                throw new IllegalStateException("boom");
            }
        };
        var provider = new LangChain4jLlmProvider(failing, "test");
        assertThrows(CompletionException.class,
                () -> provider.complete(PROMPT, PARAMS).toCompletableFuture().join());
    }
}
