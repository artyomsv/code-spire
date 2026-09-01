package dev.codespire.orchestrator.factory;

import dev.codespire.contract.port.ScmType;
import dev.codespire.orchestrator.provider.ProviderRegistry;
import dev.codespire.orchestrator.provider.ProviderRole;
import dev.codespire.orchestrator.provider.ScmProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * The dedicated machine account a factory run pushes as (ADR-037, FR-F29).
 *
 * <p><b>Empty means "cannot dispatch", never "use the reviewer".</b> The reviewer's own author
 * allowlist skips pull requests it opened itself — so a run that fell back to the review bot would
 * produce a branch nobody reviews, silently. Failing closed here is the whole point of the class
 * existing as something separate from {@link ProviderRegistry#resolve}.
 */
@ApplicationScoped
public class MachineAccounts {

    @Inject
    ProviderRegistry providers;

    public Optional<ScmProvider> resolve(ScmType scmType, String workspace) {
        return providers.resolve(scmType.providerType(), workspace, ProviderRole.FACTORY);
    }
}
