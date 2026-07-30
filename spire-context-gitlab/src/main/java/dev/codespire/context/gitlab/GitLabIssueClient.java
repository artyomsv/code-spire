package dev.codespire.context.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.http.PinnedJsonClient;
import dev.codespire.http.PinnedJsonConfig;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

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
        this.http = new PinnedJsonClient(
                new PinnedJsonConfig("GitLab API", config.baseUrl(), "Bearer " + config.secret(),
                        Map.of("Accept", "application/json"),
                        "Check the base URL is the instance root (no /api/v4 suffix) and the token has "
                                + "api or read_api scope."),
                mapper, GitLabIssueApiException::new);
    }

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
