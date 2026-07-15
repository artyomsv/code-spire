package dev.codespire.orchestrator.provider;

import dev.codespire.contract.scm.Author;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.function.Supplier;

/**
 * Contacts the SCM with a provider's token to resolve the account behind it —
 * so registration can auto-fill the bot account id and validate the token up
 * front, instead of the operator looking the id up by hand and discovering a bad
 * token only on the first review.
 *
 * <p>Not every valid token can name a user: a Bitbucket workspace/repository
 * access token acts as a synthetic bot and can't call {@code /user}. For those,
 * {@link #resolveForRegistration}/{@link #resolveForCheck} fall back to a
 * workspace capability check and return a blank-identity {@link Author} — the
 * token is usable, there's just no {@code account_id} to auto-derive.
 */
@ApplicationScoped
public class ProviderIdentityResolver {

    private static final Logger LOG = Logger.getLogger(ProviderIdentityResolver.class);

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

    /** Validate + identify a pending provider input, tolerating tokens that can't call {@code whoami()}. */
    public Author resolveForRegistration(ProviderInput in) {
        return whoamiOrWorkspace(in.type(), in.baseUrl(), in.authKind(), in.authUsername(), in.secret(),
                in.workspace(), () -> resolve(in));
    }

    /** Connectivity check for a stored provider, tolerating tokens that can't call {@code whoami()}. */
    public Author resolveForCheck(ScmProvider p) {
        return whoamiOrWorkspace(p.type(), p.baseUrl(), p.authKind(), p.authUsername(), p.secret(),
                p.workspace(), () -> resolve(p));
    }

    /**
     * Try {@code whoami()} first (keeps auto-identity for App Passwords / API tokens
     * and GitHub/GitLab PATs). If it fails for a Bitbucket provider, the token may be
     * a workspace/repo access token that can't call {@code /user}; validate it against
     * the workspace instead. If THAT also fails, rethrow the original failure so a
     * genuinely bad token still surfaces (not the fallback's error).
     */
    private Author whoamiOrWorkspace(String type, String baseUrl, String authKind, String authUsername,
                                     String secret, String workspace, Supplier<Author> whoami) {
        try {
            return whoami.get();
        } catch (RuntimeException whoamiFailed) {
            if (!"bitbucket-cloud".equals(type)) {
                throw whoamiFailed;
            }
            try {
                clients.assertWorkspaceAccess(type, baseUrl, authKind, authUsername, secret, workspace);
            } catch (RuntimeException workspaceFailed) {
                // Diagnostic: the workspace fallback also failed. Its status/detail is
                // the actionable one (bad scope / wrong workspace slug), so log it —
                // we still rethrow the /user failure to the client (generic 400).
                LOG.warnf(workspaceFailed, "Workspace fallback validation failed for %s workspace '%s'",
                        type, workspace);
                throw whoamiFailed;
            }
            return Author.of("", "", ""); // valid token, but no user account to name
        }
    }
}
