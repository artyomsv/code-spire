package dev.codespire.runworker;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;

/**
 * The one thing this build reads out of a sign-in file, and nothing else (M3.5 part F).
 *
 * <p>Everything inside that file is the vendor's. Only {@code auth_mode} has been measured
 * ({@code @openai/codex@0.146.0}, 2026-09-16), so only {@code auth_mode} is read. The rest is sealed
 * and passed on untouched, because a worker that picked out fields nobody has seen would be building
 * on a guess — and the file holds a credential, which is the worst place for one.
 *
 * <p><b>Parsed, not matched.</b> The first version used a regular expression over the whole text, and
 * its own javadoc claimed to read a top-level field. It did not:
 * {@code {"metadata":{"auth_mode":"other"},"auth_mode":"apikey"}} was read as {@code other}, so a
 * nested value decided how a credential was classified — and "is this an API key" is the question that
 * keeps a per-token key out of the subscription pool. Anything that is not valid JSON with
 * {@code auth_mode} at the top level now answers null, and the caller refuses.
 *
 * <p>Streaming rather than a tree, deliberately: the document is a credential, and a tree puts every
 * value into an object graph that a logger, a debugger or an exception message can print. This reads
 * one string and skips the rest without ever holding them.
 *
 * <p><b>Duplicate keys.</b> JSON allows the same key twice and readers disagree about which wins. Here
 * a repeated top-level {@code auth_mode} is treated as no answer at all rather than resolved by a rule
 * the vendor never promised.
 */
final class SignInAuthMode {

    private static final JsonFactory JSON = new JsonFactory();

    private static final String FIELD = "auth_mode";

    /**
     * What a mode may look like: a short token, and nothing else.
     *
     * <p>This is a SECURITY bound, not tidiness. The mode is what the screen shows as the sign-in's
     * identity, and the obvious thing a vendor might put in a field like this is an account's e-mail
     * address — the one value this project never persists or logs. No {@code @} and no dot can pass,
     * so an address cannot become a label. The previous regular-expression reader provided this by
     * accident; parsing the JSON properly removed it, so it is stated here instead.
     */
    private static final java.util.regex.Pattern MODE = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,32}");

    /** The mode an API-key sign-in reports. A subscription reports something else. */
    static final String API_KEY_MODE = "apikey";

    private SignInAuthMode() {
    }

    /**
     * @return the mode declared at the TOP level, or null when the file is not valid JSON, has no such
     *     field, declares it more than once, or declares it as something other than a string
     */
    static String of(String body) {
        try (JsonParser parser = JSON.createParser(body)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return null;
            String found = null;
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if (!FIELD.equals(field)) {
                    // An object or array under another key is skipped WHOLE, so nothing nested inside
                    // it is ever mistaken for the top-level field.
                    parser.skipChildren();
                    continue;
                }
                if (value != JsonToken.VALUE_STRING) return null;
                if (found != null) return null;
                String declared = parser.getText();
                if (!MODE.matcher(declared).matches()) return null;
                found = declared;
            }
            // The document must END at the close of that object. Without this,
            // {"auth_mode":"other"}{"auth_mode":"apikey"} answered "other" — a second root smuggled in
            // behind the first, deciding a credential's kind from a value the real file never had.
            if (parser.nextToken() != null) return null;
            return found;
        } catch (IOException | RuntimeException notReadable) {
            // Never include the body or the parser's message: both can quote the credential.
            return null;
        }
    }

    /**
     * A short, safe label for a screen.
     *
     * <p>Deliberately NOT read out of the file. Whatever identity the vendor stores has not been
     * measured, and the obvious candidate — an account's e-mail address — is the one value this project
     * never persists or logs. So the label is the mode, and {@link #of} is what makes that safe: a mode
     * is a top-level JSON string this build read itself, not a substring found somewhere in a document.
     */
    static String identity(String body) {
        String mode = of(body);
        return mode == null ? "unknown" : mode;
    }
}
