package dev.codespire.context.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.http.PinnedJsonClient;


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
        this.http = JiraApiConnection.reader(config, mapper);
    }

    /** Strict origin-pinned reads with the headers needed for complete audit pagination. */
    public dev.codespire.http.PinnedJsonResponse getEvidence(String path) { return http.getIdentityResponse(path); }

    public JsonNode getJson(String path) {
        return http.getJson(path);
    }

    public JsonNode getIdentityJson(String path) { return http.getIdentityJson(path); }

}
