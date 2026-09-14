package dev.codespire.orchestrator.repository;

import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.RepositoryDelivery;
import dev.codespire.contract.event.RepositoryEventKind;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.RepoRef;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class UnregisteredRepositoryAttentionTest extends RepositoryFixture {
    @Inject RepositoryIngressConsumer ingress;
    @Inject dev.codespire.orchestrator.attention.AttentionQueries attention;

    @Test void namesRepositoryOriginAndRegistration() throws Exception {
        UUID registration = UUID.randomUUID(); createdRegistrations.add(registration);
        account("REVIEWER"); // An account at this origin does not enroll repositories.
        RepoRef repo = dev.codespire.contract.event.ReviewIds.parse("review::" + workspace + "/TEST-repo#1").repo();
        IntegrationEvent event = new IntegrationEvent.PullRequestEventReceived(repo, 1, IntegrationEvent.PrAction.OPENED,
                "TEST-title", "TEST-description", "TEST-branch", "main", "TEST-sha", Author.of("TEST-author", "TEST-author", ""),
                origin + "/" + repo.full() + "/-/merge_requests/1", "gitlab");
        ingress.accept(new RepositoryDelivery(null, registration, 1, "gitlab", origin, RepositoryEventKind.REVIEWER,
                "TEST-delivery", event));
        var row = attention.collect().stream().filter(a -> a.code().equals("REPOSITORY_NOT_REGISTERED")
                && a.message().contains(registration.toString())).findFirst().orElseThrow();
        assertTrue(row.message().contains(repo.full())); assertTrue(row.message().contains(origin));
        var action = java.net.URLDecoder.decode(row.action(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(action.contains("register=true")); assertTrue(action.contains("registration=" + registration));
        assertTrue(action.contains("workspace=" + workspace)); assertTrue(action.contains("slug=TEST-repo"));
        assertTrue(action.contains("forgeOrigin=" + origin));
        assertTrue(repositories.find("gitlab", origin, workspace, "TEST-repo").isEmpty());
        repositories.create(repository(null, null));
        assertTrue(attention.collect().stream().noneMatch(a -> a.code().equals("REPOSITORY_NOT_REGISTERED")
                && a.message().contains(registration.toString())), "Registering the repository clears the condition");
    }
}
