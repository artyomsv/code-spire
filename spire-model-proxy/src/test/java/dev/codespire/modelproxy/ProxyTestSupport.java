package dev.codespire.modelproxy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
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
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The proxy in-process, in front of a JDK {@link HttpServer} playing the vendor on loopback. Every assertion a subclass
 * makes is on what the vendor received or what the agent got back, never on the proxy's internals.
 */
abstract class ProxyTestSupport {

    static final String API_SECRET = "TEST-secret-key";
    static final String SEAT_SECRET = "TEST-access";
    static final String SEAT_ACCOUNT = "TEST-account";

    /** What the agent gets in place of an echoed credential. */
    static final String WITHHELD = "spire-model-withheld";

    static final String REFUSED_CREDENTIAL_BODY =
            "{\"error\":{\"message\":\"spire-model: the vendor refused the credential\"}}";

    /** The headers the JDK server sets itself on every answer: framing and the date. */
    private static final Set<String> FRAMING = Set.of("date", "content-length", "transfer-encoding");

    /** What the upstream does once it has recorded the request; replaced per test. */
    interface Behaviour {
        void respond(HttpExchange exchange) throws IOException;
    }

    HttpServer upstream;
    final AtomicInteger hits = new AtomicInteger();
    volatile Map<String, List<String>> seenHeaders;
    volatile String seenPath;
    volatile String seenQuery;
    volatile byte[] seenBody;
    volatile Behaviour behaviour;

    private final List<ModelProxy> proxies = new ArrayList<>();
    private final ByteArrayOutputStream logBytes = new ByteArrayOutputStream();
    final PrintStream log = new PrintStream(logBytes, true, StandardCharsets.UTF_8);
    final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();

    @BeforeEach
    void startUpstream() throws IOException {
        behaviour = exchange -> respond(exchange, 200, "TEST-ok");
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        upstream.createContext("/", exchange -> {
            hits.incrementAndGet();
            seenHeaders = lowerCased(exchange.getRequestHeaders());
            seenPath = exchange.getRequestURI().getRawPath();
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

    String upstreamOrigin() {
        return "http://127.0.0.1:" + upstream.getAddress().getPort();
    }

    static Map<String, String> apiKeyRoute(String origin) {
        Map<String, String> env = new HashMap<>();
        env.put("SPIRE_MODEL_UPSTREAM", origin);
        env.put("SPIRE_MODEL_ALLOW_PLAINTEXT_UPSTREAM", "true");
        env.put("SPIRE_MODEL_INJECTION", "BEARER");
        env.put("SPIRE_MODEL_PATHS_EXACT", "/v1/responses,/v1/models");
        env.put("SPIRE_MODEL_SECRET", API_SECRET);
        return env;
    }

    /** Codex's seat route: the model call by its exact path, everything else under the two prefixes. */
    static Map<String, String> seatRoute(String origin) {
        Map<String, String> env = new HashMap<>();
        env.put("SPIRE_MODEL_UPSTREAM", origin);
        env.put("SPIRE_MODEL_ALLOW_PLAINTEXT_UPSTREAM", "true");
        env.put("SPIRE_MODEL_INJECTION", "CHATGPT_SEAT");
        env.put("SPIRE_MODEL_PATHS_EXACT", "/backend-api/codex/responses");
        env.put("SPIRE_MODEL_PATHS_PREFIX", "/backend-api/codex/,/backend-api/wham/");
        env.put("SPIRE_MODEL_SECRET", SEAT_SECRET);
        env.put("SPIRE_MODEL_ACCOUNT", SEAT_ACCOUNT);
        return env;
    }

    ModelProxy start(Map<String, String> env) throws IOException {
        return start(ProxyStart.of(env, new InetSocketAddress("127.0.0.1", 0), log));
    }

    ModelProxy start(Map<String, String> env, Limits limits) throws IOException {
        return start(ProxyStart.of(env, new InetSocketAddress("127.0.0.1", 0), log).withLimits(limits));
    }

    ModelProxy start(ProxyStart start) throws IOException {
        ModelProxy proxy = ModelProxy.start(start);
        proxies.add(proxy);
        return proxy;
    }

    static URI at(ModelProxy proxy, String pathAndQuery) {
        return URI.create("http://127.0.0.1:" + proxy.port() + pathAndQuery);
    }

    HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(ModelProxy proxy, String path) throws Exception {
        return send(HttpRequest.newBuilder(at(proxy, path)).POST(HttpRequest.BodyPublishers.ofString("{}")));
    }

    String logged() {
        return logBytes.toString(StandardCharsets.UTF_8);
    }

    List<String> loggedLines() {
        return logged().lines().toList();
    }

    static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    /** The header names the agent sees, lower-cased, without the framing and date the JDK server adds itself. */
    static Set<String> visibleHeaders(HttpResponse<?> response) {
        Set<String> names = new TreeSet<>();
        response.headers().map().keySet().forEach(name -> names.add(name.toLowerCase(Locale.ROOT)));
        names.removeAll(FRAMING);
        return names;
    }

    private static Map<String, List<String>> lowerCased(Map<String, List<String>> headers) {
        Map<String, List<String>> lower = new HashMap<>();
        headers.forEach((name, values) ->
                lower.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).addAll(values));
        return lower;
    }
}
