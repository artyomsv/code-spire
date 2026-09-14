package dev.codespire.context.gitlab;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.http.*;
import java.util.Map;

/** Shared authentication and pinned transport; context readers expose no write operations. */
public final class GitLabApiConnection {
    private GitLabApiConnection() {}
    public static PinnedJsonClient reader(GitLabIssueConfig config, ObjectMapper mapper) {
        return new PinnedJsonClient(transport(config), mapper, GitLabIssueApiException::new);
    }
    public static PinnedJsonWriter writer(GitLabIssueConfig config, ObjectMapper mapper) {
        return new PinnedJsonWriter(transport(config), mapper, GitLabIssueApiException::new);
    }
    private static PinnedJsonConfig transport(GitLabIssueConfig config) {
        return new PinnedJsonConfig("GitLab API", config.baseUrl(), "Bearer " + config.secret(),
                Map.of("Accept", "application/json"), "Check the instance root and the selected token API permissions.");
    }
}
