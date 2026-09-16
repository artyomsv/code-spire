package dev.codespire.runworker;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the operator must do, read out of the vendor CLI's own output (M3.5 part F).
 *
 * <p><b>This parses prose, and that is a liability worth naming.</b> There is no machine-readable
 * device-authorization output to read: the CLI prints a paragraph for a person. So this collects lines
 * until it has the two things that matter and stops guessing at anything else. When the vendor changes
 * that paragraph, this stops finding them and the sign-in FAILS VISIBLY with
 * {@code sign_in_unit_failed} — it never invents a link, never invents a code, and never leaves an
 * operator looking at a spinner. That is the property the design depends on, because the alternative to
 * parsing is reimplementing the vendor's OAuth client against endpoints they do not publish.
 *
 * <p>Measured against {@code @openai/codex@0.146.0} on 2026-09-16. The shape then was a numbered list:
 * a line holding only an https link, and a line holding only a code of the form {@code XXXX-XXXXX},
 * preceded by a sentence saying the code expires in a number of minutes.
 */
public final class SignInPrompt {

    /**
     * A line that is nothing but a link. Anchored on purpose: a link inside a sentence is prose about
     * the flow ("continue only if..."), not the address an operator is being sent to.
     */
    private static final Pattern LINK = Pattern.compile("^(https://\\S+)$");

    /**
     * The one-time code, alone on its line.
     *
     * <p>Upper case, digits and one hyphen. Anchored for the same reason as the link, and bounded so
     * that an unrelated token in the banner cannot be mistaken for it.
     */
    private static final Pattern CODE = Pattern.compile("^([A-Z0-9]{4,8}-[A-Z0-9]{4,8})$");

    /** "(expires in 15 minutes)". Optional: a missing expiry falls back to the caller's own ceiling. */
    private static final Pattern EXPIRES = Pattern.compile("expires in (\\d{1,3}) minutes?");

    private String link;
    private String code;
    private Duration expiresIn;

    /**
     * Offers one line of output.
     *
     * @return true when both the link and the code have now been seen
     */
    public boolean accept(String line) {
        String trimmed = line.strip();
        Matcher linkMatch = LINK.matcher(trimmed);
        if (link == null && linkMatch.matches()) link = linkMatch.group(1);
        Matcher codeMatch = CODE.matcher(trimmed);
        if (code == null && codeMatch.matches()) code = codeMatch.group(1);
        Matcher expiryMatch = EXPIRES.matcher(trimmed);
        if (expiresIn == null && expiryMatch.find()) expiresIn = Duration.ofMinutes(Long.parseLong(expiryMatch.group(1)));
        return complete();
    }

    public boolean complete() {
        return link != null && code != null;
    }

    public String link() {
        return link;
    }

    public String code() {
        return code;
    }

    /** How long the vendor said the code lasts, when it said so. */
    public Optional<Duration> expiresIn() {
        return Optional.ofNullable(expiresIn);
    }
}
