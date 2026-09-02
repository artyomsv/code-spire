package dev.codespire.runworker;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Removes a run's credentials from text that is about to be stored or logged.
 *
 * <p>The publisher already scrubs the git secret from every failure line it writes, because a
 * transport exception quotes the URL it tried. The worker's own {@code RunFailed} details had no
 * such scrub: they carry exception text from the runtime and the launcher, and
 * {@code factory_run.failure_detail} is read by an operator.
 *
 * <p><b>Three forms, not one.</b> The literal alone is narrower than it looks. A credential travels
 * percent-encoded inside a URL, and Basic authentication sends {@code user:secret} base64-encoded,
 * so an exception quoting a request header leaks a secret that a literal match never sees. The
 * publisher learned this in review; the same three forms apply here.
 *
 * <p><b>What it cannot do, stated rather than implied.</b> It removes secrets it was given. A detail
 * quoting a credential this run does not hold — another run's, or one read from the environment —
 * passes through untouched. That is why the injected credentials are still kept out of exception
 * messages at the source, and this is the second line rather than the first.
 */
final class SecretScrub {

    static final String REDACTED = "[redacted]";

    /** Below this a "secret" is more likely to be a common substring than a credential. */
    private static final int MIN_SECRET_LENGTH = 8;

    private final List<String> forms;

    private SecretScrub(List<String> forms) {
        this.forms = forms;
    }

    /** A scrub that removes nothing, for the paths where no credential could be decrypted. */
    static SecretScrub none() {
        return new SecretScrub(List.of());
    }

    /**
     * Every form the given secrets can appear in, longest first.
     *
     * <p>Longest first matters: one secret can contain another as a substring, and redacting the
     * shorter one first leaves the tail of the longer in place.
     */
    static SecretScrub of(String username, String... secrets) {
        List<String> forms = new ArrayList<>();
        for (String secret : secrets) {
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
    String clean(String text) {
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
