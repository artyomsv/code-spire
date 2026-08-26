package dev.codespire.context.code;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.http.PinnedJsonClient;
import dev.codespire.http.PinnedJsonConfig;

import java.net.URI;
import java.util.Map;

/**
 * Reads a repository file at a specific commit from the Bitbucket Cloud REST API.
 *
 * <p>Unlike GitLab, Bitbucket's {@code src} browse endpoint takes the file path as literal path
 * segments joined by {@code /} — the slashes themselves must stay literal; only the content of each
 * segment is percent-encoded ({@link SourceFileReaders#encodeSegments}). GitHub matches Bitbucket in
 * this respect ({@link GitHubSourceFileReader} builds its URL the same way); GitLab instead encodes
 * the whole path, slashes included, as one opaque segment.
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
            return http.getRaw("/repositories/" + SourceFileReaders.encodeSegments(repo) + "/src/"
                    + SourceFileReaders.encodeSegments(commit) + "/"
                    + SourceFileReaders.encodeSegments(path));
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
