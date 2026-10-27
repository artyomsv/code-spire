package dev.codespire.orchestrator.factory;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunIds;
import dev.codespire.contract.work.PayWith;
import dev.codespire.contract.work.WorkDraftBinding;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.work.WorkSourceRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Builds the command of a specification or plan run (M4 slice 3, ADR-049): the repository's build harness, model, effort
 * and payment, a fresh clone of the base at the commit the dispatcher read, and the draft binding. It is funded exactly
 * as a build is ({@link RunFunding}) and validated by the same parser, so a draft run refused here would have been
 * refused as a build too. Nothing is pushed: the command is an {@code ExecuteWorkDraftRun}, which an older worker refuses.
 */
@ApplicationScoped
public class WorkDraftAssembly {
    @Inject FactoryConfig config;
    @Inject RunFunding funding;
    @Inject RunCredentials credentials;

    /**
     * @param setup the build setup the run is dispatched with; its revision is what the draft records (decision 15)
     * @param baseCommit the head of the setup's base branch, read from the forge by the caller before its claim
     */
    public record Request(WorkSourceRegistry.Source source, WorkItemEvent item, BuildDefaults.Defaults setup,
                          String prompt, String baseCommit, WorkDraftBinding binding) {}

    public record Prepared(RunCommand.ExecuteWorkDraftRun command, FactoryRunProjection.QueuedRun row) {}

    /** Caller holds the registry and item locks. Local reads and encryption only, no network calls. */
    public Prepared assemble(Connection c, Request request) throws SQLException {
        WorkSourceRegistry.Source source = request.source();
        BuildDefaults.Defaults setup = request.setup();
        String subject = "draft-" + request.binding().attemptId();
        DispatchRequestParser.Parsed in = DispatchRequestParser.parse(new RunResource.DispatchRequest(source.repository().workspace(),
                source.repository().slug(), source.scm().providerType(), setup.baseBranch(), request.baseCommit(), request.prompt(),
                setup.harness(), setup.model(), subject, null, source.repositoryId()), config);
        ScmProvider account = funding.account(c, source.repositoryId());
        boolean subscription = PayWith.SUBSCRIPTION.equals(setup.payWith());
        RunFunding.Funded funded = funding.fund(new RunFunding.Choice(in.harness(), in.model(), setup.effort(), subscription));
        String id = RunIds.of(source.scm(), in.workspace(), in.slug(), subject, 1);
        String branch = RunCommand.ExecuteWorkDraftRun.DRAFT_BRANCH_PREFIX + request.binding().attemptId();
        WorkItemEvent item = request.item();
        long wall = Math.min(config.wallClockSeconds(), Math.subtractExact(item.policy().limits().maxWallClockSeconds(), item.progress().wallSeconds()));
        RunCommand.ExecuteRun execution = new RunCommand.ExecuteRun(id, source.repository(),
                FactoryCloneUrls.cloneUrl(source.scm(), account.baseUrl(), source.repository()), in.baseBranch(), in.baseCommit(), branch,
                in.prompt(), in.harness(), in.model(), funded.image(), item.policy().limits().protectedPaths().stream().sorted().toList(), wall,
                credentials.packScm(id, account.botUsername(), account.secret()), credentials.packHarness(id, funded.handedOver()))
                .atEffort(setup.effort());
        if (subscription) execution = execution.paidBySignIn();
        FactoryRunProjection.QueuedRun row = new FactoryRunProjection.QueuedRun(id, in.harness(), in.model(), in.baseBranch(), in.baseCommit(),
                branch, account.botUsername(), funded.member().id()).ofKind(RunKind.valueOf(request.binding().kind().name()));
        return new Prepared(new RunCommand.ExecuteWorkDraftRun(id, execution, request.binding()), subscription ? row.paidBySubscription() : row);
    }
}
