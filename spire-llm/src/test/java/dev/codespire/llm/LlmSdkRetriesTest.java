package dev.codespire.llm;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.codespire.contract.llm.ModelParamProfile;
import dev.codespire.contract.llm.ModelParams;
import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.port.LlmProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One {@code complete} is one HTTP request (PR 456, H-1). LangChain4j retries a timeout, a rate limit and a 5xx twice
 * by default, inside one call: a large prompt was sent three times before the worker saw a failure, and three
 * timeouts outlasted the Kafka ack budget. The application owns retries, so each factory turns the library's off.
 *
 * <p>Counted against a real HTTP stub through the real adapters, since a fake {@code LlmProvider} cannot see the
 * library's loop. The prompt is large on purpose: it is the call the policy exists for.
 */
class LlmSdkRetriesTest {

    private static final Duration BUDGET = Duration.ofMillis(300);

    /** About 300,000 characters: the size of prompt a large window now sends. */
    private static final Prompt LARGE_PROMPT = new Prompt("TEST system", "TEST diff line\n".repeat(20_000));

    private WireMockServer server;

    @BeforeEach
    void start() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void theOpenAiCompatibleFactorySendsATimedOutLargePromptOnce() {
        assertOneRequestAfterTimeout(LangChain4jLlmProvider::openAiCompatible);
    }

    @Test
    void theAnthropicFactorySendsATimedOutLargePromptOnce() {
        assertOneRequestAfterTimeout(LangChain4jLlmProvider::anthropic);
    }

    @Test
    void theGeminiFactorySendsATimedOutLargePromptOnce() {
        assertOneRequestAfterTimeout(LangChain4jLlmProvider::gemini);
    }

    @Test
    void theOpenAiCompatibleFactorySendsARateLimitedPromptOnce() {
        assertOneRequestAfterStatus(LangChain4jLlmProvider::openAiCompatible, 429);
    }

    @Test
    void theAnthropicFactorySendsAFailingPromptOnce() {
        assertOneRequestAfterStatus(LangChain4jLlmProvider::anthropic, 503);
    }

    @Test
    void theGeminiFactorySendsAFailingPromptOnce() {
        assertOneRequestAfterStatus(LangChain4jLlmProvider::gemini, 503);
    }

    /**
     * The worker still retries what the library no longer does: a rate limit arrives as LangChain4j's
     * {@code RetriableException}, the type {@code LlmFailures} recognises by name and the review worker reports as
     * retryable, so the orchestrator's retry budget runs the review again.
     */
    @Test
    void aRateLimitStillArrivesAsTheRetriableTypeTheWorkerRetries() {
        server.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(429)
                .withHeader("Content-Type", "application/json").withBody("{}")));
        LlmProvider provider = LangChain4jLlmProvider.openAiCompatible(config());

        CompletionException thrown = assertThrows(CompletionException.class, () -> call(provider));

        assertTrue(hasCauseNamed(thrown, "dev.langchain4j.exception.RetriableException"),
                "expected a RetriableException in the cause chain, got: " + thrown);
    }

    private void assertOneRequestAfterTimeout(Function<LlmConfig, LlmProvider> factory) {
        server.stubFor(any(anyUrl()).willReturn(aResponse().withFixedDelay(5_000)
                .withHeader("Content-Type", "application/json").withBody("{}")));

        assertThrows(CompletionException.class, () -> call(factory.apply(config())));

        assertEquals(1, requestCount(), "a timed-out call must not be re-sent by the client library");
    }

    private void assertOneRequestAfterStatus(Function<LlmConfig, LlmProvider> factory, int status) {
        server.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody("{}")));

        assertThrows(CompletionException.class, () -> call(factory.apply(config())));

        assertEquals(1, requestCount(), "a " + status + " must not be re-sent by the client library");
    }

    private LlmConfig config() {
        return new LlmConfig("http://localhost:" + server.port(), "TEST-KEY", "TEST-MODEL", 0.2, BUDGET);
    }

    /** Temperature off, so the provider's own one-off "retry without temperature" cannot add a request. */
    private static void call(LlmProvider provider) {
        ModelParams params = new ModelParams("TEST-MODEL", 0.2, 64,
                new ModelParamProfile(ModelParamProfile.OutputTokenParam.MAX_TOKENS, false, null, Map.of()));
        provider.complete(LARGE_PROMPT, params).toCompletableFuture().join();
    }

    private int requestCount() {
        return server.findAll(anyRequestedFor(anyUrl())).size();
    }

    private static boolean hasCauseNamed(Throwable thrown, String className) {
        for (Throwable t = thrown; t != null; t = t.getCause() == t ? null : t.getCause()) {
            for (Class<?> c = t.getClass(); c != null; c = c.getSuperclass()) {
                if (className.equals(c.getName())) {
                    return true;
                }
            }
        }
        return false;
    }
}
