package dev.codespire.contract.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.IntegrationEvent.FollowUpPosted;
import dev.codespire.contract.event.IntegrationEvent.ManualCommandReceived;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadLocation;
import dev.codespire.contract.scm.ThreadRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrationEventWireTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void manualCommandCarriesThreadContextAcrossTheWire() throws Exception {
        ManualCommandReceived original = new ManualCommandReceived(
                new RepoRef("acme", "widgets"), 7, "finding", "major shadows the field",
                Author.of("u-1", "octocat", "octocat"),
                new ThreadRef("thread-9"), new ThreadLocation("src/Foo.java", 44), "c-901");

        String json = mapper.writeValueAsString(original);
        IntegrationEvent back = mapper.readValue(json, IntegrationEvent.class);

        assertEquals(original, back);
    }

    @Test
    void manualCommandWithoutThreadContextStillDeserializes() throws Exception {
        // The shape every command on the wire has today: no threadRef, no location, no commentId.
        String legacy = """
                {"type":"ManualCommandReceived","repo":{"workspace":"acme","slug":"widgets"},
                 "prId":7,"command":"review","args":"",
                 "author":{"providerUserId":"u-1","username":"octocat"}}""";

        ManualCommandReceived back = (ManualCommandReceived) mapper.readValue(legacy, IntegrationEvent.class);

        assertNull(back.threadRef());
        assertNull(back.location());
        assertNull(back.commentId());
        assertEquals("review", back.command());
    }

    /**
     * A FollowUpPosted recorded before the turn receipts existed has no countsTurn (review of PR #371, M-1): it reads
     * as one that does not count, so a dead letter or a redelivery of it never consumes a turn after the deploy.
     */
    @Test
    void aFollowUpPostedFromBeforeTheReceiptsDoesNotCountATurn() throws Exception {
        String legacy = """
                {"type":"FollowUpPosted","reviewId":"review::TEST-WS/TEST-REPO#1",
                 "threadRef":{"value":"TEST-thread"},"commentId":"TEST-answer"}""";

        FollowUpPosted back = (FollowUpPosted) mapper.readValue(legacy, IntegrationEvent.class);

        assertEquals(new FollowUpPosted("review::TEST-WS/TEST-REPO#1", new ThreadRef("TEST-thread"), "TEST-answer",
                false), back);
    }

    @Test
    void aFollowUpPostedThatCountsATurnKeepsItAcrossTheWire() throws Exception {
        FollowUpPosted original = new FollowUpPosted("review::TEST-WS/TEST-REPO#1", new ThreadRef("TEST-thread"),
                "TEST-answer", true);

        IntegrationEvent back = mapper.readValue(mapper.writeValueAsString(original), IntegrationEvent.class);

        assertEquals(original, back);
        assertTrue(((FollowUpPosted) back).countsTurn());
    }
}
