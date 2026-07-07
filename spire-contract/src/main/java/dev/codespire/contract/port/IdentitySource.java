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
}
