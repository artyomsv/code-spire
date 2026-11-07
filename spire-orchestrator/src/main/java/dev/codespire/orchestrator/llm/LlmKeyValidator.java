package dev.codespire.orchestrator.llm;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
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

    /** How long a key check waits for the provider, to connect and to answer. */
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /**
     * @throws BadRequestException whose plain-text body is a fixed {@link KeyCheckReason}. The body
     *         matters: the dialog shows it, and an exception built from a bare message has none, which
     *         left the operator with "(400)".
     */
    public void ping(String type, String baseUrl, String apiKey) {
        Answer answer = call(authenticated(type, baseUrl, apiKey));
        if (!answer.ok()) {
            throw badRequest(answer.message());
        }
    }

    /** The per-type authenticated {@code /models} request. Key travels in a header, never the URL. */
    private HttpRequest authenticated(String type, String baseUrl, String apiKey) {
        URI uri = URI.create(trimTrailingSlash(baseUrl) + "/models");
        HttpRequest.Builder req = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET();
        switch (type == null ? "" : type) {
            case "openai" -> req.header("Authorization", "Bearer " + apiKey);
            case "anthropic" -> req.header("x-api-key", apiKey).header("anthropic-version", "2023-06-01");
            case "gemini" -> req.header("x-goog-api-key", apiKey);
            default -> throw badRequest("Unsupported LLM provider type '" + type + "'");
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
            return new CheckOutcome(false, 0, String.valueOf(e.getResponse().getEntity()));
        }
        Answer answer = call(request);
        return new CheckOutcome(answer.ok(), answer.status(), answer.ok() ? null : answer.message());
    }

    /** One key check call: the status (0 when there was no answer) and, when it failed, why. */
    private record Answer(int status, KeyCheckReason reason) {

        boolean ok() {
            return reason == null;
        }

        String message() {
            return reason.message(status);
        }
    }

    /**
     * Sends the check. A successful body is closed unread (a models list is not needed, and closing
     * cancels it); an error body is read only up to {@link KeyCheckReason#MAX_ERROR_BODY_BYTES}.
     */
    private Answer call(HttpRequest request) {
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            LOG.warnf(e, "LLM key check timed out for host %s", request.uri().getHost());
            return new Answer(0, KeyCheckReason.TIMEOUT);
        } catch (IOException e) {
            LOG.warnf(e, "LLM key check call failed for host %s", request.uri().getHost());
            return new Answer(0, KeyCheckReason.UNREACHABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Answer(0, KeyCheckReason.UNREACHABLE);
        }
        int status = response.statusCode();
        if (status / 100 == 2) {
            closeUnread(response.body());
            return new Answer(status, null);
        }
        return new Answer(status, KeyCheckReason.forStatus(status, response.body()));
    }

    private static void closeUnread(InputStream body) {
        try {
            body.close();
        } catch (IOException ignored) {
            // The answer is already known; a failed close changes nothing about it.
        }
    }

    private static BadRequestException badRequest(String reason) {
        return new BadRequestException(Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.TEXT_PLAIN).entity(reason).build());
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
