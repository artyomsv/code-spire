package dev.codespire.orchestrator.factory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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

    /**
     * The vendor account a sign-in belongs to: {@code tokens.account_id}, measured on codex-cli 0.156.1
     * (design §5.3). Empty when the file names none, which the caller refuses rather than guesses.
     */
    static java.util.Optional<String> accountOf(String storedFile) {
        try {
            String account = JSON.readTree(storedFile).path("tokens").path("account_id").asText("");
            return account.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(account);
        } catch (JsonProcessingException unreadable) {
            return java.util.Optional.empty();
        }
    }

    /** Every level, arrays included: the vendor may add a list of sessions, each with its own token. */
    private static void emptyRefreshTokens(JsonNode node) {
        if (node instanceof ArrayNode array) {
            array.forEach(SignInFiles::emptyRefreshTokens);
            return;
        }
        if (!(node instanceof ObjectNode object)) return;
        for (Iterator<Map.Entry<String, JsonNode>> fields = object.properties().iterator(); fields.hasNext(); ) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getKey().equals(REFRESH_TOKEN)) field.setValue(JSON.getNodeFactory().textNode(""));
            else emptyRefreshTokens(field.getValue());
        }
    }
}
