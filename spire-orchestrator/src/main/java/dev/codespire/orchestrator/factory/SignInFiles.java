package dev.codespire.orchestrator.factory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

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

    /**
     * When the access token expires: the {@code exp} claim of its JWT payload. The signature is not
     * checked — this is our own stored file, read only to decide when to renew it. Empty when the token
     * is not a JWT with an expiry; the caller then leaves the seat alone rather than guess.
     */
    static Optional<Instant> accessTokenExpiry(String storedFile) {
        try {
            String[] parts = JSON.readTree(storedFile).path("tokens").path("access_token").asText("").split("\\.");
            if (parts.length < 2) return Optional.empty();
            JsonNode exp = JSON.readTree(Base64.getUrlDecoder().decode(parts[1])).path("exp");
            return exp.canConvertToLong() ? Optional.of(Instant.ofEpochSecond(exp.asLong())) : Optional.empty();
        } catch (IOException | IllegalArgumentException unreadable) {
            return Optional.empty();
        }
    }

    /** The stored refresh token, when there is one. Read only by the renewal, never for an agent. */
    static Optional<String> refreshTokenOf(String storedFile) {
        try {
            String token = JSON.readTree(storedFile).path("tokens").path(REFRESH_TOKEN).asText("");
            return token.isBlank() ? Optional.empty() : Optional.of(token);
        } catch (JsonProcessingException unreadable) {
            return Optional.empty();
        }
    }

    /**
     * The stored file with the vendor's renewal answer in it: the new access token, and the new id and
     * refresh tokens when the answer has them. Everything else — the account above all — is kept, and
     * {@code last_refresh} is set to now, so the vendor's CLI in a run does not try to renew it again.
     *
     * @throws IllegalStateException named {@code renewal_unreadable} when the answer has no access token,
     *     never with the answer in the message, which holds credentials
     */
    static String renewed(String storedFile, String answer, Instant now) {
        try {
            JsonNode tokens = JSON.readTree(answer);
            String access = tokens.path("access_token").asText("");
            JsonNode file = JSON.readTree(storedFile);
            if (access.isBlank() || !(file instanceof ObjectNode object) || !(file.path("tokens") instanceof ObjectNode stored))
                throw new IllegalStateException("renewal_unreadable");
            stored.put("access_token", access);
            for (String kept : new String[]{"id_token", REFRESH_TOKEN}) {
                String renewed = tokens.path(kept).asText("");
                if (!renewed.isBlank()) stored.put(kept, renewed);
            }
            object.put("last_refresh", now.toString());
            return JSON.writeValueAsString(object);
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException("renewal_unreadable");
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
