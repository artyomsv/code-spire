package dev.codespire.context.code;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.http.PinnedJsonClient;
import dev.codespire.http.PinnedJsonConfig;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Reads a repository file at a specific commit from the GitLab v4 REST API.
 *
 * <p>GitLab identifies both the project and the file by a percent-encoded path, slashes included —
 * {@code acme/widgets} and {@code src/Alpha.java} must arrive as {@code acme%2Fwidgets} and
 * {@code src%2FAlpha.java} or the request resolves to a different route, most often a 404 that reads
 * exactly like an absent file. Same approach as {@code GitLabIssueClient.encodePath}.
 */
public class GitLabSourceFileReader implements SourceFileReader {

    private final PinnedJsonClient http;
    private final URI baseUri;

    public GitLabSourceFileReader(CodeContextConfig config) {
        this.baseUri = URI.create(config.baseUrl().replaceAll("/$", ""));
        this.http = new PinnedJsonClient(
                new PinnedJsonConfig("GitLab API", config.baseUrl(), "Bearer " + config.secret(),
                        Map.of(),
                        "Check the base URL is the instance root (no /api/v4 suffix) and the token has "
                                + "api or read_api scope."),
                new ObjectMapper(), CodeContextApiException::new);
    }

    @Override
    public String read(String repo, String path, String commit) {
        try {
            return http.getRaw("/api/v4/projects/" + encode(repo) + "/repository/files/"
                    + encode(path) + "/raw?ref=" + encode(commit));
        } catch (CodeContextApiException e) {
            if (e.isNotFound()) {
                return null; // absent or moved file — the normal case, not an error
            }
            throw e;
        }
    }

    @Override
    public String apiHost() {
        return baseUri.getHost() != null ? baseUri.getHost() : baseUri.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
