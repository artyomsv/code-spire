package dev.codespire.harness.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sign-in file a proxied seat run writes where Codex reads one: the shape measured against codex-cli 0.156.1 in the
 * 2026-11-07 spike, holding nothing that signs anyone in. The model proxy injects the real access token.
 */
class PlaceholderSignInTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void theFileHasTheMeasuredShapeAndNoCredential() throws Exception {
        JsonNode file = JSON.readTree(PlaceholderSignIn.file());

        assertEquals("chatgpt", file.path("auth_mode").asText());
        assertTrue(file.has("OPENAI_API_KEY") && file.path("OPENAI_API_KEY").isNull(), file.toString());
        assertEquals("", file.path("tokens").path("refresh_token").asText("absent"));
        assertEquals("00000000-0000-4000-8000-000000000000", file.path("tokens").path("account_id").asText());
        assertFalse(file.path("last_refresh").asText("").isBlank(), file.toString());
    }

    /** A far-future expiry is what keeps Codex from renewing at auth.openai.com, which a run never reaches. */
    @Test
    void eachTokenIsAnUnsignedJwtThatNeverExpires() throws Exception {
        JsonNode tokens = JSON.readTree(PlaceholderSignIn.file()).path("tokens");

        for (String name : new String[]{"id_token", "access_token"}) {
            String[] parts = tokens.path(name).asText().split("\\.", -1);
            assertEquals(3, parts.length, name);
            assertFalse(parts[2].isEmpty(), name + ": Codex refuses a token with an empty third part");
            assertEquals(JSON.readTree("{\"alg\":\"none\",\"typ\":\"JWT\"}"), decoded(parts[0]), name);

            JsonNode payload = decoded(parts[1]);
            assertEquals(4102444800L, payload.path("exp").asLong(), name);
            assertTrue(payload.path("spire_placeholder").asBoolean(false), name);
            JsonNode auth = payload.path("https://api.openai.com/auth");
            assertEquals("00000000-0000-4000-8000-000000000000", auth.path("chatgpt_account_id").asText(), name);
            assertFalse(auth.path("chatgpt_plan_type").asText("").isBlank(), name);
            assertFalse(auth.path("chatgpt_user_id").asText("").isBlank(), name);
        }
    }

    /** The same file every time, so a proxied command and environment do not depend on when they were built. */
    @Test
    void theFileIsTheSameEveryTime() {
        assertEquals(PlaceholderSignIn.file(), PlaceholderSignIn.file());
    }

    /** Built from claims at run time: no JWT literal sits in the source, where a secret scanner would flag it. */
    @Test
    void noJwtLiteralExistsInTheSource() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/codespire/harness/codex/PlaceholderSignIn.java"), StandardCharsets.UTF_8);

        assertFalse(source.contains("ey" + "J"), "a JWT starts with the base64 of '{\"'");
    }

    private static JsonNode decoded(String part) throws Exception {
        return JSON.readTree(Base64.getUrlDecoder().decode(part));
    }
}
