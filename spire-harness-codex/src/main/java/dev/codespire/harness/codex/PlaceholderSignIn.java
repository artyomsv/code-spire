package dev.codespire.harness.codex;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The sign-in file a proxied seat run writes where Codex reads one. It signs nobody in: the model proxy drops the
 * {@code Authorization} and {@code chatgpt-account-id} the agent sends and injects the seat's real ones.
 *
 * <p>The shape was measured against codex-cli 0.156.1 in the 2026-11-07 spike: {@code auth_mode} {@code chatgpt}, no
 * key, two unsigned JWTs carrying the account claims Codex reads, an empty refresh token and a fixed account. The
 * tokens' expiry is far in the future because a token Codex believes expired makes it renew at {@code auth.openai.com},
 * which a run never reaches. {@code last_refresh} is the same instant, so the file is the same on every call.
 *
 * <p>The JWTs are built from their claims here, never written out: a JWT literal anywhere in the repository is what
 * the secret scanner exists to refuse.
 */
public final class PlaceholderSignIn {

    /** A valid version 4 UUID that names no account. */
    public static final String ACCOUNT_ID = "00000000-0000-4000-8000-000000000000";

    /** 2100-01-01T00:00:00Z. */
    static final long EXPIRES_EPOCH_SECONDS = 4_102_444_800L;

    private static final String LAST_REFRESH = "2100-01-01T00:00:00Z";

    /** The claim object Codex reads the account and plan from. */
    private static final String AUTH_CLAIM = "https://api.openai.com/auth";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private PlaceholderSignIn() {
    }

    /** The whole {@code auth.json}, the same on every call. */
    public static String file() {
        ObjectNode file = JSON.createObjectNode();
        file.put("auth_mode", "chatgpt");
        file.putNull("OPENAI_API_KEY");
        ObjectNode tokens = file.putObject("tokens");
        tokens.put("id_token", jwt());
        tokens.put("access_token", jwt());
        tokens.put("refresh_token", "");
        tokens.put("account_id", ACCOUNT_ID);
        file.put("last_refresh", LAST_REFRESH);
        return write(file);
    }

    /**
     * Header {@code alg none}, the claims, and a third part that is not a signature: Codex splits a token in three and
     * refuses one whose third part is empty (read from its token parser, not measured on its own), and nothing on the
     * agent's side verifies it.
     */
    private static String jwt() {
        ObjectNode header = JSON.createObjectNode();
        header.put("alg", "none");
        header.put("typ", "JWT");
        ObjectNode payload = JSON.createObjectNode();
        payload.put("exp", EXPIRES_EPOCH_SECONDS);
        payload.put("spire_placeholder", true);
        ObjectNode auth = payload.putObject(AUTH_CLAIM);
        auth.put("chatgpt_account_id", ACCOUNT_ID);
        auth.put("chatgpt_plan_type", "plus");
        auth.put("chatgpt_user_id", "user-TEST-PLACEHOLDER");
        return encode(write(header)) + "." + encode(write(payload)) + "." + encode(CodexModelProxy.PLACEHOLDER);
    }

    private static String encode(String text) {
        return URL.encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String write(ObjectNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("a placeholder sign-in could not be written", impossible);
        }
    }
}
