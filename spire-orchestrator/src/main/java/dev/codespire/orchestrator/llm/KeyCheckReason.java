package dev.codespire.orchestrator.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;

/**
 * Why a provider refused a key check, in words an operator can act on.
 *
 * <p>The provider's own {@code error.message} is the useful part: Anthropic refuses a user key that is
 * not scoped to a workspace with a 400 that says exactly that, and without it the dialog showed only
 * "(400)". OpenAI, Anthropic and Gemini all answer {@code {"error": {"message": ...}}}.
 *
 * <p>The message comes from outside, so it is cleaned before it is shown or stored: the submitted key
 * and anything key-shaped are removed (OpenAI echoes part of a wrong key), control characters are
 * dropped and the length is capped.
 */
final class KeyCheckReason {

    /** The response body as text; a key check answer is small (a models list or an error). */
    static final HttpResponse.BodyHandler<String> BODY = HttpResponse.BodyHandlers.ofString();

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_MESSAGE = 300;
    private static final String REDACTED = "[redacted]";
    /** Vendor key shapes: OpenAI and Anthropic {@code sk-…}, Gemini {@code AIza…}. */
    private static final Pattern KEY_SHAPED = Pattern.compile("(sk-[A-Za-z0-9_*\\-]{6,}|AIza[A-Za-z0-9_\\-]{10,})");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\s]+");

    private KeyCheckReason() {
    }

    /**
     * @param status the provider's non-2xx status
     * @param body   the provider's response body, possibly empty or not JSON
     * @param apiKey the key that was checked, removed from the message wherever it appears
     */
    static String of(int status, String body, String apiKey) {
        String headline = status == 401 || status == 403
                ? "The LLM provider rejected the API key (HTTP " + status + ")"
                : "The LLM provider refused the key check (HTTP " + status + ")";
        String message = clean(providerMessage(body), apiKey);
        return message.isEmpty() ? headline + "." : headline + ": " + message;
    }

    private static String providerMessage(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode message = JSON.readTree(body).path("error").path("message");
            return message.isTextual() ? message.asText() : "";
        } catch (IOException notJson) {
            return "";
        }
    }

    private static String clean(String message, String apiKey) {
        String cleaned = message;
        if (apiKey != null && !apiKey.isBlank()) {
            cleaned = cleaned.replace(apiKey, REDACTED);
        }
        cleaned = KEY_SHAPED.matcher(cleaned).replaceAll(REDACTED);
        cleaned = CONTROL.matcher(cleaned).replaceAll(" ").strip();
        return cleaned.length() > MAX_MESSAGE ? cleaned.substring(0, MAX_MESSAGE) + "…" : cleaned;
    }
}
