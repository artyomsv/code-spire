package dev.codespire.orchestrator.factory;

import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.RepoRef;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * The HTTPS clone URL for a repository on a registered provider.
 *
 * <p>A composition root, allowlisted by {@code CoreIsProviderNeutralTest} for the same reason
 * {@code ProviderClients} is: the mapping from a provider's API base URL to its clone host is
 * provider-specific knowledge, and this is the one place in the orchestrator it may live.
 * GitHub's cloud API answers on {@code api.github.com} while clones go to {@code github.com}; a
 * GitHub Enterprise Server puts its API under {@code /api/v3} on the same host as its clones;
 * GitLab and Bitbucket Cloud use one host for both.
 *
 * <p>Derived rather than stored, so a registration cannot carry a clone URL pointing somewhere
 * other than the provider it was verified against.
 */
public final class FactoryCloneUrls {

    private static final String GITHUB_API_HOST = "api.github.com";

    private static final String GITHUB_CLONE_HOST = "github.com";

    private static final String BITBUCKET_CLONE_HOST = "bitbucket.org";

    private FactoryCloneUrls() {
    }

    public static String cloneUrl(ScmType type, String baseUrl, RepoRef repo) {
        String host = cloneHost(type, baseUrl);
        return "https://" + host + "/" + repo.workspace() + "/" + repo.slug() + ".git";
    }

    private static String cloneHost(ScmType type, String baseUrl) {
        return switch (type) {
            case GITHUB -> {
                String host = hostOf(baseUrl);
                yield GITHUB_API_HOST.equals(host) ? GITHUB_CLONE_HOST : host;
            }
            case BITBUCKET_CLOUD -> BITBUCKET_CLONE_HOST;
            case GITLAB, BITBUCKET_DC -> hostOf(baseUrl);
        };
    }

    private static String hostOf(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("a provider needs a base URL to derive its clone host");
        }
        try {
            URI uri = new URI(baseUrl);
            if (uri.getHost() == null) {
                throw new IllegalArgumentException("provider base URL has no host: " + baseUrl);
            }
            return uri.getPort() > 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("provider base URL is not a URI: " + baseUrl, e);
        }
    }
}
