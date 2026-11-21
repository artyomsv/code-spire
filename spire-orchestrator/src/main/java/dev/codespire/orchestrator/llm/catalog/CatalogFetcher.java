package dev.codespire.orchestrator.llm.catalog;

import dev.codespire.llmcatalog.CatalogLimits;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Reads the catalog file from its URL. Redirects are refused (a moved file is an operator's change to
 * make), the body is read to at most one byte past the cap, and a failure is reported as a status code
 * or an exception class only: nothing from the body reaches a log or the state row.
 *
 * <p>One deadline covers the whole exchange, body included. {@code HttpRequest.timeout} ends when the
 * headers arrive, so a server that sent headers and then stalled would otherwise hold the read, and the
 * synchronized refresh behind it, for ever.
 *
 * <p>The https-only rule is not here: {@link LlmCatalogRefresh} refuses another scheme before it asks.
 */
public class CatalogFetcher {

    static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int NOT_MODIFIED = 304;
    private static final int FIRST_SUCCESS = 200;
    private static final int LAST_SUCCESS = 299;

    private final HttpClient client;
    private final Duration deadline;

    public CatalogFetcher(HttpClient client) {
        this(client, TIMEOUT);
    }

    /** For tests: a shorter deadline for the whole exchange. */
    CatalogFetcher(HttpClient client, Duration deadline) {
        this.client = client;
        this.deadline = deadline;
    }

    /** The client production uses; tests build theirs from it so the redirect policy is the real one. */
    public static HttpClient.Builder clientBuilder() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(TIMEOUT);
    }

    /** What one read of the URL found. */
    public sealed interface FetchResult permits NotModified, Fetched, Failed {
    }

    /** 304: the stored etag still names the current file. */
    public record NotModified() implements FetchResult {
    }

    /** 200 with a body within the cap. */
    public record Fetched(String body, String etag) implements FetchResult {
    }

    /** Anything else; {@code reason} is a status code or an exception class name, never body text. */
    public record Failed(String reason) implements FetchResult {
    }

    public FetchResult fetch(URI url, String etag) {
        HttpRequest.Builder request = HttpRequest.newBuilder(url).timeout(deadline).GET();
        if (etag != null && !etag.isBlank()) {
            request.header("If-None-Match", etag);
        }
        CompletableFuture<HttpResponse<byte[]>> exchange =
                client.sendAsync(request.build(), info -> new CappedBody(CatalogLimits.MAX_BYTES + 1));
        try {
            return read(exchange.get(deadline.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            exchange.cancel(true);
            return new Failed(e.getClass().getSimpleName());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return new Failed(cause.getClass().getSimpleName());
        } catch (InterruptedException e) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            return new Failed(e.getClass().getSimpleName());
        }
    }

    private static FetchResult read(HttpResponse<byte[]> response) {
        int status = response.statusCode();
        if (status == NOT_MODIFIED) {
            return new NotModified();
        }
        // With redirects off a 3xx arrives here, and this check is what refuses it (aRedirectIsAFailure).
        if (status < FIRST_SUCCESS || status > LAST_SUCCESS) {
            return new Failed("http_" + status);
        }
        byte[] bytes = response.body();
        if (bytes.length > CatalogLimits.MAX_BYTES) {
            return new Failed("body_over_" + CatalogLimits.MAX_BYTES + "_bytes");
        }
        return new Fetched(new String(bytes, StandardCharsets.UTF_8), response.headers().firstValue("ETag").orElse(null));
    }
}
