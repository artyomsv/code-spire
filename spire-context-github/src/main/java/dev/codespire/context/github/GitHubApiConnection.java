package dev.codespire.context.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.http.PinnedJsonClient;
import dev.codespire.http.PinnedJsonConfig;
import dev.codespire.http.PinnedJsonWriter;
import java.util.Map;

/** Shared GitHub authentication and API version; callers choose an explicit read or write facade. */
public final class GitHubApiConnection {
    private GitHubApiConnection() {}

    public static PinnedJsonClient reader(GitHubIssueConfig config, ObjectMapper mapper) {
        return new PinnedJsonClient(transport(config), mapper, GitHubIssueApiException::new);
    }
    public static PinnedJsonWriter writer(GitHubIssueConfig config, ObjectMapper mapper) {
        return new PinnedJsonWriter(transport(config), mapper, GitHubIssueApiException::new);
    }
    private static PinnedJsonConfig transport(GitHubIssueConfig config) {
        return new PinnedJsonConfig("GitHub API", config.baseUrl(), "Bearer " + config.secret(),
                Map.of("Accept", "application/vnd.github+json", "X-GitHub-Api-Version", "2022-11-28"),
                "Check the base URL is the API root (…/api/v3 on Enterprise Server) and the token can read issues.");
    }
}
