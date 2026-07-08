package dev.codespire.scm.github;

import dev.codespire.contract.port.PrUrlParser;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.PrCoordinates;
import dev.codespire.contract.scm.RepoRef;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub PR URLs: {@code github.com/{owner}/{repo}/pull/{number}}. Host-agnostic
 * (matches the path anywhere) so GitHub Enterprise hosts and any trailing path or
 * query segments still parse. {@code owner} and {@code repo} are single segments.
 */
public final class GitHubPrUrlParser implements PrUrlParser {

    // A single path segment: starts and ends alphanumeric, so "." / ".." can't slip through.
    private static final String SEG = "[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?";
    private static final Pattern URL = Pattern.compile("(" + SEG + ")/(" + SEG + ")/pull/(\\d+)");

    @Override
    public ScmType type() {
        return ScmType.GITHUB;
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
            return Optional.of(new PrCoordinates(new RepoRef(m.group(1), m.group(2)), Long.parseLong(m.group(3))));
        } catch (NumberFormatException e) {
            return Optional.empty(); // a PR number too large for a long is not a real PR
        }
    }
}
