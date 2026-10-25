package dev.codespire.orchestrator.factory;

import dev.codespire.orchestrator.caps.SpendGate;
import dev.codespire.orchestrator.llm.LlmModelPricer;
import dev.codespire.orchestrator.llm.LlmModelRegistry;
import dev.codespire.orchestrator.provider.ScmProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Who a work item's run is dispatched as and how it pays, asked the same way for a build and for a specification or
 * plan run (M4 slice 3): the FACTORY account, locked before it is read; the model, the image the harness runs it on,
 * the price list, the deployment's spend gate, and a seat or a key. Each refusal is the rule's own reason.
 *
 * <p>Extracted from {@link WorkRunAssembly} rather than copied: two copies of "may this run be bought" would drift, and
 * the one that drifted would be the one a draft run passes and a build refuses, after the draft was paid for.
 */
@ApplicationScoped
public class RunFunding {
    @Inject MachineAccounts accounts;
    @Inject HarnessCredentialPool pool;
    @Inject SpendGate spend;
    @Inject LlmModelPricer pricer;
    @Inject LlmModelRegistry models;
    @Inject HarnessCatalogues catalogues;

    /** What a run is asked to run with, and whether a subscription seat pays for it. */
    public record Choice(String harness, String model, String effort, boolean subscription) {}

    /** The pool member that pays, what the agent is handed, and the exact image the checked list was read from. */
    public record Funded(HarnessCredentialPool.PoolMember member, String handedOver, String image) {}

    /** The FACTORY account, locked first, including when the tracker and factory accounts differ. Caller holds the item lock. */
    public ScmProvider account(Connection c, UUID repository) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT p.id FROM repository_account a JOIN scm_provider p ON p.id=a.account_id WHERE a.repository_id=? AND a.role='FACTORY' FOR UPDATE OF p")) {
            ps.setObject(1, repository);
            try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) throw new IllegalStateException("factory_account_unavailable"); }
        }
        return accounts.resolve(repository).orElseThrow(() -> new IllegalStateException("factory_account_unavailable"));
    }

    /**
     * Every type the chosen harness can report must have a rate or a not-billed assertion. Asking only about INPUT and
     * OUTPUT is what let item 36 start, spend, report CACHED_INPUT and REASONING, and stop with a cost nobody could
     * account for. A subscription is not priced per token, so the price list neither enables nor blocks it; the
     * deployment's spend gate is still consulted, because it is the operator's policy (design §5.7).
     *
     * @throws IllegalStateException naming the refusal
     */
    public Funded fund(Choice choice) {
        try { if (!choice.subscription() && models.isDisabled(choice.model())) throw new IllegalStateException("model_disabled"); }
        catch (LlmModelRegistry.CatalogueUnavailable unreadable) { throw new IllegalStateException("catalogue_unavailable"); }
        // Approved against the image the harness ran then; it may run another by now (§6A.4b).
        HarnessCatalogues.Admission admission = catalogues.admit(choice.harness(), choice.model(), choice.effort());
        if (admission.refusal() != null) throw new IllegalStateException(admission.refusal());
        if (!choice.subscription()) {
            java.util.List<dev.codespire.contract.review.TokenType> unpriced = pricer.unpricedTypes(choice.model(), choice.harness());
            if (!unpriced.isEmpty()) throw new IllegalStateException("model_pricing_incomplete:"
                    + unpriced.stream().map(Enum::name).collect(Collectors.joining(",")));
        }
        if (spend.decide().refused()) throw new IllegalStateException("deployment_spend_cap_reached");
        HarnessCredentialPool.PoolMember member = choice.subscription() ? pickSeat(choice.harness()) : pickKey();
        // The agent gets the sign-in with its refresh token emptied; the whole file stays here, encrypted.
        String handedOver = choice.subscription() ? SignInFiles.forAgent(member.apiKey()) : member.apiKey();
        return new Funded(member, handedOver, admission.image());
    }

    private HarnessCredentialPool.PoolMember pickSeat(String harness) {
        return pool.selectSubscription(harness).orElseThrow(() -> new IllegalStateException("subscription_unavailable"));
    }

    private HarnessCredentialPool.PoolMember pickKey() {
        if (!(pool.select() instanceof HarnessCredentialPool.Selection.Chosen chosen)) throw new IllegalStateException("harness_credential_unavailable");
        return chosen.member();
    }
}
