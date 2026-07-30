package dev.codespire.context.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.http.PinnedJsonClient;
import dev.codespire.http.PinnedJsonConfig;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Thin read-only HTTP layer over the Jira REST API (v2 — its {@code description} comes back as a
 * plain string, unlike v3's Atlassian Document Format, so no ADF walker is needed and Data Center is
 * covered by the same paths).
 *
 * <p>Transport, host-pinned redirects and the SSRF guard live in {@link PinnedJsonClient}, shared with
 * every other adapter. What stays here is what is actually Jira's: the auth scheme (Cloud uses basic
 * with the account email, self-managed a bearer PAT) and the base-URL advice in the sign-in hint.
 */
public class JiraClient {

    private final PinnedJsonClient http;

    public JiraClient(JiraConfig config, ObjectMapper mapper) {
        this.http = new PinnedJsonClient(
                new PinnedJsonConfig("Jira API", config.baseUrl(), authHeader(config),
                        Map.of("Accept", "application/json"),
                        "Check the base URL is the Jira site root and the token has REST API access."),
                mapper, JiraApiException::new);
    }

    public JsonNode getJson(String path) {
        return http.getJson(path);
    }

    private static String authHeader(JiraConfig config) {
        if ("bearer".equals(config.authKind())) {
            return "Bearer " + config.secret();
        }
        String raw = config.username() + ":" + config.secret();
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
