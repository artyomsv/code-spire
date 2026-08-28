package dev.codespire.llm;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.codespire.contract.llm.ModelParams;
import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.port.LlmProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The request budget must actually reach the client each factory builds.
 *
 * <p>LangChain4j exposes no getter for a built model's timeout, so a factory that ignored
 * {@code config.timeout()} — or went back to the 60 seconds this used to hardcode at three separate
 * call sites — would be invisible to every other test in this module. The only way to observe it is
 * to make the endpoint slower than the budget and watch which one wins.
 *
 * <p>All three factories are covered rather than one. They are three separate lines that must each
 * be right, and the hardcoded value they replaced was likewise duplicated three times.
 */
class LlmRequestTimeoutTest {

    /** Longer than the budget below, so the budget is what decides the outcome. */
    private static final int RESPONSE_DELAY_MS = 5_000;

    private static final Duration BUDGET = Duration.ofMillis(300);

    private WireMockServer server;

    @BeforeEach
    void startSlowEndpoint() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        server.stubFor(any(anyUrl()).willReturn(aResponse()
                .withFixedDelay(RESPONSE_DELAY_MS)
                .withHeader("Content-Type", "application/json")
                .withBody("{}")));
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void theOpenAiCompatibleFactoryHonoursTheBudget() {
        assertTimesOut(LangChain4jLlmProvider::openAiCompatible);
    }

    @Test
    void theAnthropicFactoryHonoursTheBudget() {
        assertTimesOut(LangChain4jLlmProvider::anthropic);
    }

    @Test
    void theGeminiFactoryHonoursTheBudget() {
        assertTimesOut(LangChain4jLlmProvider::gemini);
    }

    /**
     * Asserts on the failure being a TIMEOUT, not on elapsed wall-clock. A client that ignored the
     * budget would sit through the delay and then fail on the stub's unusable body — a different
     * error, which is what makes this discriminating rather than merely red.
     */
    private void assertTimesOut(Function<LlmConfig, LlmProvider> factory) {
        LlmProvider provider = factory.apply(new LlmConfig(
                "http://localhost:" + server.port(), "TEST-KEY", "TEST-MODEL", 0.2, BUDGET));

        CompletionException thrown = assertThrows(CompletionException.class,
                () -> provider.complete(new Prompt("system", "user"), new ModelParams("TEST-MODEL", 0.2, 64))
                        .toCompletableFuture().join());

        assertTrue(mentionsTimeout(thrown),
                "expected the configured " + BUDGET.toMillis() + "ms budget to cut the call off, got: " + thrown);
    }

    private static boolean mentionsTimeout(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String message = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("timed out") || message.contains("timeout")
                    || t.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
        }
        return false;
    }
}
