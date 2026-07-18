package dev.codespire.scm.bitbucket;

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

    public JsonNode putJson(String path, Object body) {
        try {
            return parse(send("PUT", path, mapper.writeValueAsString(body)));
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
                // Only safe reads follow redirects: replaying a POST against a
                // Location target could double-post a comment.
                if (!"GET".equals(method) && !"HEAD".equals(method)) {
                    throw new BitbucketApiException(status, method, path, "redirect on " + method + " refused");
                }
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new BitbucketApiException(status, method, path));
                target = target.resolve(location);
                requireSafeRedirectTarget(target, status, method, path);
                continue;
            }
            if (status / 100 != 2) {
                throw new BitbucketApiException(status, method, path, bodySnippet(response.body()));
            }
            return response.body();
        }
        throw new BitbucketApiException(310, method, path); // too many redirects
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
            throw new BitbucketApiException(status, method, path, "redirect without a host refused");
        }
        if (host.equalsIgnoreCase(baseUri.getHost())) {
            return;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isPrivateAddress(address)) {
                    throw new BitbucketApiException(status, method, path,
                            "redirect to non-public address refused: " + host);
                }
            }
        } catch (UnknownHostException e) {
            throw new UncheckedIOException("Bitbucket API " + method + " " + path
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
            throw new UncheckedIOException("Unparseable Bitbucket API response", e);
        }
    }
}
