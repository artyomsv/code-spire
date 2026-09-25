package dev.codespire.orchestrator.factory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

/**
 * The copy of a stored sign-in that an agent may hold (M3.5 part F).
 *
 * <p>The agent runs untrusted ticket text at full shell access, so whatever it is given, it can read. An
 * access token expires on its own; a refresh token does not, and an agent that reads one holds the seat
 * until a person revokes it. So every refresh token in the file is EMPTIED before the file leaves the
 * orchestrator — emptied rather than removed, because the vendor's CLI refuses a file whose
 * {@code refresh_token} field is missing but accepts an empty one and runs on the access token (measured
 * 2026-09-25, codex-cli 0.156.1: design §5.3).
 */
final class SignInFiles {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REFRESH_TOKEN = "refresh_token";

    private SignInFiles() {
    }

    /**
     * @throws IllegalStateException named {@code subscription_unreadable} when the stored bytes are not a
     *     JSON object — never with the bytes in the message, which are a credential
     */
    static String forAgent(String storedFile) {
        try {
            JsonNode file = JSON.readTree(storedFile);
            if (!(file instanceof ObjectNode object)) throw new IllegalStateException("subscription_unreadable");
            emptyRefreshTokens(object);
            return JSON.writeValueAsString(object);
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException("subscription_unreadable");
        }
    }

    private static void emptyRefreshTokens(ObjectNode node) {
        for (Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator(); fields.hasNext(); ) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getKey().equals(REFRESH_TOKEN)) field.setValue(JSON.getNodeFactory().textNode(""));
            else if (field.getValue() instanceof ObjectNode child) emptyRefreshTokens(child);
        }
    }
}
