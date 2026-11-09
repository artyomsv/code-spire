package dev.codespire.modelproxy;

import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** What the vendor receives: the run's credential in place of the agent's, and the agent's body as it was. */
class ModelProxyCredentialTest extends ProxyTestSupport {

    @Test
    void replacesWhateverCredentialTheAgentSent() throws Exception {
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()));

        HttpResponse<String> response = send(HttpRequest.newBuilder(at(proxy, "/v1/models"))
                .header("Authorization", "Bearer TEST-PLACEHOLDER")
                .header("Cookie", "TEST-cookie=1")
                .GET());

        assertEquals(200, response.statusCode());
        assertEquals(List.of("Bearer " + API_SECRET), seenHeaders.get("authorization"));
        assertNull(seenHeaders.get("cookie"));
    }

    @Test
    void aSeatSendsTheAccessTokenAndTheAccountId() throws Exception {
        ModelProxy proxy = start(seatRoute(upstreamOrigin()));

        HttpResponse<String> response = send(HttpRequest.newBuilder(at(proxy, "/backend-api/codex/models"))
                .header("Authorization", "Bearer TEST-PLACEHOLDER")
                .header("chatgpt-account-id", "TEST-PLACEHOLDER-account")
                .GET());

        assertEquals(200, response.statusCode());
        assertEquals(List.of("Bearer " + SEAT_SECRET), seenHeaders.get("authorization"));
        assertEquals(List.of(SEAT_ACCOUNT), seenHeaders.get("chatgpt-account-id"));
    }

    @Test
    void anXApiKeyRouteSendsNoAuthorization() throws Exception {
        Map<String, String> env = apiKeyRoute(upstreamOrigin());
        env.put("SPIRE_MODEL_INJECTION", "X_API_KEY");
        env.put("SPIRE_MODEL_PATHS_EXACT", "/v1/messages");
        ModelProxy proxy = start(env);

        HttpResponse<String> response = send(HttpRequest.newBuilder(at(proxy, "/v1/messages"))
                .header("Authorization", "Bearer TEST-PLACEHOLDER")
                .header("x-api-key", "TEST-PLACEHOLDER")
                .POST(HttpRequest.BodyPublishers.ofString("{}")));

        assertEquals(200, response.statusCode());
        assertEquals(List.of(API_SECRET), seenHeaders.get("x-api-key"));
        assertNull(seenHeaders.get("authorization"));
    }

    @Test
    void aContentLengthIsKept() throws Exception {
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()));

        HttpResponse<String> response = send(HttpRequest.newBuilder(at(proxy, "/v1/responses"))
                .POST(HttpRequest.BodyPublishers.ofByteArray("abc".getBytes(StandardCharsets.UTF_8))));

        assertEquals(200, response.statusCode());
        assertEquals(List.of("3"), seenHeaders.get("content-length"));
        assertNull(seenHeaders.get("transfer-encoding"));
        assertEquals("abc", new String(seenBody, StandardCharsets.UTF_8));
    }

    /** The answer must be readable as it comes, so the proxy can look for the credential in it. */
    @Test
    void theVendorIsAskedForAnUnencodedAnswer() throws Exception {
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()));

        HttpResponse<String> response = send(HttpRequest.newBuilder(at(proxy, "/v1/responses"))
                .header("Accept-Encoding", "gzip, br")
                .POST(HttpRequest.BodyPublishers.ofString("{}")));

        assertEquals(200, response.statusCode());
        assertEquals(List.of("identity"), seenHeaders.get("accept-encoding"));
    }
}
