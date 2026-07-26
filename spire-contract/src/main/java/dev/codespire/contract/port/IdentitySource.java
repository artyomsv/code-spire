package dev.codespire.contract.port;

import dev.codespire.contract.scm.Author;

/**
 * Resolves the account behind a configured SCM token. Used at provider
 * registration to (1) auto-fill the bot account id — the stable id the ingress
 * self-loop guard compares against (ADR-013) — so the operator never has to look
 * it up by hand, and (2) validate the token up front: a bad or under-scoped token
 * fails fast at "Save" instead of silently on the first review.
 *
 * <p>Implemented by the read adapters (they already hold the token-bearing
 * client). {@code whoami()} throws the adapter's API exception on an auth failure.
 */
public interface IdentitySource {

    ScmType type();

    /** The token owner (provider user id + username + display name); email is never carried. */
    Author whoami();

    /**
     * Validate a credential and name its owner if it has one — the question registration
     * actually asks.
     *
     * <p>Not every usable credential belongs to a user account, and on such a token
     * {@link #whoami()} fails while the token itself is perfectly good. An adapter whose
     * API has that kind of credential overrides this to fall back to a check of the
     * capability a review really needs, returning a blank-identity {@link Author}: usable
     * token, simply no id to auto-derive. The default is plain {@code whoami()} — no
     * fallback, so a genuine auth failure still surfaces.
     *
     * <p>The fallback lives here, behind one neutral question, so that callers never
     * branch on which provider they hold. A caller that asked "is this Bitbucket?" would
     * have to be revisited for every provider added or every capability that changes.
     *
     * @param workspace the workspace/organisation the credential is scoped to
     */
    default Author whoamiOrValidate(String workspace) {
        return whoami();
    }
}
