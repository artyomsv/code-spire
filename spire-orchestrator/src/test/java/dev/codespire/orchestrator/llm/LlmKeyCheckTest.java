package dev.codespire.orchestrator.llm;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a failed LLM key check tells the operator, and what it never repeats (review of PR #393).
 *
 * <p>The reason reaches the save dialog, the Check button, a warning log and the stored last check
 * error, so it is always one of {@link KeyCheckReason}'s fixed sentences. Provider text is recognised
 * only for Anthropic's own workspace refusal; a body is read only when that recognition needs it, and
 * the whole call has one deadline.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class LlmKeyCheckTest {

    private static final String WORKSPACE_JSON = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
            + "\"message\":\"TEST-This API key is not scoped to a workspace, so add a header.\"}}";
    private static final long SLACK_MS = 5_000;

    private static WireMockServer llm;

    @Inject
    LlmProviderRegistry registry;

    @Inject
    LlmModelRegistry models;

    @BeforeAll
    static void startLlm() {
        llm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        llm.start();
    }

    @AfterAll
    static void stopLlm() {
        llm.stop();
    }

    @BeforeEach
    void reset() {
        registry.list().forEach(v -> registry.delete(UUID.fromString(v.id())));
        catalogue("openai", "TEST-MODEL");
        catalogue("anthropic", "claude-sonnet-4");
        llm.resetAll();
    }

    @Test
    void anAnthropicUserKeyIsToldToUseAWorkspaceKey() {
        String said = says("anthropic", answer(400, "application/json", WORKSPACE_JSON));

        assertEquals(KeyCheckReason.WORKSPACE_REQUIRED.message(400), said);
        assertTrue(said.contains("inside a workspace"), said);
        assertFalse(said.contains("TEST-"), said);
    }

    @Test
    void anotherProvidersWorkspaceTextGetsTheGenericReason() {
        assertEquals(KeyCheckReason.REFUSED.message(400), says("openai", answer(400, "application/json", WORKSPACE_JSON)));
    }

    @Test
    void anHtmlPageWithThePhraseGetsTheGenericReason() {
        assertEquals(KeyCheckReason.REFUSED.message(400),
                says("anthropic", answer(400, "text/html", "<html>TEST-not scoped to a workspace</html>")));
    }

    @Test
    void thePhraseOutsideTheErrorMessageGetsTheGenericReason() {
        String body = "{\"type\":\"error\",\"error\":{\"type\":\"TEST-not scoped to a workspace\",\"message\":\"TEST-other\"}}";
        assertEquals(KeyCheckReason.REFUSED.message(400), says("anthropic", answer(400, "application/json", body)));
    }

    @Test
    void aRejectedKeyARateLimitAndAFailingProviderEachSaySo() {
        assertEquals(KeyCheckReason.REJECTED.message(401), says("openai", answer(401, "application/json", "{}")));
        assertEquals(KeyCheckReason.RATE_LIMITED.message(429), says("openai", answer(429, "application/json", "{}")));
        assertEquals(KeyCheckReason.PROVIDER_FAILING.message(502), says("openai", answer(502, "text/html", "<html/>")));
    }

    /** A provider message may echo part of the key or name an account; none of it comes back. */
    @Test
    void theProvidersOwnTextNeverComesBack() {
        String said = says("anthropic", answer(400, "application/json",
                "{\"error\":{\"message\":\"TEST-secret-***3456 for TEST-account@example.invalid\"}}"));

        assertEquals(KeyCheckReason.REFUSED.message(400), said);
    }

    /** Headers arrive at once and the body stalls: the call's one deadline still ends it, as a timeout. */
    @Test
    void aStalledErrorBodyEndsAtTheDeadline() {
        long started = System.currentTimeMillis();
        String said = says("anthropic", answer(400, "application/json", WORKSPACE_JSON)
                .withChunkedDribbleDelay(WORKSPACE_JSON.length(), (int) LlmKeyValidator.TIMEOUT.toMillis() * 3));

        assertEquals(KeyCheckReason.TIMEOUT.message(0), said);
        assertTrue(System.currentTimeMillis() - started < LlmKeyValidator.TIMEOUT.toMillis() + SLACK_MS);
    }

    /** A refusal's body is never needed, so a slow one is not waited for. */
    @Test
    void aRefusalDoesNotWaitForItsBody() {
        long started = System.currentTimeMillis();
        String said = says("openai", answer(401, "application/json", WORKSPACE_JSON)
                .withChunkedDribbleDelay(WORKSPACE_JSON.length(), (int) LlmKeyValidator.TIMEOUT.toMillis() * 3));

        assertEquals(KeyCheckReason.REJECTED.message(401), said);
        assertTrue(System.currentTimeMillis() - started < SLACK_MS);
    }

    @Test
    void aProviderThatDoesNotAnswerInTimeSaysSo() {
        assertEquals(KeyCheckReason.TIMEOUT.message(0), says("openai",
                answer(200, "application/json", "{}").withFixedDelay((int) LlmKeyValidator.TIMEOUT.toMillis() + 1500)));
    }

    @Test
    void anUnreachableProviderSaysSo() {
        assertEquals(KeyCheckReason.UNREACHABLE.message(0), post(body("openai", "http://localhost:1")));
    }

    /**
     * The HTTP client builds its exception from the bytes it could not parse, and a provider can
     * echo the key there. The log names the failure's kind only, never its text or causes.
     */
    @Test
    void aGarbledAnswerNeverReachesTheLog() throws Exception {
        List<String> logged = new ArrayList<>();
        Handler capture = capture(logged);
        Logger logger = Logger.getLogger(LlmKeyValidator.class.getName());
        logger.addHandler(capture);
        try (ServerSocket garbled = garbledServer()) {
            String said = post(body("openai", "http://localhost:" + garbled.getLocalPort()));

            assertEquals(KeyCheckReason.UNREACHABLE.message(0), said);
            assertFalse(logged.isEmpty(), "the failure must be logged at all");
            assertTrue(logged.stream().noneMatch(line -> line.contains("TEST-")), String.join("\n", logged));
        } finally {
            logger.removeHandler(capture);
        }
    }

    /** The Check button, its log line and the stored last error carry the same fixed sentence. */
    @Test
    void theCheckButtonStoresTheSameFixedReason() {
        llm.stubFor(get(urlEqualTo("/models")).willReturn(answer(200, "application/json", "{\"data\":[]}")));
        String id = given().contentType("application/json").body(body("openai", llm.baseUrl()))
                .when().post("/api/llm-providers").then().statusCode(201).extract().path("id");
        llm.stubFor(get(urlEqualTo("/models")).willReturn(
                answer(403, "application/json", "{\"error\":{\"message\":\"TEST-access disabled\"}}")));

        given().when().post("/api/llm-providers/" + id + "/check").then().statusCode(200);

        assertEquals(KeyCheckReason.REJECTED.message(403),
                registry.get(UUID.fromString(id)).orElseThrow().lastCheckError());
    }

    private String says(String type, ResponseDefinitionBuilder answer) {
        llm.stubFor(get(urlEqualTo("/models")).willReturn(answer));
        return post(body(type, llm.baseUrl()));
    }

    private static String post(Map<String, Object> provider) {
        return given().contentType("application/json").body(provider)
                .when().post("/api/llm-providers")
                .then().statusCode(400)
                .contentType(org.hamcrest.Matchers.containsString("text/plain"))
                .extract().asString();
    }

    private static ResponseDefinitionBuilder answer(int status, String contentType, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", contentType).withBody(body);
    }

    private static Map<String, Object> body(String type, String baseUrl) {
        Map<String, Object> provider = new HashMap<>();
        provider.put("name", "TEST-" + type);
        provider.put("type", type);
        provider.put("baseUrl", baseUrl);
        provider.put("model", "anthropic".equals(type) ? "claude-sonnet-4" : "TEST-MODEL");
        provider.put("apiKey", "TEST-key");
        return provider;
    }

    private void catalogue(String type, String name) {
        if (models.list().stream().noneMatch(m -> m.name().equals(name))) {
            models.create(new LlmModelInput(type, name, name, "UNMETERED", Map.of(),
                    null, null, null, null, true, List.of()));
        }
    }

    /** Answers every connection with a status line no client can parse, carrying TEST- markers. */
    private static ServerSocket garbledServer() throws IOException {
        ServerSocket server = new ServerSocket(0);
        Thread.ofVirtual().start(() -> {
            while (!server.isClosed()) {
                try (Socket client = server.accept(); InputStream in = client.getInputStream()) {
                    readRequestHead(in);
                    client.getOutputStream().write(("HTTP/1.1 XYZ TEST-secret-echo TEST-account@example.invalid\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                } catch (IOException closed) {
                    return;
                }
            }
        });
        return server;
    }

    /** Reads up to the blank line that ends a request's head, so the answer follows the request. */
    private static void readRequestHead(InputStream in) throws IOException {
        int matched = 0;
        byte[] end = {'\r', '\n', '\r', '\n'};
        while (matched < end.length) {
            int b = in.read(); // read only while the head is unfinished: the request has no body to wait for
            if (b == -1) {
                return;
            }
            matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
        }
    }

    /** Every record's formatted text, parameters and whole throwable chain, as one line each. */
    private static Handler capture(List<String> into) {
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                StringWriter trace = new StringWriter();
                if (record.getThrown() != null) {
                    record.getThrown().printStackTrace(new PrintWriter(trace));
                }
                into.add(record.getMessage() + java.util.Arrays.toString(record.getParameters()) + trace);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.ALL);
        return handler;
    }
}
