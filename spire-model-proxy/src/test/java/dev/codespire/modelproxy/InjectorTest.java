package dev.codespire.modelproxy;

import dev.codespire.modelproxy.ProxyRoute.Injection;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class InjectorTest {

    private static final Credential KEY = new Credential(Injection.BEARER, "TEST-secret-key", null);

    /** What an agent might send: its placeholder credentials, ordinary headers, and transport headers. */
    private static Map<String, List<String>> incoming() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Authorization", List.of("Bearer TEST-PLACEHOLDER"));
        headers.put("X-api-key", List.of("TEST-PLACEHOLDER"));
        headers.put("Chatgpt-account-id", List.of("TEST-PLACEHOLDER-account"));
        headers.put("Cookie", List.of("TEST-cookie=1"));
        headers.put("Proxy-authorization", List.of("Basic TEST-PLACEHOLDER"));
        headers.put("Host", List.of("model-proxy:8080"));
        headers.put("Content-length", List.of("3"));
        headers.put("Transfer-encoding", List.of("chunked"));
        headers.put("Connection", List.of("keep-alive, X-hop"));
        headers.put("Keep-alive", List.of("timeout=5"));
        headers.put("Expect", List.of("100-continue"));
        headers.put("Upgrade", List.of("websocket"));
        headers.put("Te", List.of("trailers"));
        headers.put("X-hop", List.of("TEST-hop"));
        headers.put("Accept", List.of("text/event-stream"));
        headers.put("Content-type", List.of("application/json"));
        headers.put("Openai-beta", List.of("responses=experimental"));
        headers.put("Accept-Encoding", List.of("gzip, br"));
        return headers;
    }

    @Test
    void aBearerReplacesEveryCredentialTheAgentSent() {
        Map<String, List<String>> out = Injector.outgoing(incoming(), KEY);

        assertEquals(List.of("Bearer TEST-secret-key"), out.get("authorization"));
        assertNull(out.get("x-api-key"));
        assertNull(out.get("chatgpt-account-id"));
        assertNull(out.get("cookie"));
        assertNull(out.get("proxy-authorization"));
    }

    @Test
    void anXApiKeySendsNoAuthorization() {
        Map<String, List<String>> out =
                Injector.outgoing(incoming(), new Credential(Injection.X_API_KEY, "TEST-secret-key", null));

        assertEquals(List.of("TEST-secret-key"), out.get("x-api-key"));
        assertNull(out.get("authorization"));
        assertNull(out.get("chatgpt-account-id"));
    }

    @Test
    void aSeatSendsItsAccessTokenAndItsAccount() {
        Map<String, List<String>> out =
                Injector.outgoing(incoming(), new Credential(Injection.CHATGPT_SEAT, "TEST-access", "TEST-account"));

        assertEquals(List.of("Bearer TEST-access"), out.get("authorization"));
        assertEquals(List.of("TEST-account"), out.get("chatgpt-account-id"));
        assertNull(out.get("x-api-key"));
    }

    @Test
    void transportAndRestrictedHeadersAreNeverCopied() {
        Map<String, List<String>> out = Injector.outgoing(incoming(), KEY);

        for (String name : List.of("host", "content-length", "transfer-encoding", "connection", "keep-alive",
                "expect", "upgrade", "te", "x-hop")) {
            assertNull(out.get(name), name + " must not be forwarded");
        }
    }

    @Test
    void ordinaryHeadersPassUnderLowerCaseNames() {
        Map<String, List<String>> out = Injector.outgoing(incoming(), KEY);

        assertEquals(List.of("text/event-stream"), out.get("accept"));
        assertEquals(List.of("application/json"), out.get("content-type"));
        assertEquals(List.of("responses=experimental"), out.get("openai-beta"));
        out.keySet().forEach(name -> assertEquals(name.toLowerCase(java.util.Locale.ROOT), name));
    }

    @Test
    void aCredentialHeaderIsDroppedWhateverItsCase() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("AUTHORIZATION", List.of("Bearer TEST-PLACEHOLDER"));
        headers.put("authorization", List.of("Bearer TEST-PLACEHOLDER-2"));
        headers.put("COOKIE", List.of("TEST-cookie=1"));

        Map<String, List<String>> out = Injector.outgoing(headers, KEY);

        assertEquals(List.of("Bearer TEST-secret-key"), out.get("authorization"));
        assertFalse(out.containsKey("cookie"));
        assertEquals(1, out.keySet().stream().filter(n -> n.equalsIgnoreCase("authorization")).count());
    }

    /** The answer is searched for an echo of the credential, which a compressed body would hide. */
    @Test
    void theVendorIsAlwaysAskedForAnUnencodedAnswer() {
        Map<String, List<String>> out = Injector.outgoing(incoming(), KEY);

        assertEquals(List.of("identity"), out.get("accept-encoding"));
    }
}
