package dev.codespire.scm.gitlab;

import dev.codespire.contract.port.PrUrlParser;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.PrCoordinates;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitLab MR URLs: {@code host/<group>/<subgroup...>/<project>/-/merge_requests/{iid}}.
 * The project path is everything between the host and the {@code /-/merge_requests/}
 * route separator (which never appears inside a project path). It can be nested, so
 * it is split by {@link GitLabProjectPath} into {@code workspace} = the top group and
 * {@code slug} = the rest of the namespace + project — with every segment validated so
 * {@code ..} or empty segments are rejected.
 */
public final class GitLabPrUrlParser implements PrUrlParser {

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
        try {
            long iid = Long.parseLong(m.group(2));
            return GitLabProjectPath.parse(m.group(1)).map(repo -> new PrCoordinates(repo, iid));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
