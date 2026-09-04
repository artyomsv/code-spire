package dev.codespire.contract.scm;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

/**
 * A pull request that exists on a forge: its number and where a human can read it.
 *
 * <p><b>Both, not one.</b> The number is what {@code factory_run.pr_id} stores and what every later
 * API call takes; the URL is what a person clicks and what the runs view shows. Returning only the
 * number would mean the URL gets re-assembled from a host and a path somewhere else — which is how a
 * self-hosted GitLab or Bitbucket DC ends up with {@code github.com} in a link, and the adapter that
 * knows the real host is the only thing that should ever build it.
 *
 * <p>"Number", not "id": on all three forges the value in the URL and in the API path is the
 * per-repository number, and each of them ALSO has a global id that is not it. Naming it {@code id}
 * is how the two get confused, and {@code RepoRef}'s own history shows what that costs.
 */
public record PullRequestRef(long number, String url) {

    public PullRequestRef {
        if (number <= 0) {
            // Every forge numbers from 1. A zero here means a response field was missing and read
            // back as a primitive default -- the fabricated-zero shape ADR-023 names, arriving
            // through a JSON parse rather than through a ledger.
            throw new IllegalArgumentException("a pull request number starts at 1: " + number);
        }
        Objects.requireNonNull(url, "url");
        if (url.isBlank()) {
            throw new IllegalArgumentException("a pull request needs a URL a human can open");
        }
        // This value comes from a forge response and becomes an href. A scheme check is the whole
        // guard -- the host is NOT pinned to the API host, because Bitbucket serves its web pages
        // from a different one than its API and pinning would refuse every legitimate link.
        String scheme;
        try {
            scheme = new URI(url).getScheme();
        } catch (URISyntaxException notAUrl) {
            throw new IllegalArgumentException("a pull request URL must be a URL: " + url, notAUrl);
        }
        if (scheme == null || !("http".equals(scheme.toLowerCase(Locale.ROOT))
                || "https".equals(scheme.toLowerCase(Locale.ROOT)))) {
            throw new IllegalArgumentException(
                    "a pull request URL must be http or https, so it can never become a "
                            + "javascript: href when it is rendered: " + url);
        }
    }
}
