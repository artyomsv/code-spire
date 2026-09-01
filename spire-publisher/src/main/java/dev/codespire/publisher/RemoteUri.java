package dev.codespire.publisher;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The one rule both entrypoints apply to {@code SPIRE_REMOTE_URI}: an http(s) URL with NO userinfo,
 * and plain {@code http} only inside the machine's own trust zone.
 *
 * <p>A credential embedded in the URL reaches every place the URL is printed — a JGit transport
 * exception's message, and from there the outcome line on stdout that the worker records as the
 * run's failure detail. The credential has its own two variables. Every refusal names the variable
 * and never its value, and {@link URISyntaxException} is not chained because its message quotes
 * the input.
 *
 * <p>Plain http would send the machine account's token in the clear, so it is accepted only for a
 * loopback name or a literal private, link-local or loopback address — a container on the daemon's
 * own bridge, a developer's local forge. Every hosted forge is https, and a self-managed one
 * reached by name must be too.
 */
final class RemoteUri {

    private static final String HTTPS = "https";

    private static final String HTTP = "http";

    private static final Set<String> LOOPBACK_NAMES = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    /** RFC 1918, link-local (169.254/16) and the CGNAT range (100.64/10), as literal IPv4. */
    private static final Pattern PRIVATE_IPV4 = Pattern.compile(
            "10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"
                    + "|172\\.(?:1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}"
                    + "|192\\.168\\.\\d{1,3}\\.\\d{1,3}"
                    + "|169\\.254\\.\\d{1,3}\\.\\d{1,3}"
                    + "|100\\.(?:6[4-9]|[7-9]\\d|1[01]\\d|12[0-7])\\.\\d{1,3}\\.\\d{1,3}");

    /** Unique-local (fc00::/7) and link-local (fe80::/10) IPv6, bracketed as a URL host. */
    private static final Pattern PRIVATE_IPV6 = Pattern.compile("(?i)\\[f[cd][0-9a-f]{2}:.*]|\\[fe[89ab][0-9a-f]:.*]");

    private RemoteUri() {
    }

    static String validated(String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("SPIRE_REMOTE_URI is not a valid URI");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals(HTTPS) && !scheme.equals(HTTP)) {
            throw new IllegalStateException("SPIRE_REMOTE_URI must be an http(s) URL");
        }
        boolean authorityHasUserinfo = uri.getRawAuthority() != null && uri.getRawAuthority().contains("@");
        if (uri.getRawUserInfo() != null || authorityHasUserinfo) {
            throw new IllegalStateException("SPIRE_REMOTE_URI carries a credential in its userinfo; "
                    + "the credential goes in its own variables, never in the URL");
        }
        if (scheme.equals(HTTP) && !isTrustZone(uri.getHost())) {
            throw new IllegalStateException("SPIRE_REMOTE_URI uses plain http to a host outside the "
                    + "local trust zone; that would send the machine account's token in the clear. "
                    + "Use https, or a loopback or private address.");
        }
        return value;
    }

    private static boolean isTrustZone(String host) {
        if (host == null) {
            return false;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        return LOOPBACK_NAMES.contains(lower)
                || PRIVATE_IPV4.matcher(lower).matches()
                || PRIVATE_IPV6.matcher(lower).matches();
    }
}
