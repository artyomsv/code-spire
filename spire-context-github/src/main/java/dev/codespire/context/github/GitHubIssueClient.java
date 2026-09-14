package dev.codespire.context.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.http.PinnedJsonClient;
import dev.codespire.http.PinnedJsonResponse;

/**
 * Thin read-only HTTP layer over the GitHub REST API.
 *
 * <p>Transport, host-pinned redirects and the private-address (SSRF) guard live in
 * {@link PinnedJsonClient}, shared with every other adapter, so a fix to the guard lands once. What
 * stays here is what is actually GitHub's: bearer auth, the vendor {@code Accept} type, the pinned API
 * version, and the base-URL advice an operator needs when the token is refused.
 */
public class GitHubIssueClient {

    private final PinnedJsonClient http;

    public GitHubIssueClient(GitHubIssueConfig config, ObjectMapper mapper) {
        this.http = GitHubApiConnection.reader(config, mapper);
    }

    public JsonNode getJson(String path) {
        return http.getJson(path);
    }

    /** Authority-bearing reads keep every redirect on the configured origin and preserve pagination. */
    public PinnedJsonResponse getEvidence(String path) {
        return http.getIdentityResponse(path);
    }
}
