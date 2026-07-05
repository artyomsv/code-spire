package dev.codespire.scm.bitbucket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Thin HTTP layer over the Bitbucket Cloud REST API (api.bitbucket.org/2.0).
 * Redirects (the /diff endpoint 302s, SCM-MAPPING.md §3) are followed
 * MANUALLY with host pinning: the bot's Authorization header is only ever
 * sent to the configured API host, never to a cross-host redirect target
 * (security review finding L1). The token is never logged.
 */
public class BitbucketCloudClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_REDIRECTS = 3;

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI baseUri;
    private final String authorization;

    public BitbucketCloudClient(BitbucketCloudConfig config, ObjectMapper mapper) {
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER) // manual, host-pinned
                .connectTimeout(TIMEOUT)
                .build();
        this.mapper = mapper;
        this.baseUri = URI.create(config.baseUrl().replaceAll("/$", ""));
        this.authorization = config.usesBearerToken()
                ? "Bearer " + config.apiToken()
                : "Basic " + Base64.getEncoder().encodeToString(
                        (config.botUsername() + ":" + config.botAppPassword()).getBytes(StandardCharsets.UTF_8));
    }

    public JsonNode getJson(String path) {
        return parse(send("GET", path, null));
    }

    public String getText(String path) {
        return send("GET", path, null);
    }

    public JsonNode postJson(String path, Object body) {
        try {
            return parse(send("POST", path, mapper.writeValueAsString(body)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String send(String method, String path, String jsonBody) {
        URI target = URI.create(baseUri + path);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpResponse<String> response = execute(method, path, target, jsonBody);
            int status = response.statusCode();
            if (status / 100 == 3) {
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new BitbucketApiException(status, method, path));
                target = target.resolve(location);
                continue;
            }
            if (status / 100 != 2) {
                throw new BitbucketApiException(status, method, path);
            }
            return response.body();
        }
        throw new BitbucketApiException(310, method, path); // too many redirects
    }

    private HttpResponse<String> execute(String method, String path, URI target, String jsonBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(TIMEOUT)
                .header("Accept", "application/json, text/plain");
        if (sameOrigin(target)) {
            builder.header("Authorization", authorization); // pinned to the API host only
        }
        if (jsonBody != null) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException("Bitbucket API " + method + " " + path + " I/O failure", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted calling Bitbucket API", e);
        }
    }

    private boolean sameOrigin(URI target) {
        return baseUri.getScheme().equalsIgnoreCase(target.getScheme())
                && baseUri.getHost().equalsIgnoreCase(target.getHost())
                && baseUri.getPort() == target.getPort();
    }

    private JsonNode parse(String body) {
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            throw new UncheckedIOException("Unparseable Bitbucket API response", e);
        }
    }
}
