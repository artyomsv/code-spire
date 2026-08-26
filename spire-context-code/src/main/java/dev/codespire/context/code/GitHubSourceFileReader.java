package dev.codespire.context.code;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.http.PinnedJsonClient;
import dev.codespire.http.PinnedJsonConfig;

import java.net.URI;
import java.util.Map;

/**
 * Reads a repository file at a specific commit from the GitHub REST API.
 *
 * <p>Transport, host-pinned redirects and the private-address (SSRF) guard live in
 * {@link PinnedJsonClient}, shared with every other adapter. The {@code vnd.github.raw} media type
 * returns the file's bytes directly rather than base64 inside a JSON envelope, so this reader calls
 * {@link PinnedJsonClient#getRaw} rather than {@code getJson}.
 *
 * <p>{@code path} is percent-encoded one segment at a time ({@link SourceFileReaders#encodeSegments})
 * before it reaches the URL — see that class's javadoc for why a raw, unencoded path is unsafe here.
 */
public class GitHubSourceFileReader implements SourceFileReader {

    /** File contents as bytes rather than the base64-in-JSON envelope the default media type returns. */
    private static final String RAW_MEDIA = "application/vnd.github.raw";

    private final PinnedJsonClient http;
    private final URI baseUri;

    public GitHubSourceFileReader(CodeContextConfig config) {
        this.baseUri = URI.create(config.baseUrl().replaceAll("/$", ""));
        this.http = new PinnedJsonClient(
                new PinnedJsonConfig("GitHub API", config.baseUrl(), "Bearer " + config.secret(),
                        Map.of("Accept", RAW_MEDIA),
                        "Check the base URL is the API root (…/api/v3 on Enterprise Server) and the "
                                + "token can read repository contents."),
                new ObjectMapper(), CodeContextApiException::new);
    }

    @Override
    public String read(String repo, String path, String commit) {
        try {
            return http.getRaw("/repos/" + repo + "/contents/" + SourceFileReaders.encodeSegments(path)
                    + "?ref=" + commit);
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
