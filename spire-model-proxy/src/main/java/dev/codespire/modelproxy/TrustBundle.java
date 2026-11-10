package dev.codespire.modelproxy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * The TLS trust for the vendor connection, from the PEM bundle {@code SSL_CERT_FILE} names.
 *
 * <p>The JVM ignores {@code SSL_CERT_FILE}, so behind a TLS-inspecting corporate proxy the vendor
 * call would fail at the handshake. The bundle logic is COPIED from
 * {@code spire-publisher/src/main/java/dev/codespire/publisher/CorporateTransport.java}
 * ({@code trustOnly}), whose javadoc gives the reasoning: the bundle REPLACES the trust store (the
 * documented contract is a complete bundle), and {@code generateCertificates} is plural because a
 * bundle is many PEM blocks. Unlike the original it returns a context for this process's one client
 * instead of setting the JVM default. Keep the two in step.
 */
final class TrustBundle {

    static final String CA_BUNDLE = "SSL_CERT_FILE";

    private TrustBundle() {
    }

    /** A context trusting only the bundle's certificates, or the JDK default when no bundle is set. */
    static SSLContext sslContext(Map<String, String> env) {
        String path = env.get(CA_BUNDLE);
        try {
            if (path == null || path.isBlank()) {
                return SSLContext.getDefault();
            }
            return trustOnly(Files.readAllBytes(Path.of(path.strip())));
        } catch (IOException | GeneralSecurityException | InvalidPathException unreadable) {
            throw new IllegalArgumentException(CA_BUNDLE + " could not be read as a PEM certificate bundle");
        }
    }

    private static SSLContext trustOnly(byte[] pem) throws IOException, GeneralSecurityException {
        Collection<? extends Certificate> certificates;
        try (InputStream in = new ByteArrayInputStream(pem)) {
            certificates = CertificateFactory.getInstance("X.509").generateCertificates(in);
        }
        if (certificates.isEmpty()) {
            throw new IllegalArgumentException(CA_BUNDLE + " names a file that holds no X.509 certificate");
        }
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        int index = 0;
        for (Certificate certificate : certificates) {
            store.setCertificateEntry("spire-ca-" + index++, certificate);
        }
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        // TLSv1.3 enables {1.3, 1.2} on this JDK, as CorporateTransport records; "TLSv1.2" would drop 1.3.
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }
}
