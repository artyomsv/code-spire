package dev.codespire.publisher;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Teaches this JVM the deployment's corporate trust store and proxy (FR-F14).
 *
 * <p><b>Without this class the corporate environment reaches one container out of three.</b> The
 * worker injects {@code SSL_CERT_FILE}, {@code GIT_SSL_CAINFO} and {@code NODE_EXTRA_CA_CERTS} into
 * every container of a run unit, and those three names cover OpenSSL, the git binary and Node — the
 * agent's world. The init clone and the publisher are neither: they are this JVM running JGit, which
 * reads the JDK's own trust store and {@code ProxySelector}, and knows none of those names.
 * Measured rather than assumed: the JGit jar contains zero references to any of them.
 *
 * <p>So behind a TLS-inspecting proxy the clone failed at the forge and the push failed at the
 * forge, while three documents said the opposite. A review found it; the mount test could not,
 * because reading the file proves the bind and says nothing about trust.
 *
 * <p><b>The bundle REPLACES the trust store rather than adding to it, and that is the documented
 * contract</b> — {@code .env.example} and {@code CORPORATE-ENVIRONMENT.md} both require a complete
 * bundle, the corporate root appended to the public roots. Merging the JDK defaults in here would
 * silently make a corporate-only bundle work in this container and fail in the agent, which reads
 * the same file through OpenSSL semantics. One contract, honoured identically everywhere.
 *
 * <p>Configured from the environment and nowhere else. This process is handed a run's credentials
 * and nothing about the deployment; the same variables the runtime already sets are the whole
 * input, so a container started by hand behaves the way one started by the worker does.
 */
final class CorporateTransport {

    /** OpenSSL's name, which the worker sets from {@code spire.run.ca-bundle-path}. */
    static final String CA_BUNDLE = "SSL_CERT_FILE";

    private CorporateTransport() {
    }

    /**
     * Applies whatever the deployment configured, and does nothing when it configured none.
     *
     * <p>Never throws for an absent variable — the ordinary deployment has no corporate anything,
     * and this must be a no-op there. A variable that IS set and cannot be honoured throws, because
     * the alternative is a clone that connects to the forge without the trust the operator asked
     * for, which is the failure mode this whole feature exists to remove.
     */
    static void apply(Map<String, String> env) {
        trustBundleIn(env).ifPresent(CorporateTransport::trustOnly);
        applyProxy(env);
    }

    /** The configured bundle, if the deployment set one and it is readable. */
    private static java.util.Optional<Path> trustBundleIn(Map<String, String> env) {
        String path = env.get(CA_BUNDLE);
        if (path == null || path.isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(Path.of(path));
    }

    /**
     * Makes the certificates in this PEM the only ones this JVM trusts.
     *
     * <p>{@code generateCertificates} — plural — because a corporate bundle is many concatenated
     * PEM blocks and the singular form reads only the first. That is the shape of bug that would
     * leave an operator with a bundle that works for the forge and fails for the model API, which
     * is exactly the symptom this feature is meant to end rather than reproduce.
     */
    static void trustOnly(Path bundle) {
        try {
            byte[] pem = Files.readAllBytes(bundle);
            Collection<? extends Certificate> certificates;
            try (InputStream in = new ByteArrayInputStream(pem)) {
                certificates = CertificateFactory.getInstance("X.509").generateCertificates(in);
            }
            if (certificates.isEmpty()) {
                throw new IllegalStateException(CA_BUNDLE + " is set to " + bundle
                        + ", which holds no X.509 certificate. A JVM cannot trust an empty bundle,"
                        + " and every call this container makes would fail at the handshake.");
            }
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            int index = 0;
            for (Certificate certificate : certificates) {
                store.setCertificateEntry("spire-ca-" + index++, certificate);
            }
            TrustManagerFactory trust =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trust.getTrustManagers(), null);
            // The default, not a per-connection one: JGit builds its own connections and hands us
            // no seam to pass a context through. Setting the default is what reaches them.
            SSLContext.setDefault(context);
            HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
        } catch (IOException | java.security.GeneralSecurityException e) {
            throw new IllegalStateException("could not build a trust store from " + CA_BUNDLE
                    + "=" + bundle + ": " + e.getMessage(), e);
        }
    }

