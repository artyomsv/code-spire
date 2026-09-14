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

    @Inject
    dev.codespire.orchestrator.repository.RepositoryRegistry repositories;

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
        return clients.accountIdentity(in.type(), in.baseUrl(), in.authKind(), in.authUsername(), in.secret(), null);
    }

    /** An optional repository supplies validation scope, never account ownership or a binding. */
    public Author resolveForRegistration(ProviderInput in, java.util.UUID repositoryId) {
        if (repositoryId == null) return resolveForRegistration(in);
        var repository = repositories.get(repositoryId).orElseThrow(() -> new IllegalArgumentException("Repository is not registered"));
        if (!repository.scmType().equals(in.type())
                || !repository.forgeOrigin().equals(dev.codespire.contract.scm.ForgeOrigin.of(in.baseUrl()))) {
            throw new IllegalArgumentException("Validation repository belongs to another forge");
        }
        return clients.accountIdentity(in.type(), in.baseUrl(), in.authKind(), in.authUsername(), in.secret(), repository.workspace());
    }

    /** Connectivity check for a stored provider, tolerating tokens that can't name a user. */
    public Author resolveForCheck(ScmProvider p) {
        String namespace = repositories.list().stream()
                .filter(repository -> (repository.reviewer() != null && p.id().equals(repository.reviewer().id()))
                        || (repository.factory() != null && p.id().equals(repository.factory().id())))
                .map(dev.codespire.orchestrator.repository.RepositoryView::workspace).findFirst().orElse(null);
        return clients.accountIdentity(p.type(), p.baseUrl(), p.authKind(), p.authUsername(), p.secret(), namespace);
    }
}
