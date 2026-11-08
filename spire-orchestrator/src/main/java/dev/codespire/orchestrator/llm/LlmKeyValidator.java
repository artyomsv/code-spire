package dev.codespire.orchestrator.llm;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    /**
     * A client of its own per check, shut down when the check has its answer. Shutting it down closes
     * the connection, which is what ends a body nobody reads or one that stalls; a shared client would
     * keep that connection, and pool it for the next check.
     */
    private static HttpClient newClient() {
        return HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * @throws BadRequestException whose plain-text body is a fixed {@link KeyCheckReason}. The body
     *         matters: the dialog shows it, and an exception built from a bare message has none, which
     *         left the operator with "(400)".
     */
    public void ping(String type, String baseUrl, String apiKey) {
        Answer answer = call(authenticated(type, baseUrl, apiKey), type);
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
        Answer answer = call(request, type);
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
     * Sends the check under ONE deadline for headers and body together. The request timeout alone ends
     * at the headers, so a body that stalled after them held the thread with no limit. On expiry the
     * client is shut down, which closes the exchange rather than abandoning it. A body is read only
     * when its reason depends on it, and then only up to {@link KeyCheckReason#MAX_ERROR_BODY_BYTES};
     * every other body is not read at all ({@link BoundedBody}), and its connection closes with the
     * client.
     *
     * <p>A failure is logged by its kind only. The HTTP client builds its exception text from bytes the
     * provider sent, which can echo the key, so neither that text nor the throwable is logged.
     */
    private Answer call(HttpRequest request, String type) {
        HttpClient client = newClient();
        try {
            return await(client, request, type);
        } finally {
            client.shutdownNow();
        }
    }

    private Answer await(HttpClient client, HttpRequest request, String type) {
        CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request, info -> new BoundedBody(
                KeyCheckReason.needsBody(type, info.statusCode()) ? KeyCheckReason.MAX_ERROR_BODY_BYTES : 0));
        HttpResponse<byte[]> response;
        try {
            response = pending.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            return failed(request, KeyCheckReason.TIMEOUT, "deadline");
        } catch (ExecutionException e) {
            boolean timedOut = e.getCause() instanceof HttpTimeoutException;
            return failed(request, timedOut ? KeyCheckReason.TIMEOUT : KeyCheckReason.UNREACHABLE,
                    e.getCause() == null ? "unknown" : e.getCause().getClass().getSimpleName());
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            return failed(request, KeyCheckReason.UNREACHABLE, "interrupted");
        }
        int status = response.statusCode();
        return status / 100 == 2
                ? new Answer(status, null)
                : new Answer(status, KeyCheckReason.forStatus(type, status, response.body()));
    }

    private static Answer failed(HttpRequest request, KeyCheckReason reason, String kind) {
        LOG.warnf("LLM key check to host %s failed (%s): %s", request.uri().getHost(), kind, reason.name());
        return new Answer(0, reason);
    }

    private static BadRequestException badRequest(String reason) {
        return new BadRequestException(Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.TEXT_PLAIN).entity(reason).build());
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
