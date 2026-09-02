package dev.codespire.orchestrator.operator;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.port.OperatorOAuth;
import dev.codespire.contract.port.ScmType;
import dev.codespire.scm.bitbucket.BitbucketOperatorOAuth;
import dev.codespire.scm.github.GitHubOperatorOAuth;
import dev.codespire.scm.gitlab.GitLabOperatorOAuth;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;
import java.util.Set;

/**
 * Which platforms an operator can sign into, and the adapter that knows how.
 *
 * <p>A composition root, and the only place in the orchestrator that names an SCM — the same
 * exemption {@code ProviderClients} holds, for the same reason (ADR-020). Every caller asks for a
 * {@link ScmType} and gets a port back, so no policy anywhere branches on which platform it holds.
 */
@ApplicationScoped
public class OperatorConnects {

    /**
     * The platforms an operator can prove an account on.
     *
     * <p>Narrower than {@link ScmType} on purpose: a type this switch cannot build is a type an
     * admin must not be offered an OAuth-app form for, since saving one would produce a connect
     * button that only ever fails.
     */
    public static final Set<String> SUPPORTED_TYPES = Set.of("bitbucket-cloud", "github", "gitlab");

    @Inject
    ObjectMapper mapper;

    /** Empty when this build has no adapter for the platform — never a guess at a similar one. */
    public Optional<OperatorOAuth> forType(ScmType type) {
        return switch (type) {
            case GITHUB -> Optional.of(new GitHubOperatorOAuth(mapper));
            case GITLAB -> Optional.of(new GitLabOperatorOAuth(mapper));
            case BITBUCKET_CLOUD -> Optional.of(new BitbucketOperatorOAuth(mapper));
            case BITBUCKET_DC -> Optional.empty();
        };
    }
}
