package dev.codespire.orchestrator.work;

import dev.codespire.contract.port.PullRequestLander;
import dev.codespire.contract.work.WorkExecution;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.contract.work.WorkPolicy;
import jakarta.enterprise.context.ApplicationScoped;

/** M3 policy can request a phase; it cannot invent an executor. Slice 8 binds prepared artifacts and held builds. */
@ApplicationScoped
public class WorkPhaseCapability {
    @jakarta.inject.Inject WorkRunTransport runs;
    @jakarta.inject.Inject WorkDelivery delivery;
    @jakarta.inject.Inject WorkVerifyTransport verifies;
    @jakarta.inject.Inject dev.codespire.orchestrator.factory.MachineAccounts accounts;
    @jakarta.inject.Inject dev.codespire.orchestrator.provider.ProviderClients clients;
    public boolean available(WorkItemEvent item,String phase) {
        return switch(phase) {
            // A generated item writes its specification and plan with short read-only runs on the build's transport (M4
            // slice 3); an item without a draft keeps both unavailable, which keeps items parked before a switch parked (A16).
            case "spec","plan" -> item.draft()!=null && runs.available();
            case "build" -> item.preparation()!=null && runs.available();
            // An empty command list is still available: it runs and answers no_checks_declared (M4, spec §4.2).
            case "verify" -> item.preparation()!=null && item.progress().execution()!=null && verifies.available();
            case "deliver","review" -> delivery.available(item,phase);
            case "land" -> landAvailable(item.policy().effective().get(WorkPolicy.Phase.LAND),item.progress().execution(),
                    accounts.resolve(item.repositoryId()).map(clients::pullRequestLander).orElse(null));
            default -> false;
        };
    }
    /**
     * Land merges only on an operator's approval ({@code auto_if_green} stays unavailable), only a delivered pull
     * request, and only through the FACTORY account's lander, which GitHub alone provides (M4 land, spec §7.2, §7.3).
     */
    static boolean landAvailable(String mode,WorkExecution execution,PullRequestLander lander) {
        return "approve".equals(mode) && execution!=null && execution.pullRequest()!=null && lander!=null && lander.available();
    }
}
