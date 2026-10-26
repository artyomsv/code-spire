package dev.codespire.orchestrator.factory;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunIds;
import dev.codespire.contract.work.WorkItemEvent;
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
    @Inject RunFunding funding;
    @Inject RunCredentials credentials;
    @Inject dev.codespire.orchestrator.work.WorkVerifyHistory verifyHistory;
    @Inject dev.codespire.orchestrator.work.WorkStepAssembly steps;
    public record Prepared(RunCommand.ExecuteWorkRun command,FactoryRunProjection.QueuedRun row) {}

    public void validate(WorkSourceRegistry.Source source,dev.codespire.contract.work.WorkPreparation preparation,WorkArtifacts.Evidence evidence) {
        parse(source,preparation,composedPrompt(evidence),"work-validation");
    }
    private static String composedPrompt(WorkArtifacts.Evidence evidence) {
        if(evidence.failure()!=null || evidence.specification()==null || evidence.instruction()==null)
            throw new IllegalArgumentException("Validated tracker artifacts are required");
        return "Implement this one prepared task.\n\nSpecification:\n"+evidence.specification()+"\n\nSingle plan step:\n"+evidence.instruction();
    }
    private DispatchRequestParser.Parsed parse(WorkSourceRegistry.Source source,dev.codespire.contract.work.WorkPreparation preparation,
                                               String prompt,String subject) {
        return DispatchRequestParser.parse(new RunResource.DispatchRequest(source.repository().workspace(),source.repository().slug(),source.scm().providerType(),
                preparation.baseBranch(),preparation.baseCommit(),prompt,preparation.harness(),preparation.model(),subject,null,source.repositoryId()),config);
    }
    /** Caller holds registry and item locks. This method performs local reads and encryption, no network calls. */
    public Prepared assemble(Connection c,WorkSourceRegistry.Source source,WorkItemEvent item,WorkArtifacts.Evidence evidence) throws SQLException {
        return assemble(c,source,item,evidence,null);
    }
    /**
     * @param fix the land fix this build is (M4 land, spec §7.5, A7), or null. A fix goes through every check an ordinary
     *     build does; it clones the item branch at the approved head from the forge, pushes to that existing branch, never
     *     resumes a checkpoint, and is told what land refused.
     */
    public Prepared assemble(Connection c,WorkSourceRegistry.Source source,WorkItemEvent item,WorkArtifacts.Evidence evidence,
                             dev.codespire.orchestrator.work.WorkLandFixes.Fix fix) throws SQLException {
        String subject="work-"+item.progress().attemptId();
        // A plan step (M4 slice 3) builds its own prompt and start; a land fix after the plan is no step (decision 12).
        var step=dev.codespire.orchestrator.work.WorkStepAssembly.builds(item,fix!=null)?steps.step(c,item,evidence):null;
        var in=parse(source,item.preparation(),step==null?composedPrompt(evidence):step.prompt(),subject);
        // How the APPROVED preparation pays — not the build setup now, which may have changed since.
        boolean subscription=dev.codespire.contract.work.PayWith.SUBSCRIPTION.equals(item.preparation().payWith());
        var account=funding.account(c,source.repositoryId());
        // The model, the image, the price list, the spend gate and the seat or key, asked as a draft run asks them (RunFunding).
        RunFunding.Funded funded=funding.fund(new RunFunding.Choice(in.harness(),in.model(),item.preparation().effort(),subscription));
        String id=RunIds.of(source.scm(),in.workspace(),in.slug(),subject,1),branch=fix==null?DispatchRequestParser.RUN_BRANCH_PREFIX+subject:fix.branch();
        String baseBranch=fix==null?in.baseBranch():fix.branch(),baseCommit=fix==null?in.baseCommit():fix.approvedHead();
        long wall=Math.min(config.wallClockSeconds(),Math.subtractExact(item.policy().limits().maxWallClockSeconds(),item.progress().wallSeconds()));
        // A retried build (M4): the failure goes in its prompt, and it continues from the failed checkpoint
        // unless that could not be read.
        // Inside a land fix only that fix's own verify counts; a failure from before the pull request was opened is history.
        java.util.Optional<dev.codespire.orchestrator.work.WorkVerifyHistory.Previous> previous=verifyHistory.lastNotPassed(c,item.workItemId(),item.generation())
                .filter(p->fix==null || fix.runs().contains(p.runId()));
        String prompt=step!=null?in.prompt():fix==null
                ?in.prompt()+previous.map(p->dev.codespire.orchestrator.work.WorkVerifyHistory.promptSection(p.verification())).orElse("")
                :in.prompt()+dev.codespire.orchestrator.work.WorkLandFixes.promptSection(fix)
                        +previous.map(p->dev.codespire.orchestrator.work.WorkVerifyHistory.landFixPromptSection(p.verification())).orElse("");
        RunCommand.ExecuteRun command=new RunCommand.ExecuteRun(id,source.repository(),FactoryCloneUrls.cloneUrl(source.scm(),account.baseUrl(),source.repository()),
                // The image the checked list was read from, not the tag, which may name another by now.
                baseBranch,baseCommit,branch,prompt,in.harness(),in.model(),funded.image(),
                item.policy().limits().protectedPaths().stream().sorted().toList(),wall,
                credentials.packScm(id,account.botUsername(),account.secret()),credentials.packHarness(id,funded.handedOver()))
                // The level the approved binding hashed, so the build runs at what was approved (M3.5 part M).
                .atEffort(item.preparation().effort());
        if(subscription)command=command.paidBySignIn();
        if(fix!=null)command=command.onExistingBranch(fix.baseBranch());
        var resumable=previous.filter(p->fix==null && step==null && dev.codespire.orchestrator.work.WorkVerifyHistory.checkpointReadable(p.verification()));
        if(resumable.isPresent())command=command.fromCheckpoint(resumable.orElseThrow().runId(),resumable.orElseThrow().head());
        if(step!=null && step.start().fromRunId()!=null)command=command.fromCheckpoint(step.start().fromRunId(),step.start().fromHead());
        var held=new RunCommand.ExecuteWorkRun(command,new dev.codespire.contract.work.WorkRunBinding(
                item.workItemId(),item.generation(),item.progress().attemptId(),item.preparation().binding()));
        // The base tip the land effect read, never the workspace's: the held gate excuses only what that base brought.
        if(fix!=null && fix.baseTip()!=null)held=held.withMergedBaseTip(fix.baseTip());
        var row=new FactoryRunProjection.QueuedRun(id,in.harness(),in.model(),baseBranch,baseCommit,branch,account.botUsername(),funded.member().id());
        if(step!=null) {
            // Every plan step may finish empty (decision 14); one after a step that holds a bundle never falls back to the base (A2).
            held=held.allowingEmpty();
            if(step.start().required())held=held.requiringCheckpoint();
            row=row.atStep(step.index());
        }
        return new Prepared(held,subscription?row.paidBySubscription():row);
    }

}
