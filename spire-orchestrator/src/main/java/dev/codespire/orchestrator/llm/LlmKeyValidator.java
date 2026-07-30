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
        HttpRequest request = authenticated(type, baseUrl, apiKey);
        interpret(send(request, request.uri()));
    }

    /** The per-type authenticated {@code /models} request. Key travels in a header, never the URL. */
    private HttpRequest authenticated(String type, String baseUrl, String apiKey) {
        URI uri = URI.create(trimTrailingSlash(baseUrl) + "/models");
        HttpRequest.Builder req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET();
        switch (type == null ? "" : type) {
            case "openai" -> req.header("Authorization", "Bearer " + apiKey);
            case "anthropic" -> req.header("x-api-key", apiKey).header("anthropic-version", "2023-06-01");
            case "gemini" -> req.header("x-goog-api-key", apiKey);
            default -> throw new BadRequestException("Unsupported LLM provider type '" + type + "'");
        }
        return req.build();
    }

    /**
     * Outcome of a re-check, mirroring {@link dev.codespire.orchestrator.context.ContextKeyValidator.CheckOutcome}.
     * {@code status} is 0 when the provider could not be reached at all.
     */
    public record CheckOutcome(boolean ok, int status, String detail) {

        /**
         * True only for a genuine credential rejection (401/403) — deliberately excludes 0
         * (unreachable) and any other non-2xx status (5xx, ...), which are inconclusive rather
         * than proof the key is bad. The resource uses this to decide whether persisting the
         * outcome as {@code last_check_ok = FALSE} is warranted, instead of re-deriving the
         * status comparison itself.
         */
        public boolean isRejected() {
            return status == 401 || status == 403;
        }
    }

    /**
     * Re-check a stored key and REPORT the outcome instead of throwing, so the attention panel
     * can persist a rejection rather than surfacing it as a failed request.
     *
     * <p>401 and 403 both mean "key rejected" here. That is deliberately unlike
     * {@code ScmApiException.isUnauthorized()}, which is 401-only because at least one SCM
     * answers 403 for rate limiting; the LLM vendors in {@code TYPES} signal throttling with
     * 429, so 403 is unambiguous on this side. Do not "harmonise" the two.
     */
    public CheckOutcome check(String type, String baseUrl, String apiKey) {
        HttpRequest request;
        try {
            request = authenticated(type, baseUrl, apiKey);
        } catch (BadRequestException e) {
            return new CheckOutcome(false, 0, e.getMessage());
        }
        int status;
        try {
            status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            LOG.warnf(e, "LLM key check call failed for host %s", request.uri().getHost());
            return new CheckOutcome(false, 0, "Could not reach the LLM provider.");
        }
        if (status == 401 || status == 403) {
            return new CheckOutcome(false, status,
                    "The LLM provider rejected the API key (HTTP " + status + ").");
        }
        if (status / 100 != 2) {
            return new CheckOutcome(false, status,
                    "The LLM provider returned an unexpected status (" + status + ").");
        }
        return new CheckOutcome(true, status, null);
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
