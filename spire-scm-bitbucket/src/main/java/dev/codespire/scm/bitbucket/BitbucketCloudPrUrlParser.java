package dev.codespire.scm.bitbucket;

import dev.codespire.contract.port.PrUrlParser;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.PrCoordinates;
import dev.codespire.contract.scm.RepoRef;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bitbucket Cloud PR URLs: {@code bitbucket.org/{workspace}/{repo}/pull-requests/{id}}
 * (the UI variant {@code /pullrequests/} is accepted too). Host-agnostic so
 * proxied hosts (company MCAS: {@code bitbucket.org.mcas.ms}) and trailing path or
 * query segments still parse. {@code workspace} and {@code repo} are single segments.
 */
public final class BitbucketCloudPrUrlParser implements PrUrlParser {

    private static final String SEG = "[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?";
    private static final Pattern URL =
            Pattern.compile("(" + SEG + ")/(" + SEG + ")/(?:pull-requests|pullrequests)/(\\d+)");

    @Override
    public ScmType type() {
        return ScmType.BITBUCKET_CLOUD;
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
            return Optional.empty();
        }
    }
}
