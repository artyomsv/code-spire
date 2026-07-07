package dev.codespire.orchestrator.provider;

import dev.codespire.contract.scm.Author;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Contacts the SCM with a provider's token to resolve the account behind it —
 * so registration can auto-fill the bot account id and validate the token up
 * front, instead of the operator looking the id up by hand and discovering a bad
 * token only on the first review.
 */
@ApplicationScoped
public class ProviderIdentityResolver {

    @Inject
    ProviderClients clients;

    /** The token owner for a pending provider input; throws the adapter's API exception on an auth failure. */
    public Author resolve(ProviderInput in) {
        return clients.identitySource(in.type(), in.baseUrl(), in.authKind(), in.authUsername(), in.secret()).whoami();
    }
}
