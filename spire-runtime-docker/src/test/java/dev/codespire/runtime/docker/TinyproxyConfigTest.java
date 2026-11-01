package dev.codespire.runtime.docker;

import dev.codespire.runtime.EgressPolicy;
import dev.codespire.runtime.EnterpriseEnvironment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The configuration the egress proxy is handed, without a daemon: the mode, the declared hosts, and the corporate
 * upstream as tinyproxy's directive can and cannot carry it.
 */
class TinyproxyConfigTest {

    @Test
    void reportModeHasNoFilterAndEnforceDeniesByDefault() {
        String report = TinyproxyConfig.configFor(new EgressPolicy(EgressPolicy.Mode.REPORT, Set.of()),
                EnterpriseEnvironment.NONE, "172.30.0.0/16");
        String enforce = TinyproxyConfig.configFor(new EgressPolicy(EgressPolicy.Mode.ENFORCE, Set.of("api.openai.com")),
                EnterpriseEnvironment.NONE, "172.30.0.0/16");

        assertTrue(report.contains("Allow 172.30.0.0/16"), report);
        assertTrue(report.contains("Port 8888"), report);
        assertTrue(report.contains("ConnectPort 443") && report.contains("ConnectPort 80"), report);
        assertTrue(report.contains("LogLevel Connect"), report);
        // A named LogFile, /dev/stdout included, is refused by tinyproxy's create_file_safely and the log goes to a
        // syslog the container does not have. With none, tinyproxy writes to stdout, which is what egressOf reads.
        assertFalse(report.contains("LogFile"), report);
        assertFalse(report.contains("Syslog"), report);
        assertFalse(report.contains("Filter"), report);
        assertTrue(enforce.contains("Filter \"/tmp/filter\""), enforce);
        assertTrue(enforce.contains("FilterDefaultDeny Yes"), enforce);
        assertFalse(report.contains("Upstream"), "no corporate proxy, no upstream: " + report);
    }

    @Test
    void theFilterAnchorsEachDeclaredHostExactly() {
        assertEquals("^api\\.openai\\.com$\n^chatgpt\\.com$", TinyproxyConfig.filterFor(
                new EgressPolicy(EgressPolicy.Mode.ENFORCE, Set.of("chatgpt.com", "api.openai.com"))));
    }

    @Test
    void theProxyChainsToTheCorporateProxyAndHonoursItsExceptions() {
        EnterpriseEnvironment corporate = new EnterpriseEnvironment(List.of(), Map.of(
                "HTTPS_PROXY", "http://TEST-user:TEST-pass@TEST-proxy:3128/",
                "NO_PROXY", "intranet.example.test, .corp.example.test"));

        String config = TinyproxyConfig.configFor(EgressPolicy.OFF, corporate, "172.30.0.0/16");

        assertTrue(config.contains("Upstream http TEST-user:TEST-pass@TEST-proxy:3128"), config);
        assertTrue(config.contains("Upstream none \"intranet.example.test\""), config);
        assertTrue(config.contains("Upstream none \".corp.example.test\""), config);
    }

    /**
     * tinyproxy has no TLS to its upstream, so an https:// corporate proxy cannot be chained. Writing it as
     * {@code Upstream http} would send CONNECT and the proxy credential in clear text to a port that expects TLS:
     * refused, naming the setting, and without quoting the credential.
     */
    @Test
    void anHttpsCorporateProxyIsRefusedNeverDowngradedToPlainHttp() {
        EnterpriseEnvironment corporate = new EnterpriseEnvironment(List.of(), Map.of(
                "HTTPS_PROXY", "https://TEST-user:TEST-pass@TEST-proxy.example.test:8443"));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> TinyproxyConfig.configFor(EgressPolicy.OFF, corporate, "172.30.0.0/16"));

        assertTrue(refused.getMessage().contains("SPIRE_RUN_HTTPS_PROXY"), refused.getMessage());
        assertTrue(refused.getMessage().contains("https"), refused.getMessage());
        assertFalse(refused.getMessage().contains("TEST-pass"), "the refusal must not quote the credential");
    }

    /**
     * tinyproxy builds Basic authentication from the Upstream directive as written, so a percent-encoded password
     * must reach it decoded, as the corporate transport already decodes it. '+' is a plus, not a space.
     */
    @Test
    void anEncodedProxyCredentialIsDecodedForTheUpstream() {
        EnterpriseEnvironment corporate = new EnterpriseEnvironment(List.of(), Map.of(
                "HTTPS_PROXY", "http://TEST-user:TEST-p%25ss+word@TEST-proxy:3128"));

        String config = TinyproxyConfig.configFor(EgressPolicy.OFF, corporate, "172.30.0.0/16");

        assertTrue(config.contains("Upstream http TEST-user:TEST-p%ss+word@TEST-proxy:3128"), config);
    }

    /**
     * What tinyproxy's directive cannot hold is refused rather than rendered into a line it would parse differently:
     * a ':' in the user name, an '@' in the password, whitespace or a quote anywhere.
     */
    @Test
    void aCredentialTheUpstreamDirectiveCannotHoldIsRefused() {
        for (String url : List.of("http://TEST%3Auser:TEST-pass@TEST-proxy:3128",
                "http://TEST-user:TEST-p%40ss@TEST-proxy:3128",
                "http://TEST-user:TEST-p%20ss@TEST-proxy:3128",
                "http://TEST-user:TEST-p%22ss@TEST-proxy:3128")) {
            EnterpriseEnvironment corporate = new EnterpriseEnvironment(List.of(), Map.of("HTTPS_PROXY", url));

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> TinyproxyConfig.configFor(EgressPolicy.OFF, corporate, "172.30.0.0/16"), url);
            assertFalse(refused.getMessage().contains("TEST-p"), "the refusal must not quote the credential: " + url);
        }
    }

    @Test
    void aCorporateProxyThatCannotBeChainedIsRefusedRatherThanIgnored() {
        EnterpriseEnvironment corporate = new EnterpriseEnvironment(List.of(), Map.of("HTTPS_PROXY", "http://TEST-proxy"));

        assertThrows(IllegalArgumentException.class,
                () -> TinyproxyConfig.configFor(EgressPolicy.OFF, corporate, "172.30.0.0/16"));
    }
}
