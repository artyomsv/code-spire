package dev.codespire.publisher;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.Map;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves TRUST, not file presence.
 *
 * <p>The defect this class was written for shipped with a passing integration test: it mounted a
 * bundle into all three containers and read it back with {@code cat}. Reading a certificate file
 * says nothing about whether anything trusts it, and in this JVM nothing did — the init clone and
 * the publisher are JGit, which reads the JDK trust store and knows none of the three variables the
 * runtime injects.
 *
 * <p>So the assertion here is a real TLS handshake against a real server holding a certificate no
 * public root signs: it must FAIL before the bundle is applied and SUCCEED after. A test that only
 * checked the certificate loaded would have passed against the broken version too.
 *
 * <p>The key material is generated per run by {@code keytool} from the JDK under test — nothing is
 * committed, nothing is reused, and the certificate is valid for one day.
 */
class CorporateTransportTest {

    private static final String BODY = "TEST-BEHIND-THE-CORPORATE-CA";

    /**
     * A self-signed certificate and the server that serves it.
     *
     * <p>Generated with {@code keytool} because the JDK exposes no public API to create one, and a
     * committed fixture would expire, would be a private key in the repository, and would tempt
     * reuse. The CN is {@code localhost} so the hostname check passes and the only thing under test
     * is whether the issuer is trusted.
     */
    private record Endpoint(HttpsServer server, Path pem) {

        String url() {
            return "https://localhost:" + server.getAddress().getPort() + "/probe";
        }
    }

