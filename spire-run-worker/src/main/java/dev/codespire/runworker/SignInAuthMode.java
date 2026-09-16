package dev.codespire.runworker;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The two things this build reads out of a sign-in file, and nothing else (M3.5 part F).
 *
 * <p>Everything inside that file is the vendor's. Only {@code auth_mode} has been measured
 * ({@code @openai/codex@0.146.0}, 2026-09-16), so only {@code auth_mode} is read. The rest is sealed
 * and passed on untouched, because a worker that picked out fields nobody has seen would be building
 * on a guess — and the file holds a credential, which is the worst place for one.
 *
 * <p>Read with a regular expression rather than a JSON parser on purpose: this runs in the worker,
 * where the value must not be turned into an object graph that a logger, a debugger or an error
 * message could print. The one field needed is a short string at the top level.
 */
final class SignInAuthMode {

    /** {@code "auth_mode":"apikey"} — the whole of what has been measured. */
    private static final Pattern AUTH_MODE = Pattern.compile("\"auth_mode\"\\s*:\\s*\"([a-zA-Z0-9_-]{1,32})\"");

    /** The mode an API-key sign-in reports. A subscription reports something else. */
    static final String API_KEY_MODE = "apikey";

    private SignInAuthMode() {
    }

    /** @return the declared mode, or null when the file does not say */
    static String of(String body) {
        Matcher match = AUTH_MODE.matcher(body);
        return match.find() ? match.group(1) : null;
    }

    /**
     * A short, safe label for a screen.
     *
     * <p>Deliberately NOT read out of the file. Whatever identity the vendor stores has not been
     * measured, and the obvious candidate — an account's e-mail address — is the one value this project
     * never persists or logs.
     *
     * <p>So the label is the MODE, and {@link #AUTH_MODE} is what makes that safe rather than merely
     * intended: it admits letters, digits, underscore and hyphen, and nothing else. An address needs an
     * {@code @} and a dot, so a file whose mode is address-shaped yields no mode at all and this
     * answers {@code unknown}. Tightening that pattern is therefore a security change, not a tidy-up.
     */
    static String identity(String body) {
        String mode = of(body);
        return mode == null ? "unknown" : mode;
    }
}
