package dev.codespire.orchestrator.provider;

import dev.codespire.contract.scm.Author;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Contacts the SCM with a provider's token to resolve the account behind it —
 * so registration can auto-fill the bot account id and validate the token up
 * front, instead of the operator looking the id up by hand and discovering a bad
 * token only on the first review.
 *
 * <p>Not every valid token can name a user; some are account-less and cannot call
 * the API's "who am I" endpoint at all. {@link #resolveForRegistration} and
 * {@link #resolveForCheck} therefore ask the neutral
 * {@link dev.codespire.contract.port.IdentitySource#whoamiOrValidate} question and let
 * the adapter decide how to answer it — a blank-identity {@link Author} means the token
 * is usable but has no id to auto-derive. Which providers need that, and how they
 * validate instead, is knowledge this class deliberately does not hold.
 */
@ApplicationScoped
public class ProviderIdentityResolver {

    @Inject
    ProviderClients clients;

    /** The token owner for a pending provider input; throws the adapter's API exception on an auth failure. */
    public Author resolve(ProviderInput in) {
        return clients.identitySource(in.type(), in.baseUrl(), in.authKind(), in.authUsername(), in.secret()).whoami();
    }

    /** The token owner for an already-stored provider (its secret decrypted). */
    public Author resolve(ScmProvider p) {
        return clients.identitySource(p.type(), p.baseUrl(), p.authKind(), p.authUsername(), p.secret()).whoami();
    }

    /** Validate + identify a pending provider input, tolerating tokens that can't name a user. */
    public Author resolveForRegistration(ProviderInput in) {
        return clients.identitySource(in.type(), in.baseUrl(), in.authKind(), in.authUsername(), in.secret())
                .whoamiOrValidate(in.workspace());
    }

    /** Connectivity check for a stored provider, tolerating tokens that can't name a user. */
    public Author resolveForCheck(ScmProvider p) {
        return clients.identitySource(p.type(), p.baseUrl(), p.authKind(), p.authUsername(), p.secret())
                .whoamiOrValidate(p.workspace());
    }
}
