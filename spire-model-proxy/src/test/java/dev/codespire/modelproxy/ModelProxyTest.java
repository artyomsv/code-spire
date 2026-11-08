package dev.codespire.modelproxy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The proxy in-process, in front of a JDK {@link HttpServer} playing the vendor on loopback. Every
 * assertion is on what the vendor received or what the agent got back, never on the proxy's internals.
 */
class ModelProxyTest {

    private static final String REFUSED_CREDENTIAL_BODY =
            "{\"error\":{\"message\":\"spire-model: the vendor refused the credential\"}}";

    /** What the upstream does once it has recorded the request; replaced per test. */
    private interface Behaviour {
        void respond(HttpExchange exchange) throws IOException;
    }

    private HttpServer upstream;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile Map<String, List<String>> seenHeaders;
    private volatile String seenQuery;
    private volatile byte[] seenBody;
    private volatile Behaviour behaviour;

    private final List<ModelProxy> proxies = new ArrayList<>();
    private final ByteArrayOutputStream logBytes = new ByteArrayOutputStream();
    private final PrintStream log = new PrintStream(logBytes, true, StandardCharsets.UTF_8);
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();

    @BeforeEach
    void startUpstream() throws IOException {
        behaviour = exchange -> respond(exchange, 200, "TEST-ok");
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        upstream.createContext("/", exchange -> {
            hits.incrementAndGet();
            Map<String, List<String>> headers = new HashMap<>();
            exchange.getRequestHeaders().forEach((name, values) ->
                    headers.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).addAll(values));
            seenHeaders = headers;
            seenQuery = exchange.getRequestURI().getRawQuery();
            seenBody = exchange.getRequestBody().readAllBytes();
            behaviour.respond(exchange);
        });
        upstream.start();
    }

    @AfterEach
    void stopAll() {
        proxies.forEach(ModelProxy::stop);
        upstream.stop(0);
    }

    // --- credentials -------------------------------------------------------------------------

    @Test
    void replacesWhateverCredentialTheAgentSent() throws Exception {
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()), log);

        HttpResponse<String> response = send(HttpRequest.newBuilder(at(proxy, "/v1/models"))
                .header("Authorization", "Bearer TEST-PLACEHOLDER")
                .header("Cookie", "TEST-cookie=1")
                .GET());

        assertEquals(200, response.statusCode());
        assertEquals(List.of("Bearer TEST-secret-key"), seenHeaders.get("authorization"));
        assertNull(seenHeaders.get("cookie"));
    }

    @Test
    void aSeatSendsTheAccessTokenAndTheAccountId() throws Exception {
        ModelProxy proxy = start(seatRoute(upstreamOrigin()), log);

        HttpResponse<String> response = send(HttpRequest.newBuilder(at(proxy, "/backend-api/codex/models"))
                .header("Authorization", "Bearer TEST-PLACEHOLDER")
                .header("chatgpt-account-id", "TEST-PLACEHOLDER-account")
                .GET());

        assertEquals(200, response.statusCode());
        assertEquals(List.of("Bearer TEST-access"), seenHeaders.get("authorization"));
        assertEquals(List.of("TEST-account"), seenHeaders.get("chatgpt-account-id"));
    }

    @Test
    void anXApiKeyRouteSendsNoAuthorization() throws Exception {
        Map<String, String> env = apiKeyRoute(upstreamOrigin());
        env.put("SPIRE_MODEL_INJECTION", "X_API_KEY");
        env.put("SPIRE_MODEL_PATHS_EXACT", "/v1/messages");
        ModelProxy proxy = start(env, log);

        HttpResponse<String> response = send(HttpRequest.newBuilder(at(proxy, "/v1/messages"))
                .header("Authorization", "Bearer TEST-PLACEHOLDER")
                .header("x-api-key", "TEST-PLACEHOLDER")
                .POST(HttpRequest.BodyPublishers.ofString("{}")));

        assertEquals(200, response.statusCode());
        assertEquals(List.of("TEST-secret-key"), seenHeaders.get("x-api-key"));
        assertNull(seenHeaders.get("authorization"));
    }

    // --- paths -------------------------------------------------------------------------------

    @Test
    void anUndeclaredPathIsRefusedWithoutReachingTheVendor() throws Exception {
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()), log);

        HttpResponse<String> response = send(HttpRequest.newBuilder(at(proxy, "/v1/files")).GET());

        assertEquals(403, response.statusCode());
        assertEquals(0, hits.get());
        assertTrue(logged().contains("spire-model GET /v1/files -> refused"), logged());
    }

    @Test
    void aDotSegmentCannotEscapeAnAllowedPath() throws Exception {
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()), log);

        for (String target : List.of("/v1/responses/../files", "/v1/responses%2F..%2Ffiles", "/v1/%2e%2e/files")) {
            assertEquals(403, rawStatus(proxy, "GET " + target + " HTTP/1.1\r\nHost: model\r\n"), target);
        }
        // The JDK server itself may answer these two 400 before any handler runs (a leading // was
        // measured at 400 in CI on JDK 25; a backslash is a malformed URI to it). The proxy's
        // own guard refuses both too (PathGuardTest); here what matters is that neither reaches the
        // vendor, whichever layer refuses it.
        for (String target : List.of("//v1/responses", "/v1\\responses")) {
            int status = rawStatus(proxy, "GET " + target + " HTTP/1.1\r\nHost: model\r\n");
            assertTrue(status == 400 || status == 403, target + " got " + status);
        }
        assertEquals(0, hits.get());
    }

    /**
     * The discriminating half: on a prefix route every one of these starts with an allowed prefix, so
     * only the guard's check of the raw path stands between it and the vendor.
     */
    @Test
    void aDotSegmentCannotEscapeAnAllowedPrefix() throws Exception {
        ModelProxy proxy = start(seatRoute(upstreamOrigin()), log);

        for (String target : List.of("/backend-api/codex/../../v1/files", "/backend-api/codex/%2e%2e/x",
                "/backend-api/codex/%2F..%2Fx", "/backend-api/codex//x")) {
            assertEquals(403, rawStatus(proxy, "GET " + target + " HTTP/1.1\r\nHost: model\r\n"), target);
        }
        assertEquals(0, hits.get());
    }

    @Test
    void anAbsoluteFormTargetIsRefused() throws Exception {
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()), log);

        int status = rawStatus(proxy, "GET http://other.example/v1/responses HTTP/1.1\r\nHost: other.example\r\n");

        assertEquals(403, status);
        assertEquals(0, hits.get());
    }

    @Test
    void aConnectIsRefused() throws Exception {
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()), log);

        int status = rawStatus(proxy, "CONNECT api.openai.com:443 HTTP/1.1\r\nHost: api.openai.com:443\r\n");

        // Measured in CI on JDK 25: the JDK server closes the connection without an answer (-1) before
        // any handler runs; the target has no path for it to match a context with. The handler's own
        // 501 for CONNECT stays as a second line in case a later JDK lets the request through.
        assertTrue(status == -1 || status == 501, "CONNECT got " + status);
        assertEquals(0, hits.get());
    }

    @Test
    void anApiKeyRouteMatchesExactly() throws Exception {
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()), log);

        assertEquals(403, send(HttpRequest.newBuilder(at(proxy, "/v1/responses/extra")).GET()).statusCode());
        assertEquals(0, hits.get());

        HttpResponse<String> forwarded = send(HttpRequest.newBuilder(at(proxy, "/v1/responses?x=1"))
                .POST(HttpRequest.BodyPublishers.ofString("{}")));

        assertEquals(200, forwarded.statusCode());
        assertEquals("TEST-ok", forwarded.body());
        assertEquals(1, hits.get());
        assertEquals("x=1", seenQuery);
    }

    @Test
    void aWebSocketUpgradeIsRefused() throws Exception {
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()), log);

        int status = rawStatus(proxy, "GET /v1/responses HTTP/1.1\r\nHost: model\r\n"
                + "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n");

        assertEquals(501, status);
        assertEquals(0, hits.get());
        assertTrue(logged().contains("-> refused"), logged());
    }

    @Test
    void aMethodOtherThanGetOrPostIsRefused() throws Exception {
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()), log);

        HttpResponse<String> response = send(HttpRequest.newBuilder(at(proxy, "/v1/responses"))
                .method("DELETE", HttpRequest.BodyPublishers.noBody()));

        assertEquals(405, response.statusCode());
        assertEquals(0, hits.get());
    }

    // --- responses ---------------------------------------------------------------------------

    @Test
    void aStreamIsPassedOnAsItArrives() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        behaviour = exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream body = exchange.getResponseBody();
            body.write("data: TEST-1\n\n".getBytes(StandardCharsets.UTF_8));
            body.flush();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            body.write("data: TEST-2\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        };
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()), log);

        HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(at(proxy, "/v1/responses"))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> readEvent(body));
            try {
                assertEquals("data: TEST-1\n\n", first.get(5, TimeUnit.SECONDS),
                        "the first event must arrive while the vendor is still holding the stream open");
            } finally {
                release.countDown();
            }
            assertEquals("data: TEST-2\n\n", new String(body.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * A vendor that dies mid-stream must reach the agent as a broken response. Ending the chunked body
     * cleanly would hand the agent a truncated answer that reads as complete.
     */
    @Test
    void aMidStreamResetIsNotAcleanEnd() throws Exception {
        CountDownLatch firstEventRead = new CountDownLatch(1);
        try (ServerSocket vendor = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> oneEventThenReset(vendor, firstEventRead));
            ModelProxy proxy = start(apiKeyRoute("http://127.0.0.1:" + vendor.getLocalPort()), log);

            HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(at(proxy, "/v1/responses"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            try (InputStream body = response.body()) {
                assertEquals("data: TEST-1\n\n", readEvent(body));
                firstEventRead.countDown();

                assertThrows(IOException.class, body::readAllBytes,
                        "the agent must see the stream break, not a clean end of a truncated body");
            }
            assertEquals(List.of("spire-model POST /v1/responses -> aborted"), logged().lines().toList());
        }
    }

    @Test
    void aRefusedCredentialsBodyAndChallengeAreReplaced() throws Exception {
        behaviour = exchange -> {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"TEST\"");
            exchange.getResponseHeaders().set("Set-Cookie", "TEST-cookie=1");
            respond(exchange, 401, "TEST-sk-echo");
        };
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()), log);

        HttpResponse<String> response = send(HttpRequest.newBuilder(at(proxy, "/v1/responses"))
                .POST(HttpRequest.BodyPublishers.ofString("{}")));

        assertEquals(401, response.statusCode());
        assertEquals(REFUSED_CREDENTIAL_BODY, response.body());
        assertTrue(response.headers().firstValue("www-authenticate").isEmpty());
        assertTrue(response.headers().firstValue("set-cookie").isEmpty());
        assertTrue(logged().contains("spire-model POST /v1/responses -> 401"), logged());
    }

    @Test
    void aContentLengthIsKept() throws Exception {
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()), log);

        HttpResponse<String> response = send(HttpRequest.newBuilder(at(proxy, "/v1/responses"))
                .POST(HttpRequest.BodyPublishers.ofByteArray("abc".getBytes(StandardCharsets.UTF_8))));

        assertEquals(200, response.statusCode());
        assertEquals(List.of("3"), seenHeaders.get("content-length"));
        assertNull(seenHeaders.get("transfer-encoding"));
        assertEquals("abc", new String(seenBody, StandardCharsets.UTF_8));
    }

    // --- the log -----------------------------------------------------------------------------

    @Test
    void theLogNamesNoSecretNoQueryAndNoException() throws Exception {
        ModelProxy apiKey = start(apiKeyRoute(upstreamOrigin()), log);
        ModelProxy seat = start(seatRoute(upstreamOrigin()), log);

        assertEquals(200, send(HttpRequest.newBuilder(at(apiKey, "/v1/responses?key=TEST-q"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))).statusCode());
        assertEquals(200, send(HttpRequest.newBuilder(at(seat, "/backend-api/codex/responses?key=TEST-q"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))).statusCode());

        String text = logged();
        assertTrue(text.contains("spire-model POST /v1/responses -> 200"), text);
        assertTrue(text.contains("spire-model POST /backend-api/codex/responses -> 200"), text);
        for (String secret : List.of("TEST-secret-key", "TEST-access", "TEST-account", "TEST-q")) {
            assertFalse(text.contains(secret), "the log carries " + secret + ": " + text);
        }

        try (ServerSocket resetting = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> resetEveryConnection(resetting));
            ByteArrayOutputStream resetBytes = new ByteArrayOutputStream();
            PrintStream resetLog = new PrintStream(resetBytes, true, StandardCharsets.UTF_8);
            ModelProxy broken = start(apiKeyRoute("http://127.0.0.1:" + resetting.getLocalPort()), resetLog);

            HttpResponse<String> response = send(HttpRequest.newBuilder(at(broken, "/v1/responses"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")));

            assertEquals(502, response.statusCode());
            assertEquals(List.of("spire-model POST /v1/responses -> 502"),
                    resetBytes.toString(StandardCharsets.UTF_8).lines().toList(),
                    "one line with the status and nothing of the exception");
        }
    }

    // --- helpers -----------------------------------------------------------------------------

    private String upstreamOrigin() {
        return "http://127.0.0.1:" + upstream.getAddress().getPort();
    }

    private static Map<String, String> apiKeyRoute(String origin) {
        Map<String, String> env = new HashMap<>();
        env.put("SPIRE_MODEL_UPSTREAM", origin);
        env.put("SPIRE_MODEL_ALLOW_PLAINTEXT_UPSTREAM", "true");
        env.put("SPIRE_MODEL_INJECTION", "BEARER");
        env.put("SPIRE_MODEL_PATHS_EXACT", "/v1/responses,/v1/models");
        env.put("SPIRE_MODEL_SECRET", "TEST-secret-key");
        return env;
    }

    private static Map<String, String> seatRoute(String origin) {
        Map<String, String> env = new HashMap<>();
        env.put("SPIRE_MODEL_UPSTREAM", origin);
        env.put("SPIRE_MODEL_ALLOW_PLAINTEXT_UPSTREAM", "true");
        env.put("SPIRE_MODEL_INJECTION", "CHATGPT_SEAT");
        env.put("SPIRE_MODEL_PATHS_PREFIX", "/backend-api/codex/,/backend-api/wham/");
        env.put("SPIRE_MODEL_SECRET", "TEST-access");
        env.put("SPIRE_MODEL_ACCOUNT", "TEST-account");
        return env;
    }

    private ModelProxy start(Map<String, String> env, PrintStream into) throws IOException {
        ModelProxy proxy = ModelProxy.start(env, new InetSocketAddress("127.0.0.1", 0), into);
        proxies.add(proxy);
        return proxy;
    }

    private static URI at(ModelProxy proxy, String pathAndQuery) {
        return URI.create("http://127.0.0.1:" + proxy.port() + pathAndQuery);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private String logged() {
        return logBytes.toString(StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** Sends a hand-written request head, for targets an HTTP client refuses to send; returns the status,
     * or -1 when the server closed the connection without one. */
    private static int rawStatus(ModelProxy proxy, String head) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", proxy.port())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write((head + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String statusLine = reader.readLine();
            // -1: the server closed the connection without answering.
            return statusLine == null ? -1 : Integer.parseInt(statusLine.split(" ")[1]);
        }
    }

    /** Reads one server-sent event: everything up to and including the first blank line. */
    private static String readEvent(InputStream body) {
        try {
            ByteArrayOutputStream event = new ByteArrayOutputStream();
            int next;
            while ((next = body.read()) != -1) {
                event.write(next);
                String text = event.toString(StandardCharsets.UTF_8);
                if (text.endsWith("\n\n")) {
                    return text;
                }
            }
            return event.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Accepts each connection, reads the request head, and resets it (linger 0 sends an RST). */
    /**
     * A hand-written vendor: answers one connection with a chunked 200 holding one event, waits until
     * the agent has read that event, then resets the connection (linger 0 sends an RST).
     */
    private static void oneEventThenReset(ServerSocket server, CountDownLatch firstEventRead) {
        try (Socket socket = server.accept()) {
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            while (!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
                int next = in.read();
                if (next == -1) {
                    return;
                }
                head.write(next);
            }
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n"
                    + "e\r\ndata: TEST-1\n\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            firstEventRead.await(10, TimeUnit.SECONDS);
            socket.setSoLinger(true, 0);
        } catch (IOException closed) {
            // The test is over or the proxy hung up; either way there is nothing left to serve.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void resetEveryConnection(ServerSocket server) {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                socket.setSoLinger(true, 0);
                socket.getInputStream().read(new byte[1024]);
            } catch (IOException closed) {
                return;
            }
        }
    }
}
