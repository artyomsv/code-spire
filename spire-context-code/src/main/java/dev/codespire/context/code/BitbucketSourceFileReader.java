package dev.codespire.context.code;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.http.PinnedJsonClient;
import dev.codespire.http.PinnedJsonConfig;

import java.net.URI;
import java.util.Map;

/**
 * Reads a repository file at a specific commit from the Bitbucket Cloud REST API.
 *
 * <p>Unlike GitLab, Bitbucket's {@code src} browse endpoint takes the repository and file path as
 * literal path segments — no percent-encoding of the slashes. GitHub matches Bitbucket in this
 * respect ({@link GitHubSourceFileReader} builds its URL the same literal-path way); GitLab is the
 * one that requires {@code encode(repo)}/{@code encode(path)}.
 */
public class BitbucketSourceFileReader implements SourceFileReader {

    private final PinnedJsonClient http;
    private final URI baseUri;

    public BitbucketSourceFileReader(CodeContextConfig config) {
        this.baseUri = URI.create(config.baseUrl().replaceAll("/$", ""));
        this.http = new PinnedJsonClient(
                new PinnedJsonConfig("Bitbucket API", config.baseUrl(), "Bearer " + config.secret(),
                        Map.of(),
                        "Check the base URL is the API root (https://api.bitbucket.org/2.0) and the "
                                + "token can read repository source."),
                new ObjectMapper(), CodeContextApiException::new);
    }

    @Override
    public String read(String repo, String path, String commit) {
        try {
            return http.getRaw("/repositories/" + repo + "/src/" + commit + "/" + path);
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
}
