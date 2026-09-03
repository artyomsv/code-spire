package dev.codespire.runworker;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.encryption.EncryptionService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A corporate proxy URL may carry basic auth, and that is a credential the RUN does not hold.
 *
 * <p>The Global Constraint — no credential in a run event or a log line — has been enforced for the
 * secrets a command carries, because those are what {@code scrubFor} could reach. FR-F14 adds a
 * credential from the other direction: it belongs to the deployment, is set in every container of
 * every unit, and both git and curl quote the URL they tried when a connection fails. That text
 * becomes the run transcript and {@code factory_run.failure_detail}, which an operator reads.
 *
 * <p>The proxy HOST is deliberately not redacted. It is what makes a proxy failure legible, and an
 * error naming nothing sends an operator looking at the forge instead.
 */
class ProxyCredentialIsRedactedTest {

    private static final String PROXY_PASSWORD = "TEST-proxy-password-1";

    private static final String PROXY = "http://svc-account:" + PROXY_PASSWORD + "@proxy.acme.example:3128";

    private static EnterpriseEnvironmentConfig configuredWith(String httpsProxy) {
        EnterpriseEnvironmentConfig config = new EnterpriseEnvironmentConfig();
        config.caBundlePath = Optional.empty();
        config.httpProxy = Optional.empty();
        config.httpsProxy = Optional.ofNullable(httpsProxy);
        config.noProxy = Optional.empty();
        config.registryHost = Optional.empty();
        config.registryUsername = Optional.empty();
        config.registrySecret = Optional.empty();
        return config;
    }

    /** The whole point: the password comes out of the URL so the scrub has something to remove. */
    @Test
    void theProxyPasswordIsExtractedAndTheHostIsNot() {
        Optional<String> secret = configuredWith(PROXY).proxySecret();

        assertTrue(secret.isPresent(), "a URL with basic auth carries a credential to redact");
        assertFalse(secret.orElseThrow().contains("proxy.acme.example"),
                "redacting the host would leave an operator with a failure that names nothing");
    }

    /** A proxy with no credential in it contributes nothing, rather than a blank form to redact. */
    @Test
    void aProxyWithNoCredentialContributesNothing() {
        assertTrue(configuredWith("http://proxy.acme.example:3128").proxySecret().isEmpty());
        assertTrue(configuredWith("http://svc-account@proxy.acme.example:3128").proxySecret().isEmpty(),
                "a username with no password is not a credential");
        assertTrue(configuredWith(null).proxySecret().isEmpty());
    }

    /**
     * Parsed by hand rather than through {@code URI}, because a password routinely contains
     * characters {@code URI} rejects — and a thrown parse would resolve to "no secret to redact",
     * turning an unparseable password into an unredacted one.
     */
    @Test
    void aPasswordWithUrlHostileCharactersIsStillExtracted() {
        String awkward = "p@ss w0rd/with:specials";
        Optional<String> secret =
                configuredWith("http://user:" + awkward + "@proxy.acme.example:3128").proxySecret();

        assertTrue(secret.isPresent(), "a password URI cannot parse is the one most likely to leak");
    }

    /**
     * The seam, not the parser: deleting the line that adds the proxy secret to the scrub must fail
     * a test. Everything above passes with {@code RunFailures} never asking for it.
     */
    @Test
    void theProxyPasswordIsRemovedFromAFailureDetail() {
        Credentials credentials = new Credentials();
        credentials.encryption = new EncryptionService(EncryptionService.generateKeysetBase64());
        credentials.mapper = new ObjectMapper();

        RunFailures failures = RunLauncherTest.failuresWith(credentials,
                RunLauncherTest.proxiedWith(Optional.of(PROXY_PASSWORD)));

        // The command's own credentials are absent here on purpose: this asserts that the
        // deployment credential is scrubbed on its own, not that it rides along with a run's.
        String cleaned = failures.scrubFor(RunLauncherTest.commandWithNoCredentials())
                .clean("fatal: unable to access via " + PROXY + ": proxy CONNECT aborted");

        assertFalse(cleaned.contains(PROXY_PASSWORD), cleaned);
        assertTrue(cleaned.contains("proxy.acme.example"),
                "the host must survive, or the operator cannot tell which hop failed: " + cleaned);
    }
}
