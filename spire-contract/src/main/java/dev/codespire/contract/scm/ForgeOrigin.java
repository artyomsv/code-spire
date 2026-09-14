package dev.codespire.contract.scm;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Host-qualified identity; API path suffixes and default ports do not create a second origin. */
public final class ForgeOrigin {
    private ForgeOrigin() { }

    public static String of(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Forge origin is required");
        URI uri = URI.create(value.trim());
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ((!scheme.equals("https") && !scheme.equals("http")) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Forge origin must be an HTTP(S) URL without credentials, query or fragment");
        }
        int port = uri.getPort();
        if ((scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80)) port = -1;
        try {
            return new URI(scheme, null, uri.getHost().toLowerCase(Locale.ROOT), port, null, null, null).toString();
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException("Invalid forge origin", invalid);
        }
    }
}
