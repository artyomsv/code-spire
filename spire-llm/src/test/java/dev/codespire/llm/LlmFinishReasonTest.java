package dev.codespire.llm;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.codespire.contract.llm.Completion;
import dev.codespire.contract.llm.ModelParams;
import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.port.LlmProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The provider's own "I stopped because I ran out of room" signal must reach {@link Completion}.
 *
 * <p>Every other test of this behaviour builds a {@code Completion} by hand — the parser's, and the
 * worker's fake model — so all of them stay green if this mapping is deleted. Reaching the real
 * translation needs a real HTTP response with a real {@code finish_reason} in it, which is what this
 * class is for.
 *
 * <p>Both values are asserted. A test that only checked the {@code length} case would pass against a
 * provider that reported EVERY response as capped, and that failure marks every clean review as
 * degraded — louder than the bug it was meant to catch.
 */
class LlmFinishReasonTest {

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
    void aResponseStoppedAtTheOutputLimitIsReportedAsCapped() {
        assertTrue(complete("length").outputCapped(),
                "finish_reason=length is the provider saying it ran out of output budget");
    }

    @Test
    void aResponseTheModelFinishedOnItsOwnTermsIsNotCapped() {
        assertFalse(complete("stop").outputCapped(),
                "a normal completion must not be reported as cut off");
    }

    private Completion complete(String finishReason) {
        server.stubFor(any(anyUrl()).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {
                          "id": "TEST-COMPLETION",
                          "object": "chat.completion",
                          "created": 1,
                          "model": "TEST-MODEL",
                          "choices": [{
                            "index": 0,
                            "message": {"role": "assistant", "content": "{\\"summary\\":\\"s\\",\\"findings\\":[]}"},
                            "finish_reason": "%s"
                          }],
                          "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
                        }
                        """.formatted(finishReason))));

        LlmProvider provider = LangChain4jLlmProvider.openAiCompatible(new LlmConfig(
                "http://localhost:" + server.port(), "TEST-KEY", "TEST-MODEL", 0.2, Duration.ofSeconds(10)));
        return provider.complete(new Prompt("system", "user"), new ModelParams("TEST-MODEL", 0.2, 64))
                .toCompletableFuture().join();
    }
}
