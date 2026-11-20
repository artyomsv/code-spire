package dev.codespire.orchestrator.llm.catalog;

import dev.codespire.llmcatalog.CatalogLimits;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Reads the catalog file from its URL. Redirects are refused (a moved file is an operator's change to
 * make), the body is read to at most one byte past the cap, and a failure is reported as a status code
 * or an exception class only: nothing from the body reaches a log or the state row.
 *
 * <p>The https-only rule is not here: {@link LlmCatalogRefresh} refuses another scheme before it asks.
 */
public class CatalogFetcher {

    static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int NOT_MODIFIED = 304;

    private final HttpClient client;

    public CatalogFetcher(HttpClient client) {
        this.client = client;
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
        HttpRequest.Builder request = HttpRequest.newBuilder(url).timeout(TIMEOUT).GET();
        if (etag != null && !etag.isBlank()) {
            request.header("If-None-Match", etag);
        }
        try {
            HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                return read(response, body);
            }
        } catch (IOException e) {
            return new Failed(e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Failed(e.getClass().getSimpleName());
        }
    }

    private static FetchResult read(HttpResponse<InputStream> response, InputStream body) throws IOException {
        int status = response.statusCode();
        if (status == NOT_MODIFIED) {
            return new NotModified();
        }
        if (status < 200 || status > 299) {
            return new Failed("http_" + status);
        }
        byte[] bytes = body.readNBytes(CatalogLimits.MAX_BYTES + 1);
        if (bytes.length > CatalogLimits.MAX_BYTES) {
            return new Failed("body_over_" + CatalogLimits.MAX_BYTES + "_bytes");
        }
        return new Fetched(new String(bytes, StandardCharsets.UTF_8), response.headers().firstValue("ETag").orElse(null));
    }
}
