package dev.codespire.orchestrator.factory;

import dev.codespire.contract.port.ScmType;
import dev.codespire.orchestrator.provider.ProviderRegistry;
import dev.codespire.orchestrator.provider.ProviderRole;
import dev.codespire.orchestrator.provider.ScmProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * The dedicated machine account a factory run pushes as (ADR-038, FR-F29).
 *
 * <p><b>Empty means "cannot dispatch", never "use the reviewer".</b> The reviewer's own author
 * allowlist skips pull requests it opened itself — so a run that fell back to the review bot would
 * produce a branch nobody reviews, silently. Failing closed here is the whole point of the class
 * existing as something separate from {@link ProviderRegistry#resolve}.
 *
 * <p><b>An account with no resolved login is EMPTY too, and that is why the check lives here.</b>
 * The login is what the forge authenticates the push as. A blank one is stored as SQL null by
 * {@code ProviderRegistry} and reaches {@code MachineAccountCredential}, whose constructor throws.
 * {@code RunResource} guards that and says why; the {@code /fix} path then re-derived the same
 * lookup and dropped the guard. On the REST arm a throw is a 500 the caller reads. On a Kafka
 * consumer it escapes the saga, so the record is redelivered forever and the author is told
 * nothing at all.
 *
 * <p>So the rule moved into the one place that resolves the factory's push identity. Two callers
 * each remembering the same guard is the shape this repository keeps paying for.
 */
@ApplicationScoped
public class MachineAccounts {

    @Inject
    ProviderRegistry providers;

    public Optional<ScmProvider> resolve(ScmType scmType, String workspace) {
        return providers.resolve(scmType.providerType(), workspace, ProviderRole.FACTORY)
                .filter(MachineAccounts::canAuthenticateAPush);
    }

    /**
     * The registration behind {@link #resolve}, usable or not — for saying WHY it was empty.
     *
     * <p><b>Never for dispatch.</b> {@code resolve} is the only method that answers "can this
     * account push", and this one exists because its two empty answers have different cures: an
     * operator registers a missing account, and re-saves a login-less one. Merging them into one
     * message would send half the readers to the wrong screen. Only the REST arm calls it, on the
     * failure path, where a second read costs nothing anyone is waiting on.
     */
    public Optional<ScmProvider> registration(ScmType scmType, String workspace) {
        return providers.resolve(scmType.providerType(), workspace, ProviderRole.FACTORY);
    }

    /**
     * A registration with no login cannot authenticate a push, so it is not a usable account.
     *
     * <p>Filtered rather than thrown, so every caller gets the answer it already knows how to
     * render — {@code RunResource} its 409, the {@code /fix} dispatch its refusal. A throw here
     * would reintroduce on the saga arm the escaping-exception shape this guard exists to remove.
     */
    private static boolean canAuthenticateAPush(ScmProvider provider) {
        return provider.botUsername() != null && !provider.botUsername().isBlank();
    }
}
