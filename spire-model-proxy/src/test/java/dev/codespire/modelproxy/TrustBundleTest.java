package dev.codespire.modelproxy;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Map;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JVM ignores {@code SSL_CERT_FILE}, so the proxy builds its trust from the bundle itself. The
 * certificate is generated per run by {@code keytool} (as in the publisher's CorporateTransportTest):
 * nothing is committed and it is valid for one day.
 */
class TrustBundleTest {

    private static final String BODY = "TEST-BEHIND-THE-CORPORATE-CA";

    private static final String PASSWORD = "TEST-keystore-password";

    @Test
    void aBundleInSslCertFileIsTrusted(@TempDir Path dir) throws Exception {
        Path keystore = dir.resolve("server.p12");
        Path pem = dir.resolve("ca.crt");
        generateCertificate(dir, keystore, pem);
        HttpsServer server = serverPresenting(keystore);
        try {
            URI probe = URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/probe");

            assertThrows(SSLHandshakeException.class, () -> get(SSLContext.getDefault(), probe),
                    "a certificate no public root signs must not be trusted without the bundle");

            SSLContext built = TrustBundle.sslContext(Map.of("SSL_CERT_FILE", pem.toString()));

            assertEquals(BODY, get(built, probe), "the bundle's certificate must be trusted");
        } finally {
            server.stop(0);
        }
    }

    /** A self-signed certificate for localhost in {@code keystore}, and the same certificate as PEM in {@code pem}. */
    private static void generateCertificate(Path dir, Path keystore, Path pem) throws Exception {
        run(dir, "keytool", "-genkeypair", "-alias", "spire", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=localhost", "-validity", "1", "-storetype", "PKCS12",
                "-keystore", keystore.toString(), "-storepass", PASSWORD, "-keypass", PASSWORD,
                "-ext", "SAN=dns:localhost,ip:127.0.0.1");
        run(dir, "keytool", "-exportcert", "-rfc", "-alias", "spire",
                "-keystore", keystore.toString(), "-storepass", PASSWORD, "-file", pem.toString());
    }

    /** An HTTPS server on loopback presenting the keystore's certificate and answering {@code /probe}. */
    private static HttpsServer serverPresenting(Path keystore) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            store.load(in, PASSWORD.toCharArray());
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, PASSWORD.toCharArray());
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
        return server;
    }

    @Test
    void noBundleIsTheJdkDefault() throws Exception {
        assertSame(SSLContext.getDefault(), TrustBundle.sslContext(Map.of()));
        assertSame(SSLContext.getDefault(), TrustBundle.sslContext(Map.of("SSL_CERT_FILE", " ")));
    }

    @Test
    void aFileWithNoCertificateIsRefusedNamingTheVariable(@TempDir Path dir) throws Exception {
        Path notACertificate = Files.writeString(dir.resolve("TEST-notes.txt"), "this is not a PEM");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> TrustBundle.sslContext(Map.of("SSL_CERT_FILE", notACertificate.toString())));

        assertTrue(refused.getMessage().contains("SSL_CERT_FILE"), refused.getMessage());
        assertFalse(refused.getMessage().contains("TEST-notes"), refused.getMessage());
    }

    private static String get(SSLContext context, URI uri) throws Exception {
        HttpClient client = HttpClient.newBuilder().sslContext(context).build();
        HttpResponse<String> response =
                client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    private static void run(Path dir, String... command) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin");
        String[] resolved = command.clone();
        resolved[0] = java.resolve(command[0]).toString();
        Process process = new ProcessBuilder(resolved).directory(dir.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), command[0] + " failed: " + output);
    }
}
