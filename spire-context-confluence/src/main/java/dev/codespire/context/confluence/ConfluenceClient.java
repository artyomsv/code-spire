package dev.codespire.context.confluence;

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
 * Thin read-only HTTP layer over the Confluence REST API ({@code /rest/api/content}
 * — the same path on Cloud, where the wiki root already carries {@code /wiki}, and
 * on Data Center). Storage-format bodies come back as XHTML, stripped to plain text
 * by {@link ConfluenceHtml}, so no macro walker is needed.
 *
 * <p>As in the SCM adapters and {@code JiraClient}, redirects are followed MANUALLY
 * with host pinning — the bot's Authorization header is only ever sent to the
 * configured API host, never to a cross-host redirect target (SSRF guard). The
 * credential is never logged.
 */
public class ConfluenceClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_REDIRECTS = 3;

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI baseUri;
    private final String authorization;

    public ConfluenceClient(ConfluenceConfig config, ObjectMapper mapper) {
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER) // manual, host-pinned
                .connectTimeout(TIMEOUT)
                .build();
        this.mapper = mapper;
        this.baseUri = URI.create(config.baseUrl().replaceAll("/$", ""));
        this.authorization = authHeader(config);
    }

    private static String authHeader(ConfluenceConfig config) {
        if ("bearer".equals(config.authKind())) {
            return "Bearer " + config.secret();
        }
        String raw = config.username() + ":" + config.secret();
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public JsonNode getJson(String path) {
        return parse(send("GET", path));
    }

    private String send(String method, String path) {
        URI target = URI.create(baseUri + path);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpResponse<String> response = execute(method, path, target);
            int status = response.statusCode();
            if (status / 100 == 3) {
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new ConfluenceApiException(status, method, path));
                target = target.resolve(location);
                requireSafeRedirectTarget(target, status, method, path);
                continue;
            }
            if (status / 100 != 2) {
                throw new ConfluenceApiException(status, method, path, bodySnippet(response.body()));
            }
            // A 2xx must be JSON. A non-JSON 2xx (an HTML sign-in page) means the request was
            // redirected to authentication — the token was not accepted. Surface it clearly here
            // instead of as a raw JSON parse error deep in the caller.
            String body = response.body();
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!looksLikeJson(contentType, body)) {
                throw new ConfluenceApiException(status, method, path,
                        "expected JSON but received " + describeType(contentType)
                                + " — the request was redirected to a sign-in page, so the token was not accepted. "
                                + "Check the base URL is the Confluence wiki root and the token has REST API access. "
                                + "Body starts: " + bodySnippet(body));
            }
            return body;
        }
        throw new ConfluenceApiException(310, method, path); // too many redirects
    }

    private static boolean looksLikeJson(String contentType, String body) {
        if (contentType != null && contentType.toLowerCase().contains("json")) {
            return true;
        }
        String t = body == null ? "" : body.stripLeading();
        return t.startsWith("{") || t.startsWith("[");
    }

    private static String describeType(String contentType) {
        return contentType == null || contentType.isBlank() ? "a non-JSON response" : contentType;
    }

    /**
     * SSRF guard on redirect hops: a cross-host Location must not point into
     * loopback/link-local/private/unique-local address space. Same-host targets
     * skip the check — the base host is operator config, not attacker data, and
     * dev/test legitimately run against WireMock on localhost.
     */
    private void requireSafeRedirectTarget(URI target, int status, String method, String path) {
        String host = target.getHost();
        if (host == null) {
            throw new ConfluenceApiException(status, method, path, "redirect without a host refused");
        }
        if (host.equalsIgnoreCase(baseUri.getHost())) {
            return;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isPrivateAddress(address)) {
                    throw new ConfluenceApiException(status, method, path,
                            "redirect to non-public address refused: " + host);
                }
            }
        } catch (UnknownHostException e) {
            throw new UncheckedIOException("Confluence API " + method + " " + path
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

    private HttpResponse<String> execute(String method, String path, URI target) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(TIMEOUT)
                .header("Accept", "application/json");
        if (sameOrigin(target)) {
            builder.header("Authorization", authorization); // pinned to the API host only
        }
        builder.method(method, HttpRequest.BodyPublishers.noBody());
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException("Confluence API " + method + " " + path + " I/O failure", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted calling Confluence API", e);
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
            throw new UncheckedIOException("Unparseable Confluence API response", e);
        }
    }
}
