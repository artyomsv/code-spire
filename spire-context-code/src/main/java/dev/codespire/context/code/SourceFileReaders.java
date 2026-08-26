package dev.codespire.context.code;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Percent-encodes a repository-relative path one segment at a time, preserving the literal
 * {@code /} separators the GitHub and Bitbucket content APIs expect as distinct path segments —
 * shared by {@link GitHubSourceFileReader} and {@link BitbucketSourceFileReader}.
 * {@link GitLabSourceFileReader} does not use this: GitLab's API instead takes the whole path,
 * slashes included, as one percent-encoded segment.
 *
 * <p>Without per-segment encoding, a raw path interpolated straight into the URL lets a
 * percent-encoded traversal segment (e.g. {@code %2e%2e}) sail past {@code Fetcher.isTraversal}
 * (which compares raw, un-decoded segments against {@code ".."}) and past the allow-list prefix
 * check, only to be decoded by the platform on arrival — I3 in the rung-1 final review.
 * {@code Fetcher} separately refuses any candidate containing a literal {@code %} before it ever
 * reaches a reader; this class exists to build a correct URL for the legitimate paths that pass
 * that check, not to be the security boundary itself. Encoding also protects a path containing
 * {@code #}, which would otherwise truncate the URL's own query string (e.g. GitHub's
 * {@code ?ref=}).
 */
final class SourceFileReaders {

    private SourceFileReaders() {
    }

    static String encodeSegments(String path) {
        StringBuilder out = new StringBuilder();
        String[] segments = path.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                out.append('/');
            }
            out.append(URLEncoder.encode(segments[i], StandardCharsets.UTF_8));
        }
        return out.toString();
    }
}
