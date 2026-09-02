package dev.codespire.contract.scm;

import dev.codespire.contract.port.ScmType;

/**
 * The OAuth application an operator signs into to prove which SCM account is theirs.
 *
 * <p>One per platform. It is <b>not</b> the bot credential in the provider registry: that one
 * proves the reviewer's identity, and no amount of it can say who the human at the browser is.
 *
 * <p>Two base URLs, because for one platform they genuinely differ — a hosted service can answer
 * its sign-in pages on one host and its API on another, and a self-hosted install answers both on
 * its own. Blank means "the platform's own hosted service", which each adapter fills in for itself:
 * a URL the core wrote would be this module naming a provider, which is the thing ADR-020 forbids.
 *
 * @param clientSecret decrypted at the point of use and never returned by any API
 */
public record OAuthApp(ScmType type, String webBaseUrl, String apiBaseUrl,
                       String clientId, String clientSecret) {

    public OAuthApp {
        if (type == null) {
            throw new IllegalArgumentException("An OAuth app must name its platform");
        }
        if (isBlank(clientId) || isBlank(clientSecret)) {
            throw new IllegalArgumentException("An OAuth app needs both a client id and a client secret");
        }
    }

    /** The configured web base, or {@code fallback} when the operator left it empty. */
    public String webBaseOr(String fallback) {
        return isBlank(webBaseUrl) ? fallback : trimTrailingSlash(webBaseUrl);
    }

    /** The configured API base, or {@code fallback} when the operator left it empty. */
    public String apiBaseOr(String fallback) {
        return isBlank(apiBaseUrl) ? fallback : trimTrailingSlash(apiBaseUrl);
    }

    private static String trimTrailingSlash(String url) {
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
