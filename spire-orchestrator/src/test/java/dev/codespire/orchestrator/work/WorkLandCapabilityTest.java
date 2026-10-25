package dev.codespire.orchestrator.work;

import dev.codespire.contract.port.PullRequestLander;
import dev.codespire.contract.scm.PullRequestRef;
import dev.codespire.contract.work.WorkExecution;
import dev.codespire.contract.work.WorkRunBinding;
import dev.codespire.orchestrator.provider.ProviderClients;
import dev.codespire.orchestrator.provider.ProviderRole;
import dev.codespire.orchestrator.provider.ScmProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When land may run at all (M4 land, spec §7.2, §7.3): an operator's approval, a delivered pull request, and a
 * forge that has a lander. {@code auto_if_green} and every forge but GitHub stay unavailable (non-goals).
 */
class WorkLandCapabilityTest {
    static final PullRequestRef PR = new PullRequestRef(901, "https://forge.example.test/TEST-pull/901", false);
    static final PullRequestLander GITHUB_LIKE = new PullRequestLander() {
        @Override public boolean available() { return true; }
    };

    static WorkExecution execution(PullRequestRef pullRequest) {
        return new WorkExecution("TEST-land-run", new WorkRunBinding("work-v1-" + "0".repeat(64), 1, UUID.randomUUID(), "a".repeat(64)),
                "b".repeat(40), null, null, pullRequest, pullRequest == null ? null : "TEST-review");
    }

    @Test void approveWithADeliveredPullRequestOnAForgeWithALanderIsAvailable() {
        assertTrue(WorkPhaseCapability.landAvailable("approve", execution(PR), GITHUB_LIKE));
    }

    @Test void autoIfGreenStaysUnavailable() {
        assertFalse(WorkPhaseCapability.landAvailable("auto_if_green", execution(PR), GITHUB_LIKE));
    }

    @Test void landStaysUnavailableOffGitHub() {
        // The real factory hands every forge but GitHub a lander that is not available.
        ScmProvider gitlab = new ScmProvider(UUID.randomUUID(), "TEST-gitlab", "gitlab", "https://gitlab.example.test", "bearer", null,
                "TEST-secret", "900124", true, List.of(), "TEST-bot", null, ProviderRole.FACTORY);
        PullRequestLander lander = new ProviderClients().pullRequestLander(gitlab);
        assertFalse(WorkPhaseCapability.landAvailable("approve", execution(PR), lander));
    }

    @Test void noDeliveredPullRequestIsUnavailable() {
        assertFalse(WorkPhaseCapability.landAvailable("approve", execution(null), GITHUB_LIKE));
        assertFalse(WorkPhaseCapability.landAvailable("approve", null, GITHUB_LIKE));
    }

    @Test void noFactoryAccountIsUnavailable() {
        assertFalse(WorkPhaseCapability.landAvailable("approve", execution(PR), null));
    }
}
