package dev.codespire.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.event.EventKeys;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchivedNoticeWireTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void theArchivedNoticeRoundTripsOverTheWire() throws Exception {
        ActionCommand command = new ActionCommand.NotifyArchived(
                "review::TEST-WS/TEST-REPO#1", new RepoRef("TEST-WS", "TEST-REPO"), 1L,
                new ThreadRef("TEST-THREAD"), "TEST-CREDENTIAL");

        String json = mapper.writeValueAsString(command);
        assertEquals(command, mapper.readValue(json, ActionCommand.class));
        assertTrue(json.contains("\"NotifyArchived\""), "the discriminator names the subtype");
    }

    @Test
    void aTopLevelNoticeCarriesNoThread() throws Exception {
        ActionCommand command = new ActionCommand.NotifyArchived(
                "review::TEST-WS/TEST-REPO#1", new RepoRef("TEST-WS", "TEST-REPO"), 1L, null,
                "TEST-CREDENTIAL");
        assertEquals(command, mapper.readValue(mapper.writeValueAsString(command), ActionCommand.class));
    }

    @Test
    void theArchivedNotifiedEventIsKeyedByReviewId() {
        IntegrationEvent event = new IntegrationEvent.ArchivedNotified(
                "review::TEST-WS/TEST-REPO#1", new ThreadRef("TEST-THREAD"), "TEST-COMMENT");
        assertEquals("review::TEST-WS/TEST-REPO#1", EventKeys.of(event));
    }
}