    /**
     * Routes this JVM's HTTP through the deployment's proxy.
     *
     * <p>A {@link ProxySelector} rather than the {@code https.proxyHost} system properties, because
     * the no-proxy list has no system-property form that covers both schemes the way one selector
     * does, and because an internal forge reachable only directly is the case that makes a proxy
     * usable at all.
     */
    private static void applyProxy(Map<String, String> env) {
        Proxy httpProxy = proxyFrom(value(env, "HTTP_PROXY", "http_proxy"));
        Proxy httpsProxy = proxyFrom(value(env, "HTTPS_PROXY", "https_proxy"));
        if (httpProxy == null && httpsProxy == null) {
            return;
        }
        List<String> noProxy = noProxyList(value(env, "NO_PROXY", "no_proxy"));
        ProxySelector direct = ProxySelector.getDefault();
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                if (uri == null || bypasses(noProxy, uri.getHost())) {
                    return List.of(Proxy.NO_PROXY);
                }
                Proxy chosen = "https".equalsIgnoreCase(uri.getScheme()) ? httpsProxy : httpProxy;
                return chosen == null ? List.of(Proxy.NO_PROXY) : List.of(chosen);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException failure) {
                direct.connectFailed(uri, address, failure);
            }
        });
        applyProxyAuthentication(env);
    }

    /**
     * Answers the proxy's own credential challenge, when the URL carries one.
     *
     * <p>{@code jdk.http.auth.tunneling.disabledSchemes} is cleared deliberately: the JDK refuses
     * Basic to a proxy on a CONNECT tunnel by default, which is precisely the shape of every
     * https-through-a-corporate-proxy call a run makes. Left at its default, an authenticating
     * proxy answers 407 and the failure names the proxy rather than the setting.
     */
    private static void applyProxyAuthentication(Map<String, String> env) {
        String url = value(env, "HTTPS_PROXY", "https_proxy");
        if (url == null) {
            url = value(env, "HTTP_PROXY", "http_proxy");
        }
        String[] credential = userInfoIn(url);
        if (credential == null) {
            return;
        }
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        System.setProperty("jdk.http.auth.proxying.disabledSchemes", "");
        Authenticator.setDefault(new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                // PROXY only. Answering a SERVER challenge with the proxy's credential would send
                // it to the forge, which is a credential leak dressed as a convenience.
                if (getRequestorType() != RequestorType.PROXY) {
                    return null;
                }
                return new PasswordAuthentication(credential[0], credential[1].toCharArray());
            }
        });
    }

    /** {@code scheme://host:port} to a {@link Proxy}, or null when unset or unparseable. */
    private static Proxy proxyFrom(String url) {
        if (url == null) {
            return null;
        }
        String authority = authorityOf(url);
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        int colon = authority.lastIndexOf(':');
        if (colon < 0 || colon == authority.length() - 1) {
            return null;
        }
        try {
            int port = Integer.parseInt(authority.substring(colon + 1));
            return new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress(authority.substring(0, colon), port));
        } catch (NumberFormatException notAPort) {
            return null;
        }
    }

    /** The username and password from a proxy URL's userinfo, or null when it carries none. */
    static String[] userInfoIn(String url) {
        if (url == null) {
            return null;
        }
        String authority = authorityOf(url);
        int at = authority.lastIndexOf('@');
        if (at < 0) {
            return null;
        }
        String userInfo = authority.substring(0, at);
        int colon = userInfo.indexOf(':');
        if (colon < 0 || colon == userInfo.length() - 1) {
            return null;
        }
        return new String[] {
                decode(userInfo.substring(0, colon)),
                decode(userInfo.substring(colon + 1)),
        };
    }

    /**
     * The authority of a proxy URL: after the scheme, before the path.
     *
     * <p>The scheme is optional because {@code user:pass@proxy:3128} is a form curl accepts and
     * operators write. Bounded at the first slash so an {@code @} in a path cannot be mistaken for
     * the end of a userinfo that is not there.
     */
    private static String authorityOf(String url) {
        int scheme = url.indexOf("://");
        String rest = scheme < 0 ? url : url.substring(scheme + 3);
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    /** Percent-decoding, because a proxy password routinely carries characters a URL escapes. */
    private static String decode(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String value(Map<String, String> env, String upper, String lower) {
        String found = env.get(upper);
        if (found == null || found.isBlank()) {
            found = env.get(lower);
        }
        return found == null || found.isBlank() ? null : found;
    }

    private static List<String> noProxyList(String raw) {
        List<String> entries = new ArrayList<>();
        if (raw != null) {
            for (String entry : raw.split(",")) {
                String trimmed = entry.trim().toLowerCase(Locale.ROOT);
                if (!trimmed.isEmpty()) {
                    entries.add(trimmed.startsWith(".") ? trimmed.substring(1) : trimmed);
                }
            }
        }
        return List.copyOf(entries);
    }

    /**
     * Whether this host is in the no-proxy list.
     *
     * <p>Suffix matching, so {@code acme.example} covers {@code forge.acme.example} — the form an
     * operator writes and the form curl implements. Anchored on a dot boundary so
     * {@code notacme.example} does not match {@code acme.example}, which would silently send an
     * unrelated host direct.
     */
    private static boolean bypasses(List<String> noProxy, String host) {
        if (host == null) {
            return false;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        for (String entry : noProxy) {
            if (entry.equals("*") || lower.equals(entry) || lower.endsWith("." + entry)) {
                return true;
            }
        }
        return false;
    }
}
