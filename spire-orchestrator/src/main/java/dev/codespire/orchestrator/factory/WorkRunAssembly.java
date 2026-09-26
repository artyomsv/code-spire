package dev.codespire.orchestrator.factory;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunIds;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.orchestrator.caps.SpendGate;
import dev.codespire.orchestrator.llm.LlmModelPricer;
import dev.codespire.orchestrator.work.WorkArtifacts;
import dev.codespire.orchestrator.work.WorkSourceRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.*;
import java.util.*;

/** Reuses M2 input validation, explicit machine identity, credential pool, pricing and deployment caps. */
@ApplicationScoped
public class WorkRunAssembly {
    @Inject FactoryConfig config;
    @Inject MachineAccounts accounts;
    @Inject HarnessCredentialPool pool;
    @Inject SpendGate spend;
    @Inject LlmModelPricer pricer;
    @Inject dev.codespire.orchestrator.llm.LlmModelRegistry models;
    @Inject RunCredentials credentials;
    @Inject HarnessCatalogues catalogues;
    public record Prepared(RunCommand.ExecuteWorkRun command,FactoryRunProjection.QueuedRun row) {}

    public void validate(WorkSourceRegistry.Source source,dev.codespire.contract.work.WorkPreparation preparation,WorkArtifacts.Evidence evidence) {
        parse(source,preparation,evidence,"work-validation");
    }
    private DispatchRequestParser.Parsed parse(WorkSourceRegistry.Source source,dev.codespire.contract.work.WorkPreparation preparation,
                                               WorkArtifacts.Evidence evidence,String subject) {
        if(evidence.failure()!=null || evidence.specification()==null || evidence.instruction()==null)
            throw new IllegalArgumentException("Validated tracker artifacts are required");
        String prompt="Implement this one prepared task.\n\nSpecification:\n"+evidence.specification()+"\n\nSingle plan step:\n"+evidence.instruction();
        return DispatchRequestParser.parse(new RunResource.DispatchRequest(source.repository().workspace(),source.repository().slug(),source.scm().providerType(),
                preparation.baseBranch(),preparation.baseCommit(),prompt,preparation.harness(),preparation.model(),subject,null,source.repositoryId()),config);
    }
    /** Caller holds registry and item locks. This method performs local reads and encryption, no network calls. */
    public Prepared assemble(Connection c,WorkSourceRegistry.Source source,WorkItemEvent item,WorkArtifacts.Evidence evidence) throws SQLException {
        String subject="work-"+item.progress().attemptId();
        var in=parse(source,item.preparation(),evidence,subject);
        // Lock the selected factory credential before reading it, including when tracker and factory accounts differ.
        try(PreparedStatement ps=c.prepareStatement("SELECT p.id FROM repository_account a JOIN scm_provider p ON p.id=a.account_id WHERE a.repository_id=? AND a.role='FACTORY' FOR UPDATE OF p")) {
            ps.setObject(1,source.repositoryId());try(ResultSet rs=ps.executeQuery()) { if(!rs.next())throw new IllegalStateException("factory_account_unavailable"); }
        }
        var account=accounts.resolve(source.repositoryId()).orElseThrow(()->new IllegalStateException("factory_account_unavailable"));
        // How the APPROVED preparation pays — not the build setup now, which may have changed since.
        boolean subscription=dev.codespire.contract.work.PayWith.SUBSCRIPTION.equals(item.preparation().payWith());
        // Every type the chosen harness can report must have a rate or a not-billed assertion. Asking
        // only about INPUT and OUTPUT is what let item 36 start, spend, report CACHED_INPUT and
        // REASONING, and stop with a cost nobody could account for. A subscription is not priced per
        // token, so the price list neither enables nor blocks it.
        try { if(!subscription && models.isDisabled(in.model()))throw new IllegalStateException("model_disabled"); }
        catch(dev.codespire.orchestrator.llm.LlmModelRegistry.CatalogueUnavailable unreadable) {
            throw new IllegalStateException("catalogue_unavailable");
        }
        // Approved against the image the harness ran then; it may run another by now (§6A.4b).
        var admission=catalogues.admit(in.harness(),in.model(),item.preparation().effort());
        if(admission.refusal()!=null)throw new IllegalStateException(admission.refusal());
        if(!subscription) {
            var unpriced=pricer.unpricedTypes(in.model(),in.harness());
            if(!unpriced.isEmpty())throw new IllegalStateException("model_pricing_incomplete:"
                    +unpriced.stream().map(Enum::name).collect(java.util.stream.Collectors.joining(",")));
        }
        // Still consulted for a subscription run: it adds no money, but the deployment's own gate is the
        // operator's policy, and part F does not quietly change it (design §5.7).
        if(spend.decide().refused())throw new IllegalStateException("deployment_spend_cap_reached");
        String id=RunIds.of(source.scm(),in.workspace(),in.slug(),subject,1),branch=DispatchRequestParser.RUN_BRANCH_PREFIX+subject;
        long wall=Math.min(config.wallClockSeconds(),Math.subtractExact(item.policy().limits().maxWallClockSeconds(),item.progress().wallSeconds()));
        HarnessCredentialPool.PoolMember member=subscription ? pickSeat(in.harness()) : pickKey();
        // The agent gets the sign-in with its refresh token emptied; the whole file stays here, encrypted.
        String handedOver=subscription ? SignInFiles.forAgent(member.apiKey()) : member.apiKey();
        RunCommand.ExecuteRun command=new RunCommand.ExecuteRun(id,source.repository(),FactoryCloneUrls.cloneUrl(source.scm(),account.baseUrl(),source.repository()),
                // The image the checked list was read from, not the tag, which may name another by now.
                in.baseBranch(),in.baseCommit(),branch,in.prompt(),in.harness(),in.model(),admission.image(),
                item.policy().limits().protectedPaths().stream().sorted().toList(),wall,
                credentials.packScm(id,account.botUsername(),account.secret()),credentials.packHarness(id,handedOver))
                // The level the approved binding hashed, so the build runs at what was approved (M3.5 part M).
                .atEffort(item.preparation().effort());
        if(subscription)command=command.paidBySignIn();
        var held=new RunCommand.ExecuteWorkRun(command,new dev.codespire.contract.work.WorkRunBinding(
                item.workItemId(),item.generation(),item.progress().attemptId(),item.preparation().binding()));
        var row=new FactoryRunProjection.QueuedRun(id,in.harness(),in.model(),in.baseBranch(),in.baseCommit(),branch,account.botUsername(),member.id());
        return new Prepared(held,subscription?row.paidBySubscription():row);
    }

    private HarnessCredentialPool.PoolMember pickSeat(String harness) {
        return pool.selectSubscription(harness).orElseThrow(()->new IllegalStateException("subscription_unavailable"));
    }

    private HarnessCredentialPool.PoolMember pickKey() {
        if(!(pool.select() instanceof HarnessCredentialPool.Selection.Chosen chosen))throw new IllegalStateException("harness_credential_unavailable");
        return chosen.member();
    }
}
