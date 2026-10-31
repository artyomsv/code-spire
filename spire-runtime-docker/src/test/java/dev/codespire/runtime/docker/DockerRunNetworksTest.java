package dev.codespire.runtime.docker;

import dev.codespire.runtime.EgressAttempt;
import dev.codespire.runtime.EgressPolicy;
import dev.codespire.runtime.EnterpriseEnvironment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two texts the egress proxy turns on, without a daemon: the configuration it is handed and the log it writes
 * back. The log lines are tinyproxy 1.11's shapes; {@code DockerRunRuntimeIT} holds them to a real proxy.
 */
class DockerRunNetworksTest {

    private static final String PREFIX = "CONNECT   Nov 01 10:00:00.000 [7]: ";

    private static String request(String method, String target) {
        return PREFIX + "Request (file descriptor 6): " + method + " " + target + " HTTP/1.1";
    }

    private static String refused(String host) {
        return "NOTICE    Nov 01 10:00:00.000 [7]: Proxying refused on filtered domain \"" + host + "\"";
    }

    @Test
    void aConnectAndAnAbsoluteUrlAreEachOneAttempt() {
        List<EgressAttempt> attempts = DockerRunNetworks.parse(List.of(
                request("CONNECT", "api.openai.com:443"),
                request("GET", "http://172.17.0.5/index.html"),
                request("GET", "http://TEST-Mixed.Example.test:8080/x")));

        assertEquals(List.of(
                new EgressAttempt("api.openai.com", 443, true),
                new EgressAttempt("172.17.0.5", 80, true),
                new EgressAttempt("test-mixed.example.test", 8080, true)), attempts);
    }

    @Test
    void aRefusedHostIsRecordedAsRefused() {
        List<EgressAttempt> attempts = DockerRunNetworks.parse(List.of(
                request("GET", "http://undeclared.example.invalid/"),
                refused("undeclared.example.invalid")));

        assertEquals(List.of(new EgressAttempt("undeclared.example.invalid", 80, false)), attempts);
    }

    /** tinyproxy refuses a CONNECT outside its ports below the log level it writes, so the parse knows the rule. */
    @Test
    void aConnectToAPortOutsideTheAllowedSetIsRefused() {
        assertEquals(List.of(new EgressAttempt("db.example.test", 5432, false)),
                DockerRunNetworks.parse(List.of(request("CONNECT", "db.example.test:5432"))));
    }

    /** The log is the agent's text: user information never leaves, and nothing malformed is reported mangled. */
    @Test
    void userInformationIsDroppedAndMalformedTargetsAreIgnored() {
        List<EgressAttempt> attempts = DockerRunNetworks.parse(List.of(
                request("GET", "http://TEST-user:TEST-secret@host.example.test/"),
                request("GET", "ftp://host.example.test/"),
                request("CONNECT", "host.example.test:99999"),
                request("GET", "http://bad_host!.example.test/"),
                "a line the proxy never wrote"));

        assertEquals(List.of(new EgressAttempt("host.example.test", 80, true)), attempts);
        assertFalse(attempts.toString().contains("TEST-secret"));
    }

    @Test
    void repeatedRequestsAreOneAttemptAndTheAnswerIsBounded() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < DockerRunNetworks.MAX_ATTEMPTS + 50; i++) {
            lines.add(request("CONNECT", "h" + i + ".example.test:443"));
            lines.add(request("CONNECT", "h0.example.test:443"));
        }

        List<EgressAttempt> attempts = DockerRunNetworks.parse(lines);

        assertEquals(DockerRunNetworks.MAX_ATTEMPTS, attempts.size());
        assertEquals(new EgressAttempt("h0.example.test", 443, true), attempts.getFirst());
    }

    @Test
    void reportModeHasNoFilterAndEnforceDeniesByDefault() {
        String report = DockerRunNetworks.configFor(new EgressPolicy(EgressPolicy.Mode.REPORT, Set.of()),
                EnterpriseEnvironment.NONE, "172.30.0.0/16");
        String enforce = DockerRunNetworks.configFor(new EgressPolicy(EgressPolicy.Mode.ENFORCE, Set.of("api.openai.com")),
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
        assertEquals("^api\\.openai\\.com$\n^chatgpt\\.com$", DockerRunNetworks.filterFor(
                new EgressPolicy(EgressPolicy.Mode.ENFORCE, Set.of("chatgpt.com", "api.openai.com"))));
    }

    @Test
    void theProxyChainsToTheCorporateProxyAndHonoursItsExceptions() {
        EnterpriseEnvironment corporate = new EnterpriseEnvironment(List.of(), Map.of(
                "HTTPS_PROXY", "http://TEST-user:TEST-pass@TEST-proxy:3128/",
                "NO_PROXY", "intranet.example.test, .corp.example.test"));

        String config = DockerRunNetworks.configFor(EgressPolicy.OFF, corporate, "172.30.0.0/16");

        assertTrue(config.contains("Upstream http TEST-user:TEST-pass@TEST-proxy:3128"), config);
        assertTrue(config.contains("Upstream none \"intranet.example.test\""), config);
        assertTrue(config.contains("Upstream none \".corp.example.test\""), config);
    }

    @Test
    void aCorporateProxyThatCannotBeChainedIsRefusedRatherThanIgnored() {
        EnterpriseEnvironment corporate = new EnterpriseEnvironment(List.of(), Map.of("HTTPS_PROXY", "http://TEST-proxy"));

        assertThrows(IllegalArgumentException.class,
                () -> DockerRunNetworks.configFor(EgressPolicy.OFF, corporate, "172.30.0.0/16"));
    }
}
