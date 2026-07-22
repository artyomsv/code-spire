package dev.codespire.scm.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

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

    public JsonNode patchJson(String path, Object body) {
        try {
            return parse(send("PATCH", path, JSON_MEDIA, mapper.writeValueAsString(body)));
        } catch (JsonProcessingException e) {
            throw new GitHubApiException(0, "PATCH", path, "request serialization failed: " + e.getMessage());
        }
    }

    /**
     * GraphQL entry point — thread resolution has no REST equivalent. Returns the
     * {@code data} node; throws {@link GitHubApiException} when the response carries
     * top-level {@code errors}.
     */
    public JsonNode postGraphQl(String query, Map<String, Object> variables) {
        URI target = graphQlTarget();
        String path = target.getRawPath();
        try {
            String body = mapper.writeValueAsString(Map.of("query", query, "variables", variables));
            JsonNode response = parse(send("POST", path, target, JSON_MEDIA, body));
            JsonNode errors = response.path("errors");
            if (!errors.isEmpty()) {
                boolean rateLimited = false;
                for (JsonNode error : errors) {
                    rateLimited |= "RATE_LIMITED".equals(error.path("type").asText(""));
                }
                throw new GitHubApiException(200, "POST", path, "GraphQL errors: " + errors,
                        rateLimited, null);
            }
            return response.path("data");
        } catch (JsonProcessingException e) {
            throw new GitHubApiException(0, "POST", path, "request serialization failed: " + e.getMessage());
        }
    }

    /**
     * The GraphQL endpoint does not live under the REST {@code baseUri + path}
     * convention every other call uses. For github.com the API base is
     * {@code https://api.github.com} and GraphQL is the sibling {@code /graphql}.
     * For GitHub Enterprise Server the REST base is {@code https://<host>/api/v3};
     * its GraphQL endpoint is the sibling {@code https://<host>/api/graphql} — NOT
     * a path under {@code /api/v3} — so the {@code /v3} segment is swapped for
     * {@code /graphql} rather than appended to it.
     */
    private URI graphQlTarget() {
        String base = baseUri.toString();
        if (base.endsWith("/api/v3")) {
            return URI.create(base.substring(0, base.length() - "/v3".length()) + "/graphql");
        }
        return URI.create(base + "/graphql");
    }

    private String send(String method, String path, String accept, String jsonBody) {
        return send(method, path, URI.create(baseUri + path), accept, jsonBody);
    }

    /** Overload for targets outside the baseUri+path convention (e.g. {@link #postGraphQl}). */
    private String send(String method, String path, URI initialTarget, String accept, String jsonBody) {
        URI target = initialTarget;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpResponse<String> response = execute(method, path, target, accept, jsonBody);
            int status = response.statusCode();
            if (status / 100 == 3) {
                // Only safe reads follow redirects: replaying a POST against a
                // Location target could double-post a comment.
                if (!"GET".equals(method) && !"HEAD".equals(method)) {
                    throw new GitHubApiException(status, method, path, "redirect on " + method + " refused");
                }
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new GitHubApiException(status, method, path));
                target = target.resolve(location);
                requireSafeRedirectTarget(target, status, method, path);
                continue;
            }
            if (status / 100 != 2) {
                throw failure(status, method, path, response);
            }
            return response.body();
        }
        throw new GitHubApiException(310, method, path); // too many redirects
    }

    /**
     * SSRF guard on redirect hops (security review): a cross-host Location must
     * not point into loopback/link-local/private/unique-local address space.
     * Same-host targets skip the check — the base host is operator config, not
     * attacker data, and dev/test legitimately run against WireMock on localhost.
     */
    private void requireSafeRedirectTarget(URI target, int status, String method, String path) {
        String host = target.getHost();
        if (host == null) {
            throw new GitHubApiException(status, method, path, "redirect without a host refused");
        }
        if (host.equalsIgnoreCase(baseUri.getHost())) {
            return;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isPrivateAddress(address)) {
                    throw new GitHubApiException(status, method, path,
                            "redirect to non-public address refused: " + host);
                }
            }
        } catch (UnknownHostException e) {
            throw new UncheckedIOException("GitHub API " + method + " " + path
                    + " redirect target did not resolve", e);
        }
    }

    private static boolean isPrivateAddress(InetAddress address) {
        if (address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isAnyLocalAddress()) {
            return true;
        }
        byte[] raw = address.getAddress();
        return raw.length == 16 && (raw[0] & 0xFE) == 0xFC; // IPv6 unique-local fc00::/7
    }

    /** Truncated response-body excerpt for error messages — no headers, so no secrets. */
    private static String bodySnippet(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String cleaned = body.replaceAll("\\s+", " ").trim();
        return cleaned.length() <= 500 ? cleaned : cleaned.substring(0, 500) + "...";
    }

    /** GitHub signals rate limits mostly as 403 (+Retry-After / x-ratelimit-remaining: 0), not 429. */
    private static GitHubApiException failure(int status, String method, String path,
                                              HttpResponse<String> response) {
        String snippet = bodySnippet(response.body());
        Integer retryAfter = response.headers().firstValue("Retry-After")
                .map(GitHubClient::parseSecondsOrNull).orElse(null);
        boolean zeroRemaining = response.headers().firstValue("x-ratelimit-remaining")
                .map("0"::equals).orElse(false);
        boolean rateLimited = status == 429 || (status == 403 && (retryAfter != null || zeroRemaining
                || snippet.toLowerCase(Locale.ROOT).contains("rate limit")));
        return new GitHubApiException(status, method, path, snippet, rateLimited, retryAfter);
    }

    private static Integer parseSecondsOrNull(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
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
                && effectivePort(baseUri) == effectivePort(target);
    }

    /** -1 (no explicit port) normalizes to the scheme default, so ":443" still matches. */
    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private JsonNode parse(String body) {
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            throw new UncheckedIOException("Unparseable GitHub API response", e);
        }
    }
}
