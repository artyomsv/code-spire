package dev.codespire.runworker;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.encryption.EncryptionService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    private static final String PROXY_USER = "svc-account";

    private static final String PROXY =
            "http://" + PROXY_USER + ":" + PROXY_PASSWORD + "@proxy.acme.example:3128";

    private static EnterpriseEnvironmentConfig configuredWith(String httpProxy, String httpsProxy) {
        EnterpriseEnvironmentConfig config = new EnterpriseEnvironmentConfig();
        config.caBundlePath = Optional.empty();
        config.httpProxy = Optional.ofNullable(httpProxy);
        config.httpsProxy = Optional.ofNullable(httpsProxy);
        config.noProxy = Optional.empty();
        config.registryHost = Optional.empty();
        config.registryUsername = Optional.empty();
        config.registrySecret = Optional.empty();
        return config;
    }

    /**
     * The exact credential, not merely "something was found".
     *
     * <p>The first version of this asserted only {@code isPresent()}, so a parser returning
     * {@code svc-account:TEST-proxy-password-1} — the userinfo rather than the password — passed it.
     * The value is the whole property: it is what gets removed from the text an operator reads.
     */
    @Test
    void theProxyPasswordIsExtractedWithItsUsernameAndNotTheHost() {
        List<SecretScrub.Credential> found = configuredWith(null, PROXY).proxyCredentials();

        assertEquals(List.of(new SecretScrub.Credential(PROXY_USER, PROXY_PASSWORD)), found);
    }

    /** A proxy with no credential in it contributes nothing, rather than a blank form to redact. */
    @Test
    void aProxyWithNoCredentialContributesNothing() {
        assertEquals(List.of(),
                configuredWith(null, "http://proxy.acme.example:3128").proxyCredentials());
        assertEquals(List.of(),
                configuredWith(null, "http://svc@proxy.acme.example:3128").proxyCredentials(),
                "a username with no password is not a credential");
        assertEquals(List.of(), configuredWith(null, null).proxyCredentials());
    }

    /**
     * A scheme-less proxy URL is a form curl accepts and operators write.
     *
     * <p>Requiring the scheme meant the password was set in every container and scrubbed from
     * nothing — the same silent outcome the hand parser exists to avoid, arriving through the shape
     * nobody tested.
     */
    @Test
    void aSchemeLessProxyUrlStillCarriesACredential() {
        List<SecretScrub.Credential> found =
                configuredWith(null, PROXY_USER + ":" + PROXY_PASSWORD + "@proxy.acme.example:3128")
                        .proxyCredentials();

        assertEquals(List.of(new SecretScrub.Credential(PROXY_USER, PROXY_PASSWORD)), found);
    }

    /** An {@code @} in a path is not the end of a userinfo that is not there. */
    @Test
    void anAtSignInAPathIsNotMistakenForACredential() {
        assertEquals(List.of(),
                configuredWith(null, "http://proxy.acme.example:3128/pac@v1").proxyCredentials());
    }

    /**
     * The value is percent-DECODED, because the URL carries {@code p%40ss} and the
     * {@code Proxy-Authorization} header carries {@code p@ss}. The header is the text that leaks.
     */
    @Test
    void aPercentEncodedPasswordIsRecognisedInTheFormThatActuallyLeaks() {
        List<SecretScrub.Credential> found =
                configuredWith(null, "http://svc:p%40ssw0rd-TEST@proxy.acme.example:3128")
                        .proxyCredentials();

        assertEquals("p@ssw0rd-TEST", found.getFirst().secret());
    }

    /**
     * BOTH proxies, not the first.
     *
     * <p>The two are separately configurable, so a rotation or two upstreams gives them different
     * passwords — and the one a {@code findFirst} dropped was as likely to be the https credential,
     * which is what every forge and model call actually uses.
     */
    @Test
    void everyProxyPasswordIsCollectedNotOnlyTheFirst() {
        List<SecretScrub.Credential> found = configuredWith(
                "http://svc:TEST-http-password@proxy-a.acme.example:3128",
                "http://svc:TEST-https-password@proxy-b.acme.example:3128").proxyCredentials();

        assertEquals(List.of(
                        new SecretScrub.Credential("svc", "TEST-http-password"),
                        new SecretScrub.Credential("svc", "TEST-https-password")),
                found);
    }

    /** One credential on both proxies is one credential, not two identical forms to redact. */
    @Test
    void oneCredentialOnBothProxiesIsCollectedOnce() {
        assertEquals(1, configuredWith(PROXY, PROXY).proxyCredentials().size());
    }

    /**
     * A password the scrub cannot act on is a startup refusal.
     *
     * <p>{@code SecretScrub} ignores anything under its floor, which is right for a run's own tokens
     * and wrong for a password an operator typed: below it the password appears verbatim in every
     * failure detail this deployment writes, with nothing on screen saying why.
     */
    @Test
    void aProxyPasswordTooShortToScrubIsRefusedAtStartup() {
        EnterpriseEnvironmentConfig config =
                configuredWith(null, "http://svc:short@proxy.acme.example:3128");

        IllegalStateException refused = assertThrows(IllegalStateException.class, config::resolve);

        assertTrue(refused.getMessage().contains("shorter"), refused.getMessage());
        assertFalse(refused.getMessage().contains("short".repeat(1) + "@"), "never quote the value");
    }

    /**
     * The seam, not the parser: deleting the line that adds the proxy credentials to the scrub must
     * fail a test. Everything above passes with {@code RunFailures} never asking for them.
     */
    @Test
    void theProxyPasswordIsRemovedFromAFailureDetail() {
        RunFailures failures = RunLauncherTest.failuresWith(credentials(),
                RunLauncherTest.proxiedWith(List.of(
                        new SecretScrub.Credential(PROXY_USER, PROXY_PASSWORD))));

        // The command's own credentials do not decrypt here on purpose: this asserts that the
        // deployment credential is scrubbed on its own, not that it rides along with a run's.
        String cleaned = failures.scrubFor(RunLauncherTest.commandWithNoCredentials())
                .clean("fatal: unable to access via " + PROXY + ": proxy CONNECT aborted");

        assertFalse(cleaned.contains(PROXY_PASSWORD), cleaned);
        assertTrue(cleaned.contains("proxy.acme.example"),
                "the host must survive, or the operator cannot tell which hop failed: " + cleaned);
    }

    /**
     * The Basic form is built with the PROXY's username, which is the one that appears on the wire.
     *
     * <p>Pairing the proxy password with the SCM username produced {@code base64(scmUser:proxyPass)}
     * — a string no request carries — while {@code SecretScrub}'s own javadoc names the
     * {@code Proxy-Authorization: Basic} header as one of the three forms that matter. A verbose
     * curl prints that header, and an agent at full shell access can run one.
     */
    @Test
    void theBasicAuthorizationHeaderFormIsRedactedToo() {
        RunFailures failures = RunLauncherTest.failuresWith(credentials(),
                RunLauncherTest.proxiedWith(List.of(
                        new SecretScrub.Credential(PROXY_USER, PROXY_PASSWORD))));

        String header = Base64.getEncoder().encodeToString(
                (PROXY_USER + ":" + PROXY_PASSWORD).getBytes(StandardCharsets.UTF_8));

        String cleaned = failures.scrubFor(RunLauncherTest.commandWithNoCredentials())
                .clean("> Proxy-Authorization: Basic " + header);

        assertFalse(cleaned.contains(header),
                "the header form is what a verbose curl prints: " + cleaned);
    }

    private static Credentials credentials() {
        Credentials credentials = new Credentials();
        credentials.encryption = new EncryptionService(EncryptionService.generateKeysetBase64());
        credentials.mapper = new ObjectMapper();
        return credentials;
    }
}
