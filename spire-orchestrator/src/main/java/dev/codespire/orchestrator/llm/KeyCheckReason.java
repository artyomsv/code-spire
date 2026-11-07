package dev.codespire.orchestrator.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.secrets.SecretScrub;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Why a provider refused a key check, in words an operator can act on.
 *
 * <p>The provider's own {@code error.message} is the useful part: Anthropic refuses a user key that is
 * not scoped to a workspace with a 400 that says exactly that, and without it the dialog showed only
 * "(400)". OpenAI, Anthropic and Gemini all answer {@code {"error": {"message": ...}}}.
 *
 * <p>The message comes from outside, so it is cleaned before it is shown or stored: the submitted key
 * goes through the shared {@link SecretScrub} (its raw and URL-encoded forms), whitespace and control
 * characters collapse to one space, and the length is capped.
 */
final class KeyCheckReason {

    /** The response body as text; a key check answer is small (a models list or an error). */
    static final HttpResponse.BodyHandler<String> BODY = HttpResponse.BodyHandlers.ofString();

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_MESSAGE = 300;
    private static final Pattern WHITESPACE_OR_CONTROL = Pattern.compile("[\\p{Cntrl}\\s]+");

    private KeyCheckReason() {
    }

    /**
     * @param status the provider's non-2xx status
     * @param body   the provider's response body, possibly empty or not JSON
     * @param apiKey the key that was checked; it never appears in the result
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
        String withoutKey = SecretScrub.of(List.of(new SecretScrub.Credential(null, apiKey))).clean(message);
        String oneLine = WHITESPACE_OR_CONTROL.matcher(withoutKey).replaceAll(" ").strip();
        return oneLine.length() > MAX_MESSAGE ? oneLine.substring(0, MAX_MESSAGE) + "…" : oneLine;
    }
}
