package dev.codespire.context.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.http.PinnedJsonClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Thin read-only HTTP layer over the GitLab v4 REST API.
 *
 * <p>Transport, host-pinned redirects and the private-address (SSRF) guard live in
 * {@link PinnedJsonClient}, shared with every other adapter. What stays here is GitLab's: bearer auth
 * (a personal access token works on the OAuth-compliant header), the base-URL and scope advice an
 * operator needs when the token is refused, and the project-path encoding below.
 */
public class GitLabIssueClient {

    private final PinnedJsonClient http;

    public GitLabIssueClient(GitLabIssueConfig config, ObjectMapper mapper) {
        this.http = GitLabApiConnection.reader(config, mapper);
    }

    /** Strict origin-pinned reads with the headers needed for complete audit pagination. */
    public dev.codespire.http.PinnedJsonResponse getEvidence(String path) { return http.getIdentityResponse(path); }

    public JsonNode getJson(String path) {
        return http.getJson(path);
    }

    /**
     * A project path as one URL path segment. GitLab identifies a project by its full namespace path,
     * so {@code acme/tools/widgets} must arrive percent-encoded or the request resolves to a different
     * route entirely. Same approach as {@code GitLabDiffSource} in the SCM adapter.
     */
    public static String encodePath(String projectPath) {
        return URLEncoder.encode(projectPath, StandardCharsets.UTF_8);
    }
}
