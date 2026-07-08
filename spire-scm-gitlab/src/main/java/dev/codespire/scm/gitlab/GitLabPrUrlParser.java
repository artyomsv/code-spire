package dev.codespire.scm.gitlab;

import dev.codespire.contract.port.PrUrlParser;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.PrCoordinates;
import dev.codespire.contract.scm.RepoRef;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitLab MR URLs: {@code host/<group>/<subgroup...>/<project>/-/merge_requests/{iid}}.
 * The project path is everything between the host and the {@code /-/merge_requests/}
 * route separator (which never appears inside a project path). It can be nested, so
 * {@code workspace} = the top group and {@code slug} = the rest of the namespace +
 * project — every segment is validated so {@code ..} or empty segments are rejected.
 */
public final class GitLabPrUrlParser implements PrUrlParser {

    private static final String SEG = "[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?";
    private static final Pattern SEGMENT = Pattern.compile(SEG);
    private static final Pattern URL = Pattern.compile("://[^/\\s]+/(.+?)/-/merge_requests/(\\d+)");

    @Override
    public ScmType type() {
        return ScmType.GITLAB;
    }

    @Override
    public Optional<PrCoordinates> parse(String url) {
        if (url == null) {
            return Optional.empty();
        }
        Matcher m = URL.matcher(url);
        if (!m.find()) {
            return Optional.empty();
        }
        String path = m.group(1);
        int slash = path.indexOf('/');
        if (slash <= 0 || slash == path.length() - 1) {
            return Optional.empty(); // must have a group AND a project
        }
        String workspace = path.substring(0, slash);
        String slug = path.substring(slash + 1);
        if (!SEGMENT.matcher(workspace).matches() || !validSlug(slug)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new PrCoordinates(new RepoRef(workspace, slug), Long.parseLong(m.group(2))));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** A slug is one or more path segments (nested groups); each must be a valid segment. */
    private static boolean validSlug(String slug) {
        for (String segment : slug.split("/", -1)) {
            if (!SEGMENT.matcher(segment).matches()) {
                return false;
            }
        }
        return true;
    }
}
