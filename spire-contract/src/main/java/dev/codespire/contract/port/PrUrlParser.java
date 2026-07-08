package dev.codespire.contract.port;

import dev.codespire.contract.scm.PrCoordinates;

import java.util.Optional;

/**
 * Extracts repo coordinates from a pull/merge-request URL for ONE provider.
 * Each SCM has its own URL grammar (GitHub {@code /pull/N}, Bitbucket
 * {@code /pull-requests/N}, GitLab {@code /-/merge_requests/N} over a possibly
 * nested group path), so each adapter owns its own parser instead of a single
 * global regex trying to cover every case.
 *
 * <p>Stateless — needs no credentials, unlike {@link DiffSource}. Returns empty
 * when the URL is not this provider's shape, so callers can try each parser in
 * turn and take the first match.
 */
public interface PrUrlParser {

    ScmType type();

    /** The coordinates when {@code url} matches this provider's shape, else empty. */
    Optional<PrCoordinates> parse(String url);
}
