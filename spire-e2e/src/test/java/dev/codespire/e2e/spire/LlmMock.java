package dev.codespire.e2e.spire;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.e2e.support.Json;
import dev.codespire.e2e.support.Stack;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads WireMock's request journal.
 *
 * <p>This is the only place in the harness that sees INSIDE a review, and it sees it the way the model
 * does: the exact prompt text the worker sent. That removes any need to enable PromptLog — which is
 * opt-in and off by default precisely because a rendered prompt quotes source code and retrieved
 * ticket text — just so a test can observe what was assembled.
 */
public final class LlmMock {

    private LlmMock() {
    }

    /** Clears the journal, so {@link #prompts()} describes one scenario rather than the whole run. */
    public static void reset() {
        send(HttpRequest.newBuilder(URI.create(Stack.llmMockAdminUrl() + "/requests"))
                .timeout(Duration.ofSeconds(30))
                .DELETE());
    }

    /** @return every chat-completion request body the mock received, oldest first. */
    public static List<String> prompts() {
        JsonNode journal = Json.read(send(
                HttpRequest.newBuilder(URI.create(Stack.llmMockAdminUrl() + "/requests"))
                        .timeout(Duration.ofSeconds(30))
                        .GET()));

        List<String> bodies = new ArrayList<>();
        for (JsonNode entry : journal.get("requests")) {
            JsonNode request = entry.get("request");
            if (request.get("url").asText().contains("/chat/completions")) {
                bodies.add(request.get("body").asText());
            }
        }
        // WireMock returns newest first; scenarios read in the order the calls actually happened.
        return bodies.reversed();
    }

    private static String send(HttpRequest.Builder builder) {
        HttpRequest built = builder.build();
        try {
            HttpResponse<String> response =
                    Stack.http().send(built, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("llm-mock admin " + response.statusCode()
                        + " for " + built.uri() + ": " + response.body());
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException("llm-mock admin unreachable at " + built.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted calling llm-mock admin", e);
        }
    }
}
