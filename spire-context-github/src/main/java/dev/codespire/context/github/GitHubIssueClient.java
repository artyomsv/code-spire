package dev.codespire.context.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.http.PinnedJsonClient;
import dev.codespire.http.PinnedJsonConfig;

import java.util.Map;

/**
 * Thin read-only HTTP layer over the GitHub REST API.
 *
 * <p>Transport, host-pinned redirects and the private-address (SSRF) guard live in
 * {@link PinnedJsonClient}, shared with every other adapter, so a fix to the guard lands once. What
 * stays here is what is actually GitHub's: bearer auth, the vendor {@code Accept} type, the pinned API
 * version, and the base-URL advice an operator needs when the token is refused.
 */
public class GitHubIssueClient {

    /** Pinning the API version keeps a future default change from silently altering response shapes. */
    private static final String API_VERSION = "2022-11-28";

    private final PinnedJsonClient http;

    public GitHubIssueClient(GitHubIssueConfig config, ObjectMapper mapper) {
        this.http = new PinnedJsonClient(
                new PinnedJsonConfig("GitHub API", config.baseUrl(), "Bearer " + config.secret(),
                        Map.of("Accept", "application/vnd.github+json",
                                "X-GitHub-Api-Version", API_VERSION),
                        "Check the base URL is the API root (…/api/v3 on Enterprise Server) and the "
                                + "token can read issues."),
                mapper, GitHubIssueApiException::new);
    }

    public JsonNode getJson(String path) {
        return http.getJson(path);
    }
}
