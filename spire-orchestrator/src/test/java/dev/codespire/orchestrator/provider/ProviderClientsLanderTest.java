package dev.codespire.orchestrator.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.port.PullRequestLander;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Land is GitHub only, and only the FACTORY account merges or deletes a branch; the REVIEWER account may only read. */
class ProviderClientsLanderTest {
    private final ProviderClients clients = newClients();

    private static ProviderClients newClients() {
        ProviderClients created = new ProviderClients();
        created.mapper = new ObjectMapper();
        return created;
    }

    private static ScmProvider provider(String type, String baseUrl, ProviderRole role) {
        return new ScmProvider(UUID.randomUUID(), "TEST-bot", type, baseUrl, "bearer",
                null, "TEST-secret", "900123", true, List.of(), "TEST-bot", "INTERACTIVE", role);
    }

    @Test
    void onlyGitHubLands() {
        assertTrue(clients.pullRequestLander(
                provider("github", "https://api.github.example.test", ProviderRole.FACTORY)).available());
        assertFalse(clients.pullRequestLander(
                provider("gitlab", "https://gitlab.example.test", ProviderRole.FACTORY)).available());
        assertFalse(clients.pullRequestLander(
                provider("bitbucket-cloud", "https://api.bitbucket.example.test/2.0", ProviderRole.FACTORY)).available());
    }

    @Test
    void aReviewerAccountCannotLand() {
        assertThrows(IllegalArgumentException.class, () -> clients.pullRequestLander(
                provider("github", "https://api.github.example.test", ProviderRole.REVIEWER)));
        assertThrows(IllegalArgumentException.class, () -> clients.pullRequestLander(
                provider("github", "https://api.github.example.test", ProviderRole.CONTEXT)));
    }

    @Test
    void aReviewerAccountReadsButNeverMergesOrDeletes() {
        PullRequestLander reader = clients.pullRequestReader(
                provider("github", "https://api.github.example.test", ProviderRole.REVIEWER));
        assertTrue(reader.available());
        RepoRef repo = new RepoRef("TEST-owner", "TEST-repo");
        assertThrows(UnsupportedOperationException.class, () -> reader.merge(repo, new PullRequestLander.MergeRequest(901, "b".repeat(40), PullRequestLander.MergeMethod.MERGE)));
        assertThrows(UnsupportedOperationException.class, () -> reader.deleteBranch(repo, "TEST-branch", "b".repeat(40)));
        assertThrows(UnsupportedOperationException.class, () -> reader.allowedMethods(repo));
        assertFalse(clients.pullRequestReader(
                provider("gitlab", "https://gitlab.example.test", ProviderRole.REVIEWER)).available());
        assertThrows(IllegalArgumentException.class, () -> clients.pullRequestReader(
                provider("github", "https://api.github.example.test", ProviderRole.CONTEXT)));
    }
}
