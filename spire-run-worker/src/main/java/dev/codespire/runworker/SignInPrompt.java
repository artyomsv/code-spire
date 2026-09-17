package dev.codespire.runworker;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the operator must do, read out of the vendor CLI's own output (M3.5 part F).
 *
 * <p><b>This parses prose, and an operator is then told to type their account credentials at whatever
 * comes back.</b> There is no machine-readable device-authorization output to read: the CLI prints a
 * paragraph for a person. So the rule is not "find something link-shaped" — the first version did that,
 * and review showed three ways to fool it:
 *
 * <ul>
 *   <li>a decoy printed first ("For help, visit:" and a support link) was adopted and paired with the
 *       genuine code;</li>
 *   <li>{@code https://auth.openai.com@attacker.example/device} passed, although its actual authority
 *       is {@code attacker.example} — the part before the {@code @} is userinfo, not a host;</li>
 *   <li>a standalone banner token such as {@code BETA-2026} passed as the one-time code.</li>
 * </ul>
 *
 * <p>So this checks the ADDRESS, not the shape. The arm declares the one host its device page lives on
 * ({@code HarnessAdapter.SignInFlow}); a link is accepted only when it parses, carries no userinfo, and
 * its host is exactly that. The code is accepted only AFTER that link, which is the order the flow
 * prints them in and which puts every banner token before the first thing worth reading. Two different
 * links, or two different codes, are AMBIGUOUS and are refused rather than resolved by a rule nobody
 * can check.
 *
 * <p>When the vendor changes the paragraph this finds nothing, and the sign-in fails visibly with
 * {@code sign_in_unit_failed}. That is the design's whole safety argument: it never invents a
 * destination, and the alternative — reimplementing their OAuth against endpoints they do not publish —
 * fails silently instead.
 *
 * <p>Measured against {@code @openai/codex@0.146.0} on 2026-09-16.
 */
public final class SignInPrompt {

    /** A line that is nothing but a link. Anchored: a link inside a sentence is prose about the flow. */
    private static final Pattern LINK = Pattern.compile("^(https://\\S+)$");

    /** The one-time code, alone on its line. Shape only — the ORDER below is what makes it the code. */
    private static final Pattern CODE = Pattern.compile("^([A-Z0-9]{4,8}-[A-Z0-9]{4,8})$");

    /** "(expires in 15 minutes)". Optional: a missing expiry falls back to the caller's own ceiling. */
    private static final Pattern EXPIRES = Pattern.compile("expires in (\\d{1,3}) minutes?");

    private final String verificationHost;

    private String link;
    private String code;
    private Duration expiresIn;
    private boolean ambiguous;

    /**
     * @param verificationHost the bare host the arm's device page lives on. Everything else is refused.
     */
    public SignInPrompt(String verificationHost) {
        this.verificationHost = verificationHost.toLowerCase(Locale.ROOT);
    }

    /**
     * Offers one line of output.
     *
     * @return true when there is a link and a code to show
     */
    public boolean accept(String line) {
        String trimmed = line.strip();
        readLink(trimmed);
        readCode(trimmed);
        Matcher expiry = EXPIRES.matcher(trimmed);
        if (expiresIn == null && expiry.find()) expiresIn = Duration.ofMinutes(Long.parseLong(expiry.group(1)));
        return complete();
    }

    private void readLink(String trimmed) {
        Matcher match = LINK.matcher(trimmed);
        if (!match.matches()) return;
        String candidate = match.group(1);
        if (!addressesTheArmsOwnPage(candidate)) return;
        // A second, DIFFERENT address for the same flow means the output is not what this build can
        // read. Choosing one would be choosing where to send somebody's account credentials.
        if (link != null && !link.equals(candidate)) ambiguous = true;
        else link = candidate;
    }

    private void readCode(String trimmed) {
        // Only after the link. Everything the CLI prints before it — banners, version strings, a plan
        // name — is out of range, which is what stops a code-shaped word being read as the code.
        if (link == null) return;
        Matcher match = CODE.matcher(trimmed);
        if (!match.matches()) return;
        String candidate = match.group(1);
        if (code != null && !code.equals(candidate)) ambiguous = true;
        else code = candidate;
    }

    /**
     * Whether this address is the arm's device page, judged by its parsed AUTHORITY.
     *
     * <p>Userinfo is refused outright rather than only compared past: it has no place in a page an
     * operator is sent to, and its whole trick is reading like a host to a person.
     */
    private boolean addressesTheArmsOwnPage(String candidate) {
        try {
            URI uri = new URI(candidate);
            return uri.getUserInfo() == null
                    && "https".equalsIgnoreCase(uri.getScheme())
                    // The port too, not only the host. An exact host on another port is another
                    // service: -1 is "none stated" and 443 is the same thing spelled out, and nothing
                    // else is the page this arm declared.
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getHost() != null
                    && uri.getHost().toLowerCase(Locale.ROOT).equals(verificationHost);
        } catch (URISyntaxException notAnAddress) {
            return false;
        }
    }

    /** Two candidates for one slot. The sign-in is refused rather than resolved by a coin toss. */
    public boolean ambiguous() {
        return ambiguous;
    }

    public boolean complete() {
        return link != null && code != null && !ambiguous;
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
