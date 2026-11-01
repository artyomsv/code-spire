package dev.codespire.runtime.docker;

import dev.codespire.runtime.EgressAttempt;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The egress proxy log read back without a daemon. The lines are tinyproxy 1.11's shapes, from its source;
 * {@code DockerRunRuntimeIT} holds them to a real proxy.
 */
class ProxyLogParserTest {

    private static final String PREFIX = "CONNECT   Nov 01 10:00:00.000 [7]: ";

    private static String request(String method, String target) {
        return PREFIX + "Request (file descriptor 6): " + method + " " + target + " HTTP/1.1";
    }

    private static String refused(String host) {
        return "NOTICE    Nov 01 10:00:00.000 [7]: Proxying refused on filtered domain \"" + host + "\"";
    }

    @Test
    void aConnectAndAnAbsoluteUrlAreEachOneAttempt() {
        List<EgressAttempt> attempts = ProxyLogParser.parse(List.of(
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
        List<EgressAttempt> attempts = ProxyLogParser.parse(List.of(
                request("GET", "http://undeclared.example.invalid/"),
                refused("undeclared.example.invalid")));

        assertEquals(List.of(new EgressAttempt("undeclared.example.invalid", 80, false)), attempts);
    }

    /** tinyproxy refuses a CONNECT outside its ports below the log level it writes, so the parse knows the rule. */
    @Test
    void aConnectToAPortOutsideTheAllowedSetIsRefused() {
        assertEquals(List.of(new EgressAttempt("db.example.test", 5432, false)),
                ProxyLogParser.parse(List.of(request("CONNECT", "db.example.test:5432"))));
    }

    /** The log is the agent's text: user information never leaves, and nothing malformed is reported mangled. */
    @Test
    void userInformationIsDroppedAndMalformedTargetsAreIgnored() {
        List<EgressAttempt> attempts = ProxyLogParser.parse(List.of(
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
        for (int i = 0; i < ProxyLogParser.MAX_ATTEMPTS + 50; i++) {
            lines.add(request("CONNECT", "h" + i + ".example.test:443"));
            lines.add(request("CONNECT", "h0.example.test:443"));
        }

        List<EgressAttempt> attempts = ProxyLogParser.parse(lines);

        assertEquals(ProxyLogParser.MAX_ATTEMPTS, attempts.size());
        assertEquals(new EgressAttempt("h0.example.test", 443, true), attempts.getFirst());
    }
}
