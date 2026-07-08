package dev.codespire.orchestrator.llm;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.BadRequestException;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Validates an LLM provider's key on save — a cheap authenticated request to the
 * provider's models list (no tokens billed), so a bad key is rejected up front
 * rather than surfacing as a review failure. The baseUrl is SSRF-guarded by the
 * resource before this runs. Each provider authenticates its {@code /models} list
 * differently (OpenAI {@code Authorization: Bearer}, Anthropic {@code x-api-key} +
 * version, Gemini {@code x-goog-api-key}), so the header set is per-type.
 */
@ApplicationScoped
public class LlmKeyValidator {

    private static final Logger LOG = Logger.getLogger(LlmKeyValidator.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** @throws BadRequestException with a generic message if the key/model is rejected or unreachable. */
    public void ping(String type, String baseUrl, String apiKey) {
        URI uri = URI.create(trimTrailingSlash(baseUrl) + "/models");
        HttpRequest.Builder req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET();
        switch (type == null ? "" : type) {
            // Key travels in a header for every provider — never in the URL (no secret in logs).
            case "openai" -> req.header("Authorization", "Bearer " + apiKey);
            case "anthropic" -> req.header("x-api-key", apiKey).header("anthropic-version", "2023-06-01");
            case "gemini" -> req.header("x-goog-api-key", apiKey);
            default -> throw new BadRequestException("Unsupported LLM provider type '" + type + "'");
        }
        interpret(send(req.build(), uri));
    }

    private int send(HttpRequest req, URI uri) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            // Generic message to the client (no upstream echo); detail stays server-side.
            LOG.warnf(e, "LLM key validation call failed for host %s", uri.getHost());
            throw new BadRequestException("Could not reach the LLM provider to validate the key");
        }
    }

    private void interpret(int status) {
        if (status == 401 || status == 403) {
            throw new BadRequestException("The LLM provider rejected the API key");
        }
        if (status / 100 != 2) {
            throw new BadRequestException("The LLM provider returned an unexpected status (" + status + ")");
        }
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
