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
    @Inject RunCredentials credentials;
    public record Prepared(RunCommand.ExecuteRun command,FactoryRunProjection.QueuedRun row) {}

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
        if(!pricer.isPriceable(in.model()))throw new IllegalStateException("model_pricing_unavailable");
        if(spend.decide().refused())throw new IllegalStateException("deployment_spend_cap_reached");
        if(!(pool.select() instanceof HarnessCredentialPool.Selection.Chosen chosen))throw new IllegalStateException("harness_credential_unavailable");
        String id=RunIds.of(source.scm(),in.workspace(),in.slug(),subject,1),branch=DispatchRequestParser.RUN_BRANCH_PREFIX+subject;
        long wall=Math.min(config.wallClockSeconds(),Math.subtractExact(item.policy().limits().maxWallClockSeconds(),item.progress().wallSeconds()));
        var command=new RunCommand.ExecuteRun(id,source.repository(),FactoryCloneUrls.cloneUrl(source.scm(),account.baseUrl(),source.repository()),
                in.baseBranch(),in.baseCommit(),branch,in.prompt(),in.harness(),in.model(),in.agentImage(),
                item.policy().limits().protectedPaths().stream().sorted().toList(),wall,
                credentials.packScm(id,account.botUsername(),account.secret()),credentials.packHarness(id,chosen.member().apiKey()));
        return new Prepared(command,new FactoryRunProjection.QueuedRun(id,in.harness(),in.model(),in.baseBranch(),in.baseCommit(),branch,account.botUsername(),chosen.member().id()));
    }
}
