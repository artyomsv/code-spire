package dev.codespire.scm.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Thin HTTP layer over the GitHub REST API (api.github.com). As in the Bitbucket
 * adapter, redirects are followed MANUALLY with host pinning — the bot's
 * Authorization header is only ever sent to the configured API host, never to a
 * cross-host redirect target (security review finding L1). The token is never
 * logged. The PR diff is requested with the {@code application/vnd.github.diff}
 * media type, which returns raw unified-diff text (SCM-MAPPING §3).
 */
public class GitHubClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_REDIRECTS = 3;
    private static final String JSON_MEDIA = "application/vnd.github+json";
    private static final String DIFF_MEDIA = "application/vnd.github.diff";
    private static final String API_VERSION = "2022-11-28";

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI baseUri;
    private final String authorization;

    public GitHubClient(GitHubConfig config, ObjectMapper mapper) {
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER) // manual, host-pinned
                .connectTimeout(TIMEOUT)
                .build();
        this.mapper = mapper;
        this.baseUri = URI.create(config.baseUrl().replaceAll("/$", ""));
        this.authorization = "Bearer " + config.apiToken();
    }

    public JsonNode getJson(String path) {
        return parse(send("GET", path, JSON_MEDIA, null));
    }

    /** The PR's unified diff (vnd.github.diff media type). */
    public String getDiff(String path) {
        return send("GET", path, DIFF_MEDIA, null);
    }

    public JsonNode postJson(String path, Object body) {
        try {
            return parse(send("POST", path, JSON_MEDIA, mapper.writeValueAsString(body)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String send(String method, String path, String accept, String jsonBody) {
        URI target = URI.create(baseUri + path);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpResponse<String> response = execute(method, path, target, accept, jsonBody);
            int status = response.statusCode();
            if (status / 100 == 3) {
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new GitHubApiException(status, method, path));
                target = target.resolve(location);
                continue;
            }
            if (status / 100 != 2) {
                throw new GitHubApiException(status, method, path);
            }
            return response.body();
        }
        throw new GitHubApiException(310, method, path); // too many redirects
    }

    private HttpResponse<String> execute(String method, String path, URI target, String accept, String jsonBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(TIMEOUT)
                .header("Accept", accept)
                .header("X-GitHub-Api-Version", API_VERSION);
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
            throw new UncheckedIOException("GitHub API " + method + " " + path + " I/O failure", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted calling GitHub API", e);
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
            throw new UncheckedIOException("Unparseable GitHub API response", e);
        }
    }
}
