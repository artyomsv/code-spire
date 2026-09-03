package dev.codespire.workspace;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Removes a run's credentials from text that is about to be stored or logged.
 *
 * <p><b>One home, because two were not equivalent.</b> The worker and the publisher each carried
 * a hand-rolled scrubber, and this class's own javadoc used to assert they agreed — "the publisher
 * already scrubs the git secret … the same three forms apply here". They did not: the publisher's
 * had no length floor, redacted in no particular order, and handled exactly one credential. The
 * weaker of the two ran in the container holding the git WRITE token. A fix applied to one would
 * not have reached the other, which is the shape that produced three separate defects in one
 * milestone. {@code RedirectHandlingHasOneHomeTest} already refuses this shape for a redirect loop,
 * and a credential scrubber is the stronger case.
 *
 * <p>It lives here rather than in either service because the two share no other module, and
 * because this one already owns {@link GitCredential}. Apache-licensed, which both FSL services
 * may depend on (ADR-021); the reverse would not be allowed.
 *
 * <p>Two surfaces it is asked to protect: the worker's {@code RunFailed} details, which carry
 * exception text from the runtime and the launcher into {@code factory_run.failure_detail} where an
 * operator reads it, and every failure line the publisher writes, because a transport exception
 * quotes the URL it tried.
 *
 * <p><b>Three forms, not one.</b> The literal alone is narrower than it looks. A credential travels
 * percent-encoded inside a URL, and Basic authentication sends {@code user:secret} base64-encoded,
 * so an exception quoting a request header leaks a secret that a literal match never sees.
 *
 * <p><b>What it cannot do, stated rather than implied.</b> It removes secrets it was given. A detail
 * quoting a credential this run does not hold — another run's, or one read from the environment —
 * passes through untouched. That is why the injected credentials are still kept out of exception
 * messages at the source, and this is the second line rather than the first.
 */
public final class SecretScrub {

    public static final String REDACTED = "[redacted]";

    /**
     * Below this a "secret" is more likely to be a common substring than a credential.
     *
     * <p><b>A secret shorter than this is NOT scrubbed</b>, and that is a decision rather than an
     * oversight: redacting a three-character string turns every innocent occurrence of it into
     * {@code [redacted]} and leaves an operator a failure detail they cannot read. The publisher's
     * own copy had no floor, so adopting this one is the single behaviour it loses — no forge issues
     * a token this short, but the gap is stated here rather than discovered later.
     * {@code EnterpriseEnvironmentConfig} refuses a proxy password below this length at startup for
     * exactly that reason, which is the pattern to copy where a caller can refuse.
     */
    public static final int MIN_SECRET_LENGTH = 8;

    /**
     * One secret and the username it authenticates with.
     *
     * <p>The pair exists because the base64 form is {@code base64(user + ":" + secret)}, so a
     * secret paired with the WRONG username produces a string that appears on no wire. That was
     * live: the deployment proxy password was handed to {@link #of(String, String...)} alongside
     * the SCM username, so the {@code Proxy-Authorization: Basic} header a verbose curl prints
     * matched nothing, while the javadoc above named that header as one of the three forms.
     */
    public record Credential(String username, String secret) {
    }

    private final List<String> forms;

    private SecretScrub(List<String> forms) {
        this.forms = forms;
    }

    /** A scrub that removes nothing, for the paths where no credential could be decrypted. */
    public static SecretScrub none() {
        return new SecretScrub(List.of());
    }

    /**
     * Every form the given secrets can appear in, longest first.
     *
     * <p>Longest first matters: one secret can contain another as a substring, and redacting the
     * shorter one first leaves the tail of the longer in place.
     */
    public static SecretScrub of(String username, String... secrets) {
        List<Credential> credentials = new ArrayList<>();
        for (String secret : secrets) {
            credentials.add(new Credential(username, secret));
        }
        return of(credentials);
    }

    /** Every form of every secret, each paired with the username it is actually sent with. */
    public static SecretScrub of(List<Credential> credentials) {
        List<String> forms = new ArrayList<>();
        for (Credential credential : credentials) {
            String username = credential.username();
            String secret = credential.secret();
            if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
                continue;
            }
            forms.add(secret);
            String encoded = URLEncoder.encode(secret, StandardCharsets.UTF_8);
            if (!encoded.equals(secret)) {
                forms.add(encoded);
            }
            if (username != null && !username.isBlank()) {
                forms.add(Base64.getEncoder().encodeToString(
                        (username + ":" + secret).getBytes(StandardCharsets.UTF_8)));
            }
        }
        forms.sort((left, right) -> Integer.compare(right.length(), left.length()));
        return new SecretScrub(List.copyOf(forms));
    }

    /** The text with every known form of every known secret replaced. */
    public String clean(String text) {
        if (text == null || forms.isEmpty()) {
            return text;
        }
        String scrubbed = text;
        for (String form : forms) {
            scrubbed = scrubbed.replace(form, REDACTED);
        }
        return scrubbed;
    }
}
