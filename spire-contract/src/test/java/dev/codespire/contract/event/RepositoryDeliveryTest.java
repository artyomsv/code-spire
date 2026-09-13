package dev.codespire.contract.event;

import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.RepoRef;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryDeliveryTest {
    private final UUID repository = UUID.randomUUID();
    private final UUID registration = UUID.randomUUID();
    private final IntegrationEvent event = new IntegrationEvent.PullRequestEventReceived(
            new RepoRef("TEST-team", "TEST-repo"), 1, IntegrationEvent.PrAction.OPENED,
            "TEST", "", "TEST-feature", "main", "abc1234", Author.of("TEST-id", "TEST-user", ""),
            "https://forge.example.test/TEST-team/TEST-repo/pull/1", "github");

    @Test void requiresARepositoryOrRegistration() {
        assertNotNull(delivery(repository, null, 0, "TEST-id"));
        assertNotNull(delivery(null, registration, 1, "TEST-id"));
        assertThrows(IllegalArgumentException.class, () -> delivery(null, null, 0, "TEST-id"));
    }

    @Test void requiresAPositiveRegistrationRevision() {
        assertNotNull(delivery(null, registration, 1, "TEST-id"));
        assertThrows(IllegalArgumentException.class, () -> delivery(null, registration, 0, "TEST-id"));
    }

    @Test void requiresANonblankDeliveryId() {
        assertNotNull(delivery(repository, null, 0, "TEST-id"));
        assertThrows(IllegalArgumentException.class, () -> delivery(repository, null, 0, " "));
    }

    @Test void verifiedEnvelopeRoundTripsWithProvenance() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        RepositoryDelivery delivery = delivery(repository, registration, 17, "TEST-delivery");
        String encoded = mapper.writeValueAsString(delivery);
        assertEquals("RepositoryDelivery", mapper.readTree(encoded).get("type").textValue());
        assertEquals(delivery, mapper.readValue(encoded, RepositoryDelivery.class));
    }

    private RepositoryDelivery delivery(UUID repo, UUID hook, long revision, String deliveryId) {
        return new RepositoryDelivery(repo, hook, revision, "github", "https://forge.example.test",
                RepositoryEventKind.REVIEWER, deliveryId, event);
    }
}
