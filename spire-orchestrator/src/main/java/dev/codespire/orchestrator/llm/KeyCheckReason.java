package dev.codespire.orchestrator.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Locale;

/**
 * Why a key check failed, as one of a fixed set of reasons in the application's own words.
 *
 * <p>Provider text is never returned, stored or logged. It can echo part of the key or name an
 * account, and this reason reaches the save dialog, the Check button, a warning log line and the
 * provider's stored last check error. One provider answer is RECOGNISED, never repeated: Anthropic
 * refuses a user key that is not scoped to a workspace with a 400 in its own error shape, and that
 * maps to {@link #WORKSPACE_REQUIRED}. Every other answer maps by status alone, and its body is not
 * read at all ({@link #needsBody}).
 */
enum KeyCheckReason {

    REJECTED("The LLM provider rejected the API key (HTTP %d). Check that the key is correct and active."),
    WORKSPACE_REQUIRED("The LLM provider refused the key check (HTTP %d): this Anthropic key is not scoped to a"
            + " workspace. Create the API key inside a workspace in the Anthropic Console."),
    RATE_LIMITED("The LLM provider is rate limiting this key (HTTP %d). Try again later."),
    PROVIDER_FAILING("The LLM provider is failing (HTTP %d). Try again later."),
    REFUSED("The LLM provider refused the key check (HTTP %d). Check the base URL and the key."),
    TIMEOUT("The LLM provider did not answer in time. Try again later."),
    UNREACHABLE("Could not reach the LLM provider. Check the base URL and the network.");

    /** How much of an error body is read to recognise it. The rest is never read. */
    static final int MAX_ERROR_BODY_BYTES = 16 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ANTHROPIC = "anthropic";
    private static final String WORKSPACE_MARKER = "not scoped to a workspace";

    private final String template;

    KeyCheckReason(String template) {
        this.template = template;
    }

    /** The operator-facing sentence. {@code status} is ignored by the reasons that carry none. */
    String message(int status) {
        return template.contains("%d") ? String.format(Locale.ROOT, template, status) : template;
    }

    /** Whether this answer's body can change its reason, so whether it is worth reading at all. */
    static boolean needsBody(String type, int status) {
        return ANTHROPIC.equals(type) && status == 400;
    }

    /**
     * The reason for a non-2xx answer.
     *
     * @param body at most {@link #MAX_ERROR_BODY_BYTES} of it, and only when {@link #needsBody} said so
     */
    static KeyCheckReason forStatus(String type, int status, byte[] body) {
        if (status == 401 || status == 403) {
            return REJECTED;
        }
        if (status == 429) {
            return RATE_LIMITED;
        }
        if (status >= 500) {
            return PROVIDER_FAILING;
        }
        if (needsBody(type, status) && isAnthropicWorkspaceRefusal(body)) {
            return WORKSPACE_REQUIRED;
        }
        return REFUSED;
    }

    /** Anthropic's error shape, {@code {"type":"error","error":{"message":...}}}, saying so in its message. */
    private static boolean isAnthropicWorkspaceRefusal(byte[] body) {
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode message = root.path("error").path("message");
            return "error".equals(root.path("type").asText())
                    && message.isTextual()
                    && message.asText().toLowerCase(Locale.ROOT).contains(WORKSPACE_MARKER);
        } catch (IOException notJson) {
            return false;
        }
    }
}
