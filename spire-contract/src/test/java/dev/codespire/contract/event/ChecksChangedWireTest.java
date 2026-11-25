package dev.codespire.contract.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Review waits for CI: the wake-up rides the Reviewer webhook's delivery, keyed and scoped by its repository. */
class ChecksChangedWireTest {

    private static final RepoRef REPO = new RepoRef("TEST-owner", "TEST-repo");
    private static final String HEAD = "a".repeat(40);

    private static IntegrationEvent.ChecksChanged event() {
        return new IntegrationEvent.ChecksChanged(REPO, HEAD);
    }

    @Test
    void onlyTheReviewerKindAcceptsIt() {
        assertTrue(RepositoryEventKind.REVIEWER.accepts(event()));
        assertFalse(RepositoryEventKind.FACTORY.accepts(event()));
        assertFalse(RepositoryEventKind.ISSUE.accepts(event()));
    }

    @Test
    void itIsKeyedAndScopedByItsRepository() {
        assertEquals(REPO.full(), EventKeys.of(event()));
        RepositoryDelivery delivery = new RepositoryDelivery(UUID.randomUUID(), null, 0, "github", null,
                RepositoryEventKind.REVIEWER, "TEST-delivery", event());
        assertEquals(REPO, delivery.repo());
    }

    @Test
    void itRoundTripsInsideADelivery() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RepositoryDelivery delivery = new RepositoryDelivery(UUID.randomUUID(), null, 0, "github", null,
                RepositoryEventKind.REVIEWER, "TEST-delivery", event());
        RepositoryDelivery back = mapper.readValue(mapper.writeValueAsString(delivery), RepositoryDelivery.class);
        assertEquals(event(), back.event());
    }
}
