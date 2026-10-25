package dev.codespire.orchestrator.work;

import dev.codespire.contract.port.ActorDirectory;
import dev.codespire.contract.port.PullRequestLander;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.orchestrator.provider.ProviderClients;
import dev.codespire.orchestrator.provider.ProviderRole;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.repository.RepositoryAccounts;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * The forge reads around a landing (M4 land, spec §7.8): the pull request and the merger's handle, read through the
 * repository's FACTORY account, or its REVIEWER account when the factory is unavailable. A forge read never runs under
 * the item lock, so the land transitions read here first.
 */
@ApplicationScoped
public class WorkLandReads {
    private static final Logger LOG = Logger.getLogger(WorkLandReads.class);

    @Inject WorkSourceRegistry sources;
    @Inject RepositoryAccounts accounts;
    @Inject ProviderClients clients;

    /**
     * The pull request as GitHub reports it now; null when it cannot be read. The reader only reads, so the reviewer's
     * token is never asked to merge (code-quality review of the branch).
     */
    PullRequestLander.LandState readPullRequest(WorkItemEvent item,long pullRequest) {
        Optional<ScmProvider> account=readingAccount(item.repositoryId());
        if(account.isEmpty())return null;
        try {
            PullRequestLander reader=clients.pullRequestReader(account.orElseThrow());
            if(!reader.available())return null;
            return reader.read(sources.get(item.sourceId()).orElseThrow(NotFoundException::new).repository(),pullRequest);
        }catch(RuntimeException unreadable) {
            LOG.warnf("Work item %s: pull request #%d could not be read (%s)",item.workItemId(),pullRequest,WorkLandTarget.cause(unreadable));
            return null;
        }
    }
    /** The merger's observed handle; null when neither account can say. */
    String mergerHandle(WorkItemEvent item,String providerUserId) {
        Optional<ScmProvider> account=readingAccount(item.repositoryId());
        if(account.isEmpty())return null;
        try {
            ActorDirectory.Result result=clients.actorDirectory(account.orElseThrow()).byId(providerUserId);
            boolean found=result.status()==ActorDirectory.Status.FOUND && result.actors().size()==1
                    && providerUserId.equals(result.actors().getFirst().providerUserId());
            return found?result.actors().getFirst().handle():null;
        }catch(RuntimeException unavailable) {
            LOG.warnf("Work item %s: the merger's handle could not be read (%s); the ticket names a person",item.workItemId(),WorkLandTarget.cause(unavailable));
            return null;
        }
    }
    private Optional<ScmProvider> readingAccount(UUID repository) {
        return accounts.resolve(repository,ProviderRole.FACTORY).or(()->accounts.resolve(repository,ProviderRole.REVIEWER));
    }
}
