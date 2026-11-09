package dev.codespire.modelproxy;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.codespire.modelproxy.ProxyRoute.Injection;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The run unit's model proxy: the agent sends plain HTTP with a placeholder credential, and this
 * process forwards each allowed request to the vendor with the run's real credential.
 *
 * <p>Its stdout is its report, one line per request: {@code spire-model <METHOD> <path> -> <status>}
 * , {@code -> refused}, or {@code -> aborted} for a response that broke after its headers were sent.
 * The line names the method, the normalized path and the status only. Never a
 * header value, a body, a query or an exception message: a message can carry the URI, and the
 * query with it. Each line is written before the agent can see the end of its response, so a reader
 * that saw the response sees the line.
 */
public final class ModelProxy {

    static final int PORT = 8080;
    private static final int CHUNK_BYTES = 8 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final String REFUSED = "refused";
    private static final String ABORTED = "aborted";
    private static final int BAD_GATEWAY = 502;

    /** Replaces a 401/403 body: OpenAI's 401 echoes part of the key it refused. */
    private static final byte[] REFUSED_CREDENTIAL_BODY =
            "{\"error\":{\"message\":\"spire-model: the vendor refused the credential\"}}"
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] REFUSED_REQUEST_BODY =
            "{\"error\":{\"message\":\"spire-model: request refused\"}}".getBytes(StandardCharsets.UTF_8);

    /** Hop-by-hop headers, headers this server sets itself, and the vendor's cookies and challenges. */
    private static final Set<String> NOT_RETURNED = Set.of("connection", "keep-alive", "proxy-connection",
            "proxy-authenticate", "te", "trailer", "transfer-encoding", "upgrade", "content-length", "date",
            "set-cookie", "www-authenticate");
    /** Describe the vendor's body, which a refused credential's reply no longer carries. */
    private static final Set<String> NOT_RETURNED_WITH_FIXED_BODY = Set.of("content-type", "content-encoding");

    /** What forwarding needs, fixed at start. */
    private record Upstream(ProxyRoute route, String secret, String account, HttpClient client) {
        @Override
        public String toString() {
            return "Upstream[route=" + route + ", secret=***, account=***]";
        }
    }

    private final Upstream upstream;
    private final PrintStream log;
    private final HttpServer server;
    private final ExecutorService executor;

    private ModelProxy(Upstream upstream, PrintStream log, HttpServer server, ExecutorService executor) {
        this.upstream = upstream;
        this.log = log;
        this.server = server;
        this.executor = executor;
    }

    public static void main(String[] args) {
        try {
            start(System.getenv(), new InetSocketAddress(PORT), System.out);
        } catch (IllegalArgumentException invalid) {
            // Every such message is this module's own and names a variable, never a value.
            System.err.println("spire-model: " + invalid.getMessage());
            System.exit(2);
        } catch (IOException cannotListen) {
            System.err.println("spire-model: cannot listen on port " + PORT);
            System.exit(2);
        }
    }

    static ModelProxy start(Map<String, String> env, InetSocketAddress address, PrintStream log) throws IOException {
        ProxyRoute route = ProxyRoute.fromEnvironment(env);
        String account = route.injection() == Injection.CHATGPT_SEAT
                ? ProxyRoute.required(env, ProxyRoute.ACCOUNT)
                : null;
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(CONNECT_TIMEOUT)
                .proxy(EgressProxy.selector(env))
                .sslContext(TrustBundle.sslContext(env))
                .build();
        Upstream upstream = new Upstream(route, ProxyRoute.required(env, ProxyRoute.SECRET), account, client);

        HttpServer server = HttpServer.create(address, 0);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        ModelProxy proxy = new ModelProxy(upstream, log, server, executor);
        server.createContext("/", proxy::handle);
        server.start();
        return proxy;
    }

    int port() {
        return server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) {
        RequestLine line = new RequestLine(exchange, log);
        boolean headersSent = false;
        try {
            OptionalInt refusal = refusal(exchange);
            Optional<String> path = refusal.isPresent()
                    ? Optional.empty()
                    : PathGuard.allowed(exchange.getRequestURI().getRawPath(), upstream.route());
            if (path.isEmpty()) {
                line.write(REFUSED);
                reply(exchange, refusal.orElse(403), REFUSED_REQUEST_BODY);
                return;
            }
            line.normalizedPath(path.get());
            forward(exchange, line);
        } catch (IOException | RuntimeException failed) {
            // Deliberately unlogged: an exception message can carry the URI, and the query with it.
            headersSent = exchange.getResponseCode() != -1;
            if (headersSent) {
                abort(line);
            }
            badGateway(exchange, line);
        } finally {
            if (!headersSent) {
                exchange.close();
            }
        }
    }

    /**
     * Ends a response whose headers were already sent, without ending it cleanly.
     *
     * <p>Closing the exchange would write the chunked body's terminating chunk, and the agent would
     * read a truncated answer as a complete one. Leaving it unclosed and throwing out of the handler
     * makes the JDK server drop the connection instead, so the agent sees the stream break. The
     * exception carries no message and no stack trace; the server logs it at TRACE only.
     */
    private static void abort(RequestLine line) {
        line.write(ABORTED);
        throw new AbortedResponse();
    }

    /** Thrown out of the handler to make the server drop the connection; see {@link #abort}. */
    private static final class AbortedResponse extends RuntimeException {

        AbortedResponse() {
            super(null, null, false, false);
        }
    }

    /** The status for a request refused before its path is even looked at, if it is one. */
    private static OptionalInt refusal(HttpExchange exchange) {
        String method = exchange.getRequestMethod();
        URI target = exchange.getRequestURI();
        if (method.equals("CONNECT") || exchange.getRequestHeaders().containsKey("Upgrade")) {
            return OptionalInt.of(501);
        }
        if (target.getScheme() != null || target.getRawAuthority() != null) {
            return OptionalInt.of(403);
        }
        // TRACE in particular: the vendor would echo the injected credential back to the agent.
        if (!method.equals("GET") && !method.equals("POST")) {
            return OptionalInt.of(405);
        }
        return OptionalInt.empty();
    }

    private void forward(HttpExchange exchange, RequestLine line) throws IOException {
        HttpResponse<InputStream> response;
        try {
            response = upstream.client()
                    .send(request(exchange, line.path()), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException();
        }
        int status = response.statusCode();
        try (InputStream body = response.body()) {
            if (status == 401 || status == 403) {
                copyHeaders(response, exchange, NOT_RETURNED_WITH_FIXED_BODY);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                line.write(String.valueOf(status));
                exchange.sendResponseHeaders(status, REFUSED_CREDENTIAL_BODY.length);
                exchange.getResponseBody().write(REFUSED_CREDENTIAL_BODY);
                return;
            }
            copyHeaders(response, exchange, Set.of());
            stream(exchange, status, body, line);
        }
    }

    private HttpRequest request(HttpExchange exchange, String path) {
        String query = exchange.getRequestURI().getRawQuery();
        URI target = URI.create(upstream.route().upstream() + path + (query == null ? "" : "?" + query));
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(target).method(exchange.getRequestMethod(), body(exchange));
        Injector.outgoing(exchange.getRequestHeaders(), upstream.route().injection(), upstream.secret(),
                        upstream.account())
                .forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        return builder.build();
    }

    /** The agent's body with its length when it declared one, so the vendor sees no chunked request. */
    private static BodyPublisher body(HttpExchange exchange) {
        Headers headers = exchange.getRequestHeaders();
        String length = headers.getFirst("Content-Length");
        if (length != null) {
            long bytes = Long.parseLong(length.strip());
            return bytes == 0 ? BodyPublishers.noBody()
                    : BodyPublishers.fromPublisher(BodyPublishers.ofInputStream(exchange::getRequestBody), bytes);
        }
        if (headers.containsKey("Transfer-Encoding")) {
            return BodyPublishers.ofInputStream(exchange::getRequestBody);
        }
        return BodyPublishers.noBody();
    }

    /** Passes the body on as it arrives: one write and one flush per read, so a stream is not held. */
    private static void stream(HttpExchange exchange, int status, InputStream body, RequestLine line)
            throws IOException {
        if (status == 204 || status == 304) {
            line.write(String.valueOf(status));
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, 0);
        OutputStream out = exchange.getResponseBody();
        byte[] chunk = new byte[CHUNK_BYTES];
        for (int read = body.read(chunk); read != -1; read = body.read(chunk)) {
            out.write(chunk, 0, read);
            out.flush();
        }
        // Before the close that ends the chunked body, so the line precedes the response's end.
        line.write(String.valueOf(status));
    }

    private static void copyHeaders(HttpResponse<?> response, HttpExchange exchange, Set<String> alsoDropped) {
        Map<String, List<String>> incoming = response.headers().map();
        Set<String> namedByConnection = Injector.namedByConnection(incoming);
        Headers outgoing = exchange.getResponseHeaders();
        incoming.forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.startsWith(":") || NOT_RETURNED.contains(lower) || alsoDropped.contains(lower)
                    || namedByConnection.contains(lower)) {
                return;
            }
            outgoing.put(lower, new ArrayList<>(values));
        });
    }

    private static void reply(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    /** A 502 for a request that failed before any of its response was sent. */
    private static void badGateway(HttpExchange exchange, RequestLine line) {
        line.write(String.valueOf(BAD_GATEWAY));
        try {
            exchange.getResponseHeaders().clear();
            exchange.sendResponseHeaders(BAD_GATEWAY, -1);
        } catch (IOException agentGone) {
            // The agent hung up; there is no one left to answer, and the line already says 502.
        }
    }
}
