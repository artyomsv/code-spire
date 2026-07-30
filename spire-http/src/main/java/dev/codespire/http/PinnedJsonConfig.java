package dev.codespire.http;

import java.util.Map;

/**
 * What the shared client needs to talk to one API host.
 *
 * @param apiName                 name used in I/O-failure messages ("Jira API", "GitHub API")
 * @param baseUrl                 API root; a trailing slash is trimmed
 * @param authorization           the finished {@code Authorization} header value — the adapter builds
 *                                it, so basic/bearer/token schemes stay the adapter's business
 * @param headers                 additional request headers, e.g. {@code Accept} and an API version
 * @param rejectedCredentialHint  what to tell the operator when a 2xx arrives that is not JSON, which
 *                                means the request reached a sign-in page
 */
public record PinnedJsonConfig(String apiName, String baseUrl, String authorization,
                               Map<String, String> headers, String rejectedCredentialHint) {

    public PinnedJsonConfig {
        require(apiName, "apiName");
        require(baseUrl, "baseUrl");
        require(authorization, "authorization");
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("PinnedJsonConfig '" + name + "' is required");
        }
    }
}