    private static Endpoint startTlsServer(Path dir) throws Exception {
        Path keystore = dir.resolve("server.p12");
        Path pem = dir.resolve("ca.crt");
        String password = "TEST-keystore-password";

        run(dir, "keytool", "-genkeypair", "-alias", "spire", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=localhost", "-validity", "1", "-storetype", "PKCS12",
                "-keystore", keystore.toString(), "-storepass", password, "-keypass", password,
                "-ext", "SAN=dns:localhost,ip:127.0.0.1");
        run(dir, "keytool", "-exportcert", "-rfc", "-alias", "spire",
                "-keystore", keystore.toString(), "-storepass", password, "-file", pem.toString());

        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            store.load(in, password.toCharArray());
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password.toCharArray());
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(keys.getKeyManagers(), null, null);

        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        server.createContext("/probe", exchange -> {
            byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return new Endpoint(server, pem);
    }

    private static void run(Path dir, String... command) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin");
        String[] resolved = command.clone();
        resolved[0] = java.resolve(command[0]).toString();
        Process process = new ProcessBuilder(resolved).directory(dir.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), command[0] + " failed: " + output);
    }

    /**
     * The whole point, in one test: the same call fails and then succeeds, and the only thing that
     * changed is that the bundle was applied.
     *
     * <p>Both halves are load-bearing. Without the first, a JVM that happened to trust the
     * certificate for some other reason would make the second pass for the wrong reason.
     */
    @Test
    void aBundleIsWhatMakesAPrivateCaTrusted(@TempDir Path dir) throws Exception {
        Endpoint endpoint = startTlsServer(dir);
        try {
            assertThrows(SSLHandshakeException.class, () -> get(endpoint.url()),
                    "a certificate no public root signs must not be trusted before the bundle");

            CorporateTransport.apply(Map.of(CorporateTransport.CA_BUNDLE, endpoint.pem().toString()));

            assertEquals(BODY, get(endpoint.url()),
                    "after the bundle the same call must succeed, which is what the clone needs");
        } finally {
            endpoint.server().stop(0);
            resetTls();
        }
    }

    /**
     * A bundle holding SEVERAL certificates trusts all of them.
     *
     * <p>The singular {@code generateCertificate} reads only the first block, which is the shape of
     * bug that leaves an operator with a bundle that works for the forge and fails for the model
     * API — the exact symptom this feature exists to end. The concatenation here puts the real
     * certificate SECOND, so the singular form would fail.
     */
    @Test
    void everyCertificateInAConcatenatedBundleIsTrusted(@TempDir Path dir) throws Exception {
        Endpoint endpoint = startTlsServer(dir);
        try {
            Path other = dir.resolve("other");
            Files.createDirectory(other);
            Endpoint unrelated = startTlsServer(other);
            unrelated.server().stop(0);

            Path combined = dir.resolve("combined.crt");
            Files.writeString(combined, Files.readString(unrelated.pem())
                    + "\n" + Files.readString(endpoint.pem()));

            CorporateTransport.apply(Map.of(CorporateTransport.CA_BUNDLE, combined.toString()));

            assertEquals(BODY, get(endpoint.url()),
                    "the second block of the bundle must be trusted too");
        } finally {
            endpoint.server().stop(0);
            resetTls();
        }
    }

    /** An unset bundle is the ordinary deployment and must change nothing. */
    @Test
    void anUnsetBundleLeavesTheJvmsOwnTrustStoreAlone(@TempDir Path dir) throws Exception {
        Endpoint endpoint = startTlsServer(dir);
        try {
            CorporateTransport.apply(Map.of());

            assertThrows(SSLHandshakeException.class, () -> get(endpoint.url()),
                    "with no bundle configured the private CA must still be untrusted");
        } finally {
            endpoint.server().stop(0);
        }
    }

    /**
     * A file that is not a certificate is refused, naming the variable.
     *
     * <p>Silently trusting nothing would present as every call failing at the handshake, with the
     * error naming the forge rather than the setting.
     */
    @Test
    void aBundleWithNoCertificateIsRefusedNamingTheVariable(@TempDir Path dir) throws Exception {
        Path notACertificate = Files.writeString(dir.resolve("notes.txt"), "this is not a PEM");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> CorporateTransport.apply(
                        Map.of(CorporateTransport.CA_BUNDLE, notACertificate.toString())));

        assertTrue(refused.getMessage().contains(CorporateTransport.CA_BUNDLE), refused.getMessage());
    }

    // ---- the proxy half -----------------------------------------------------------------------

    /**
     * A configured proxy is selected, and a no-proxy host is not.
     *
     * <p>Asserted through {@link ProxySelector#getDefault()} because that is the seam JGit reads;
     * a test of a helper method would prove the parse and not the installation.
     */
    @Test
    void aProxyIsSelectedForEveryHostExceptTheNoProxyList() {
        ProxySelector original = ProxySelector.getDefault();
        try {
            CorporateTransport.apply(Map.of(
                    "HTTPS_PROXY", "http://proxy.acme.example:3128",
                    "NO_PROXY", "acme.example,localhost"));

            assertEquals("HTTP @ proxy.acme.example:3128",
                    describe(ProxySelector.getDefault().select(URI.create("https://github.com/x"))),
                    "an external host goes through the proxy");
            assertEquals("DIRECT",
                    describe(ProxySelector.getDefault().select(URI.create("https://forge.acme.example/x"))),
                    "a suffix of a no-proxy entry is direct, which is the form operators write");
            assertEquals("DIRECT",
                    describe(ProxySelector.getDefault().select(URI.create("https://localhost:8080/x"))));
        } finally {
            ProxySelector.setDefault(original);
        }
    }

    /**
     * A no-proxy entry matches on a dot boundary, not as a bare substring.
     *
     * <p>{@code notacme.example} ending with {@code acme.example} would otherwise be sent direct —
     * an unrelated host silently bypassing a proxy an operator believes is mandatory.
     */
    @Test
    void aNoProxyEntryDoesNotMatchAnUnrelatedHostThatMerelyEndsWithIt() {
        ProxySelector original = ProxySelector.getDefault();
        try {
            CorporateTransport.apply(Map.of(
                    "HTTPS_PROXY", "http://proxy.acme.example:3128", "NO_PROXY", "acme.example"));

            assertEquals("HTTP @ proxy.acme.example:3128",
                    describe(ProxySelector.getDefault().select(URI.create("https://notacme.example/x"))));
        } finally {
            ProxySelector.setDefault(original);
        }
    }

    /** No proxy configured means the JVM's own selector is untouched. */
    @Test
    void anUnsetProxyLeavesTheSelectorAlone() {
        ProxySelector original = ProxySelector.getDefault();
        try {
            CorporateTransport.apply(Map.of());

            assertEquals(original, ProxySelector.getDefault());
        } finally {
            ProxySelector.setDefault(original);
        }
    }

    /**
     * The proxy's own credential is parsed in every form an operator writes.
     *
     * <p>Percent-decoded, because that is the form the {@code Proxy-Authorization} header carries —
     * a password written {@code p%40ss} in the URL is {@code p@ss} on the wire.
     */
    @Test
    void theProxyCredentialIsReadFromEveryUrlFormAnOperatorWrites() {
        assertEquals("[svc, TEST-pass]",
                asList(CorporateTransport.userInfoIn("http://svc:TEST-pass@proxy.acme.example:3128")));
        assertEquals("[svc, TEST-pass]",
                asList(CorporateTransport.userInfoIn("svc:TEST-pass@proxy.acme.example:3128")),
                "curl accepts a scheme-less proxy and operators write it");
        assertEquals("[svc, p@ss]",
                asList(CorporateTransport.userInfoIn("http://svc:p%40ss@proxy.acme.example:3128")),
                "the header carries the decoded form, so that is what must be recognised");
        assertNull(CorporateTransport.userInfoIn("http://proxy.acme.example:3128"));
        assertNull(CorporateTransport.userInfoIn("http://proxy.acme.example:3128/pac@v1"),
                "an @ in a path is not a credential");
    }

    /**
     * The JDK refuses Basic to a proxy on a CONNECT tunnel by default, which is every
     * https-through-a-proxy call a run makes. Left at its default an authenticating proxy answers
     * 407 and the failure names the proxy rather than the setting.
     */
    @Test
    void basicProxyAuthenticationIsEnabledWhenTheUrlCarriesACredential() {
        ProxySelector original = ProxySelector.getDefault();
        String tunneling = System.getProperty("jdk.http.auth.tunneling.disabledSchemes");
        try {
            CorporateTransport.apply(Map.of(
                    "HTTPS_PROXY", "http://svc:TEST-pass@proxy.acme.example:3128"));

            assertEquals("", System.getProperty("jdk.http.auth.tunneling.disabledSchemes"));
        } finally {
            ProxySelector.setDefault(original);
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes",
                    tunneling == null ? "Basic" : tunneling);
        }
    }

    private static String asList(String[] pair) {
        return pair == null ? "null" : "[" + pair[0] + ", " + pair[1] + "]";
    }

    private static String describe(List<java.net.Proxy> proxies) {
        java.net.Proxy proxy = proxies.getFirst();
        return proxy.type() == java.net.Proxy.Type.DIRECT ? "DIRECT"
                : proxy.type() + " @ " + ((InetSocketAddress) proxy.address()).getHostString()
                        + ":" + ((InetSocketAddress) proxy.address()).getPort();
    }

    private static String get(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        try (InputStream in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Undo the JVM-wide trust change.
     *
     * <p>{@code CorporateTransport} sets a DEFAULT, which outlives the test that set it — so
     * without this the next test in the JVM inherits a trust store containing one throwaway
     * certificate and, worse, {@code anUnsetBundleLeavesTheJvmsOwnTrustStoreAlone} would pass or
     * fail depending on test order.
     */
    private static void resetTls() throws Exception {
        SSLContext fresh = SSLContext.getInstance("TLS");
        fresh.init(null, null, null);
        SSLContext.setDefault(fresh);
        javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(fresh.getSocketFactory());
    }
}
