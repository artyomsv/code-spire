package dev.codespire.orchestrator.llm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Why a key check failed, as one of a fixed set of reasons in the application's own words.
 *
 * <p>Provider text is never returned, stored or logged. It can echo part of the key or name an
 * account, and this reason reaches the save dialog, the Check button, a warning log line and the
 * provider's stored last check error. A provider answer is only RECOGNISED: Anthropic refuses a user
 * key that is not scoped to a workspace with a 400 whose body says so, and that answer maps to
 * {@link #WORKSPACE_REQUIRED}; everything else maps by status.
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

    /** How much of an error body is read to recognise it. A body past this is never read. */
    static final int MAX_ERROR_BODY_BYTES = 16 * 1024;

    private static final String WORKSPACE_MARKER = "not scoped to a workspace";

    private final String template;

    KeyCheckReason(String template) {
        this.template = template;
    }

    /** The operator-facing sentence. {@code status} is ignored by the reasons that carry none. */
    String message(int status) {
        return template.contains("%d") ? String.format(Locale.ROOT, template, status) : template;
    }

    /**
     * The reason for a non-2xx answer.
     *
     * @param errorBody the answer's body, read here up to {@link #MAX_ERROR_BODY_BYTES} and then closed
     */
    static KeyCheckReason forStatus(int status, InputStream errorBody) {
        if (status == 401 || status == 403) {
            return REJECTED;
        }
        if (status == 429) {
            return RATE_LIMITED;
        }
        if (status >= 500) {
            return PROVIDER_FAILING;
        }
        if (status == 400 && boundedText(errorBody).toLowerCase(Locale.ROOT).contains(WORKSPACE_MARKER)) {
            return WORKSPACE_REQUIRED;
        }
        return REFUSED;
    }

    private static String boundedText(InputStream body) {
        try (InputStream in = body) {
            return new String(in.readNBytes(MAX_ERROR_BODY_BYTES), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return "";
        }
    }
}
