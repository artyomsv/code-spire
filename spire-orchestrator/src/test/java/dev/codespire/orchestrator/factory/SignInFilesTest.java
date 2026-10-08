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

    /** A JWT whose payload carries this expiry. Unsigned: only the payload is read, from our own stored file. */
    static String jwt(java.time.Instant expires) {
        java.util.Base64.Encoder url = java.util.Base64.getUrlEncoder().withoutPadding();
        return url.encodeToString("{\"alg\":\"none\"}".getBytes()) + "."
                + url.encodeToString(("{\"exp\":" + expires.getEpochSecond() + ",\"sub\":\"TEST\"}").getBytes()) + ".TEST-signature";
    }

    static String stored(String accessToken, String refreshToken) {
        return "{\"auth_mode\":\"chatgpt\",\"tokens\":{\"access_token\":\"" + accessToken + "\",\"id_token\":\"TEST-id\","
                + "\"refresh_token\":\"" + refreshToken + "\",\"account_id\":\"TEST-account\"},\"last_refresh\":\"1970-01-01T00:00:00Z\"}";
    }

    @Test
    void theAccessTokensExpiryIsReadFromItsPayload() {
        java.time.Instant expires = java.time.Instant.parse("2026-10-05T10:00:00Z");

        assertEquals(java.util.Optional.of(expires), SignInFiles.accessTokenExpiry(stored(jwt(expires), "TEST-refresh")));
        assertTrue(SignInFiles.accessTokenExpiry(stored("TEST-not-a-jwt", "TEST-refresh")).isEmpty());
        assertTrue(SignInFiles.accessTokenExpiry("TEST-not-a-sign-in").isEmpty());
    }

    @Test
    void aRenewalReplacesTheTokensAndKeepsTheAccount() throws Exception {
        java.time.Instant now = java.time.Instant.parse("2026-10-08T12:00:00Z");
        String answer = "{\"access_token\":\"TEST-access-2\",\"id_token\":\"TEST-id-2\",\"refresh_token\":\"TEST-refresh-2\"}";

        JsonNode renewed = JSON.readTree(SignInFiles.renewed(stored("TEST-access-1", "TEST-refresh-1"), answer, now));

        assertEquals("TEST-access-2", renewed.path("tokens").path("access_token").asText());
        assertEquals("TEST-id-2", renewed.path("tokens").path("id_token").asText());
        assertEquals("TEST-refresh-2", renewed.path("tokens").path("refresh_token").asText());
        assertEquals("TEST-account", renewed.path("tokens").path("account_id").asText());
        assertEquals("chatgpt", renewed.path("auth_mode").asText());
        assertEquals(now.toString(), renewed.path("last_refresh").asText());
    }

    /** The vendor may answer without a new refresh token; the stored one then stays the one to use. */
    @Test
    void aRenewalWithoutANewRefreshTokenKeepsTheOldOne() throws Exception {
        JsonNode renewed = JSON.readTree(SignInFiles.renewed(stored("TEST-access-1", "TEST-refresh-1"),
                "{\"access_token\":\"TEST-access-2\"}", java.time.Instant.EPOCH));

        assertEquals("TEST-refresh-1", renewed.path("tokens").path("refresh_token").asText());
        assertEquals("TEST-id", renewed.path("tokens").path("id_token").asText());
    }

    @Test
    void aRenewalAnswerWithoutAnAccessTokenIsRefusedWithoutQuotingIt() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> SignInFiles.renewed(stored("TEST-access-1", "TEST-refresh-1"), "{\"refresh_token\":\"TEST-quoted\"}",
                        java.time.Instant.EPOCH));
        assertEquals("renewal_unreadable", refused.getMessage());
    }

    @Test
    void theRefreshTokenIsReadOnlyWhenThereIsOne() {
        assertEquals(java.util.Optional.of("TEST-refresh"), SignInFiles.refreshTokenOf(stored("TEST-access", "TEST-refresh")));
        assertTrue(SignInFiles.refreshTokenOf(stored("TEST-access", "")).isEmpty());
    }

    @Test
    void storedBytesThatAreNotAJsonObjectAreRefusedWithoutQuotingThem() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> SignInFiles.forAgent("TEST-not-a-sign-in"));
        assertEquals("subscription_unreadable", refused.getMessage());
    }
}
