package dev.codespire.orchestrator.ingress;

import dev.codespire.contract.event.IntegrationEvent.PrAction;
import dev.codespire.contract.event.IntegrationEvent.PullRequestEventReceived;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A manual registration cannot know a pull request's provenance, so its event says "unknown" (harness reviews, step 8). */
class ManualRegistrationEventTest {

    @Test
    void aManualRegistrationDoesNotAssertWhetherItIsAFork() {
        PullRequest pr = new PullRequest(new RepoRef("TEST-WS", "TEST-REPO"), 7L, "TEST-title", "TEST-body", "TEST-source",
                "TEST-main", "0123456789abcdef0123456789abcdef01234567", Author.of("TEST-id", "test-user", "TEST User"),
                "https://example.invalid/pr/7");

        PullRequestEventReceived event = ManualRegisterResource.registrationEvent(pr, "github");

        assertNull(event.fromFork(), "unknown, never false: false would make a fork trusted");
        assertEquals(PrAction.OPENED, event.action());
        assertEquals("github", event.providerType());
        assertEquals(7L, event.prId());
    }
}
