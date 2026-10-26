package dev.codespire.runworker;

import java.util.regex.Pattern;

/**
 * Strips the control characters from text an agent handed over, before the run's credentials are scrubbed from it.
 *
 * <p>The order is the point: the scrub matches a credential literally, so a key broken up by a BEL or a terminal
 * sequence passes it, and a strip that ran afterwards would join the key back together.
 */
final class ControlCharacters {

    /** A terminal control sequence: ESC [ parameters intermediates final. Stripped whole, not just its ESC. */
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]");

    /** Every C0 control but newline and tab, DEL, and the C1 range. */
    private static final Pattern CONTROLS = Pattern.compile("[\\x00-\\x08\\x0B-\\x1F\\x7F-\\x9F]");

    private ControlCharacters() {
    }

    static String strip(String text) {
        return CONTROLS.matcher(ANSI.matcher(text).replaceAll("")).replaceAll("");
    }
}
