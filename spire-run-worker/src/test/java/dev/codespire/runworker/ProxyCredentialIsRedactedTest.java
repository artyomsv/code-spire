package dev.codespire.runworker;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.secrets.SecretScrub;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
     * A percent-encoded password yields BOTH spellings, because both appear and in different places.
     *
     * <p>This asserted only the DECODED form, on the reasoning that the header is the text that
     * leaks. The header does leak — and so does the environment variable, which carries the operator's
     * RAW spelling and is what {@code printenv} prints into a run transcript a viewer can read. The
     * two are not interchangeable and re-encoding does not bridge them: {@code URLEncoder} emits
     * uppercase hex and {@code +} for a space, so {@code p%2fss%20x} round-trips to {@code p%2Fss+x}
     * and matches neither. Measured.
     */
    @Test
    void aPercentEncodedPasswordIsRecognisedInBothFormsThatLeak() {
        List<SecretScrub.Credential> found =
                configuredWith(null, "http://svc:p%40ssw0rd-TEST@proxy.acme.example:3128")
                        .proxyCredentials();

        assertEquals(List.of(
                        new SecretScrub.Credential("svc", "p%40ssw0rd-TEST"),
                        new SecretScrub.Credential("svc", "p@ssw0rd-TEST")),
                found,
                "both spellings, and the ORDER here is incidental -- SecretScrub sorts every form "
                        + "longest-first, so the list order changes no behaviour. It is pinned only "
                        + "because an exact-list assertion is the one that catches a spelling going "
                        + "missing in either direction");
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
     * A SHORT proxy password starts the worker and is scrubbed, where it used to be a startup refusal.
     *
     * <p>The refusal is gone with the premise it rested on. {@code SecretScrub} skipped anything under
     * its floor, so a short password reached every failure detail verbatim and refusing to start was
     * the only protection available. It no longer skips, so the password is covered like any other.
     *
     * <p>Keeping the refusal would have meant asserting a NEW rule about what an operator may
     * configure — "a proxy password must be at least eight characters" — which this deployment has no
     * standing to make and which would block a working proxy for a logging reason. The readability
     * cost is carried by a warning {@code SecretScrub} logs once instead.
     */
    @Test
    void aShortProxyPasswordIsScrubbedRatherThanRefused() {
        EnterpriseEnvironmentConfig config =
                configuredWith(null, "http://svc:sh0rt1@proxy.acme.example:3128");

        assertDoesNotThrow(config::resolve, "a short password is a readability problem, not a refusal");

        String cleaned = SecretScrub.of(config.proxyCredentials())
                .clean("HTTPS_PROXY=http://svc:sh0rt1@proxy.acme.example:3128");
        assertFalse(cleaned.contains("sh0rt1"), cleaned);
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

    /**
     * The password is scrubbed in the spelling the CONTAINER carries, not only the decoded one.
     *
     * <p>Measured, because the mismatch is easy to talk past. An operator writes
     * {@code p%2fss%20x}; {@code credentialIn} decodes that to {@code p/ss x}, and
     * {@code SecretScrub} derives its URL-encoded form with {@code URLEncoder}, which produces
     * {@code p%2Fss+x} — uppercase hex, and {@code +} for the space. The value actually SET in
     * every container is the raw URL, so it contains neither form.
     *
     * <p>That matters because {@code env} and {@code printenv} are routine agent actions, and their
     * output goes to {@code run_event}, which a viewer can read. So the scrub must also carry the
     * secret exactly as it was written.
     */
    @Test
    void theProxyPasswordIsScrubbedInTheSpellingTheContainerCarries() {
        String asWritten = "p%2fss%20x-TEST";
        String proxyUrl = "http://svc:" + asWritten + "@proxy.acme.example:3128";

        SecretScrub scrub = SecretScrub.of(configuredWith(null, proxyUrl).proxyCredentials());

        String cleaned = scrub.clean("HTTPS_PROXY=" + proxyUrl);
        assertFalse(cleaned.contains(asWritten),
                "the raw spelling is what `printenv` prints into the transcript: " + cleaned);
        assertTrue(cleaned.contains("proxy.acme.example"),
                "the HOST must survive, or a scrub that redacted everything would pass this test "
                        + "and leave an operator a failure that names nothing: " + cleaned);
    }

    /**
     * And the decoded form too — that is the one a Proxy-Authorization header carries.
     *
     * <p><b>{@code %2f}, not {@code %40}, and the difference is the whole test.</b> With
     * {@code %40} this could not see a decode-only regression: {@code URLEncoder.encode("p@ss")}
     * returns {@code p%40ss} — the operator's own spelling, because {@code 40} has no hex LETTER
     * and so no case to differ on — so the DECODED credential's derived form reproduces the raw
     * text by coincidence and the assertion below passes with the raw spelling never collected.
     * Measured. {@code %2f} round-trips as {@code %2F} and the coincidence is gone.
     */
    @Test
    void bothSpellingsOfOneProxyPasswordAreScrubbed() {
        String asWritten = "p%2fss-TEST";
        String decoded = "p/ss-TEST";
        String proxyUrl = "http://svc:" + asWritten + "@proxy.acme.example:3128";

        SecretScrub scrub = SecretScrub.of(configuredWith(null, proxyUrl).proxyCredentials());

        String cleaned = scrub.clean("url " + proxyUrl + " header " + decoded);
        assertFalse(cleaned.contains(asWritten), cleaned);
        assertFalse(cleaned.contains(decoded), cleaned);
    }

    /**
     * A bare {@code %} in the password does not throw, and is still scrubbed.
     *
     * <p>{@code URLDecoder} rejects a {@code %} that is not an escape, and {@code 100%secure} is a
     * password an operator writes — {@code credentialIn}'s own javadoc already says a password
     * "routinely contains characters URI rejects". The deleted startup refusal was the only caller
     * of {@link EnterpriseEnvironmentConfig#proxyCredentials()}, so that throw used to happen at
     * boot by accident.
     *
     * <p>Without this, the first caller is the run-launch path: the throw lands AFTER the unit has
     * been created with the model key and git token in it, the dispatcher's catch re-enters the
     * same code and throws again, and the {@code finally} that calls {@code registry.forget} is
     * never entered — leaving a credential-bearing sandbox the watchdog is forbidden to reclaim.
     * One mistyped character, every run.
     */
    @Test
    void aBarePercentInTheProxyPasswordDoesNotThrowOutOfTheScrub() {
        EnterpriseEnvironmentConfig config =
                configuredWith(null, "http://svc:100%secure-TEST@proxy.acme.example:3128");

        List<SecretScrub.Credential> found = assertDoesNotThrow(config::proxyCredentials);

        assertEquals(List.of(new SecretScrub.Credential("svc", "100%secure-TEST")), found,
                "a % that is not an escape means the value already IS its own decoded form, so one "
                        + "entry is the truthful answer");
        assertFalse(SecretScrub.of(found).clean("HTTPS_PROXY=http://svc:100%secure-TEST@p:3128")
                .contains("100%secure-TEST"), "and it is still scrubbed");
    }

    /**
     * A literal {@code +} is not a space, and the header form must match what curl sends.
     *
     * <p>{@code URLDecoder} is a FORM decoder: it turns {@code +} into a space. A URI userinfo has
     * no such convention — curl percent-decodes only — so decoding {@code a+b} to {@code a b} would
     * derive a base64 form for a password that is never sent, leaving the real
     * {@code Proxy-Authorization} header uncovered.
     */
    @Test
    void aLiteralPlusInTheProxyPasswordIsNotDecodedToASpace() {
        List<SecretScrub.Credential> found =
                configuredWith(null, "http://svc:a+b-TEST@proxy.acme.example:3128").proxyCredentials();

        assertEquals(List.of(new SecretScrub.Credential("svc", "a+b-TEST")), found,
                "one entry: the value decodes to itself, so there is no second spelling");
    }
}
