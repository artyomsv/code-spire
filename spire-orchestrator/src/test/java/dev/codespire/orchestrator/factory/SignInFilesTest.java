package dev.codespire.orchestrator.factory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The agent may hold the short-lived half of a sign-in, never the refresh token (M3.5 part F, design §5.6).
 * Placeholder values: this is about what reaches the agent.
 */
class SignInFilesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String STORED = "{\"auth_mode\":\"chatgpt\",\"tokens\":{\"access_token\":\"TEST-access\","
            + "\"id_token\":\"TEST-id\",\"refresh_token\":\"TEST-refresh-must-not-leave\",\"account_id\":\"TEST-account\"},"
            + "\"last_refresh\":\"1970-01-01T00:00:00Z\"}";

    @Test
    void theRefreshTokenIsEmptiedAndEverythingElseKept() throws Exception {
        String forAgent = SignInFiles.forAgent(STORED);
        JsonNode file = JSON.readTree(forAgent);

        assertFalse(forAgent.contains("TEST-refresh-must-not-leave"));
        // Emptied, not removed: the vendor's CLI refuses a file whose refresh_token field is missing.
        assertTrue(file.path("tokens").has("refresh_token"));
        assertEquals("", file.path("tokens").path("refresh_token").asText());
        assertEquals("TEST-access", file.path("tokens").path("access_token").asText());
        assertEquals("TEST-id", file.path("tokens").path("id_token").asText());
        assertEquals("chatgpt", file.path("auth_mode").asText());
    }

    /** A token inside a list is still a refresh token (review of PR #178). */
    @Test
    void aRefreshTokenInsideAListIsEmptiedToo() throws Exception {
        String stored = "{\"sessions\":[{\"refresh_token\":\"TEST-listed-refresh\",\"access_token\":\"TEST-listed-access\"}]}";

        String forAgent = SignInFiles.forAgent(stored);

        assertFalse(forAgent.contains("TEST-listed-refresh"));
        assertEquals("", JSON.readTree(forAgent).path("sessions").path(0).path("refresh_token").asText());
        assertTrue(forAgent.contains("TEST-listed-access"));
    }

    @Test
    void storedBytesThatAreNotAJsonObjectAreRefusedWithoutQuotingThem() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> SignInFiles.forAgent("TEST-not-a-sign-in"));
        assertEquals("subscription_unreadable", refused.getMessage());
    }
}
