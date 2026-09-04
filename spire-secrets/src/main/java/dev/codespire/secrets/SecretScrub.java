package dev.codespire.secrets;

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
     * Shorter than this and a "secret" is more likely a common substring than a credential — so
     * scrubbing it will make a failure detail hard to read. <b>It is scrubbed anyway.</b>
     *
     * <p><b>This constant used to gate the scrub, and that was the wrong trade.</b> Skipping a
     * short secret spends a security property to buy a readability one, at the single instant when
     * the value IS a live credential and the cost of leaking it is unbounded. It was not
     * hypothetical: Gitea and Forgejo accept an ACCOUNT PASSWORD for git-over-HTTP with a default
     * minimum of six characters, nothing validates the length of a factory token an operator
     * pastes into the registry, and such a password reached {@code factory_run.failure_detail}
     * verbatim — a column an operator reads.
     *
     * <p>So the readability concern is answered where a human can act on it instead: a secret below
     * this length is scrubbed, and every scrub built from it logs a warning naming the consequence.
     * A warning an operator can read beats a silence they cannot.
     *
     * <p><b>Every scrub, not once.</b> An earlier wording said "logged once" and that was never
     * true — a scrub is built per run launch and again on every failure, so a deployment with a
     * short credential sees this repeatedly. Said plainly rather than corrected by deduplicating,
     * because a reader who believes it is deduplicated will build on that.
     *
     * <p>Deliberately NOT a refusal. Refusing to run because a forge issued a six-character
     * password would be a new product rule about what an operator may configure, invented under
     * cover of a logging fix — and it would block a legitimate Gitea deployment.
     */
    public static final int MIN_PLAUSIBLE_SECRET_LENGTH = 8;

    /**
     * {@code System.Logger}, because this module depends on the JDK and nothing else.
     *
     * <p>That is the whole reason it exists as a module: it is consumed by an FSL service and an
     * Apache library, and pulling a logging framework in here would put it on both classpaths.
     */
    private static final System.Logger LOG = System.getLogger(SecretScrub.class.getName());

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
     * Every form the given secrets can appear in, longest first, all sharing one username.
     *
     * <p>Longest first matters: one secret can contain another as a substring, and redacting the
     * shorter one first leaves the tail of the longer in place.
     *
     * <p><b>Package-private, and deliberately not public.</b> Production builds a
     * {@link Credential} list at both call sites, and this shape is the one whose misuse
     * {@link Credential} documents as having happened: a proxy password handed here alongside the
     * SCM username produced a base64 form that appears on no wire, so the header it was meant to
     * cover matched nothing. Publishing it from a shared module would re-offer that mistake to
     * every future caller; {@code of(List.of(new Credential(user, secret)))} is two tokens more
     * and cannot pair the wrong two values silently.
     */
    static SecretScrub of(String username, String... secrets) {
        List<Credential> credentials = new ArrayList<>();
        for (String secret : secrets) {
            credentials.add(new Credential(username, secret));
        }
        return of(credentials);
    }

    /**
     * Every form of every secret, each paired with the username it is actually sent with.
     *
     * <p>A blank or null secret contributes nothing — it has no form, and redacting the empty
     * string would rewrite every character of every message. A SHORT one contributes its forms
     * like any other, with a warning: see {@link #MIN_PLAUSIBLE_SECRET_LENGTH}.
     */
    public static SecretScrub of(List<Credential> credentials) {
        List<String> forms = new ArrayList<>();
        for (Credential credential : credentials) {
            String username = credential.username();
            String secret = credential.secret();
            if (secret == null || secret.isBlank()) {
                continue;
            }
            if (secret.length() < MIN_PLAUSIBLE_SECRET_LENGTH) {
                // The LENGTH, never the value, and never the username either -- this line is
                // written to a log that exists because credentials must not reach logs.
                LOG.log(System.Logger.Level.WARNING,
                        "a configured secret is only {0} characters, so redacting it will also hide"
                                + " innocent text and make failure details hard to read. It IS"
                                + " redacted; use a longer credential to get readable messages back.",
                        secret.length());
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
