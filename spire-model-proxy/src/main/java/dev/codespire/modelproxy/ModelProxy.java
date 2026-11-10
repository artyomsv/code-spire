package dev.codespire.modelproxy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;

/**
 * The run unit's model proxy: the agent sends plain HTTP with a placeholder credential, and this process forwards each
 * allowed request to the vendor with the run's real credential, and hands the answer back without it.
 *
 * <p>Its stdout is its report, one line per request ({@link RequestLine}): {@code spire-model <METHOD> <route> ->
 * <outcome>}, the outcome a status, {@code refused}, or {@code aborted} for a response that broke after its headers
 * were sent or a request whose body never arrived. Never a header value, a body, a query, the agent's path or an
 * exception message: a message can carry the URI. Each line is written before the agent can see the end of its
 * response, so a reader that saw the response sees the line.
 *
 * <p>What a request may cost is {@link Limits}' and {@link ServerLimits}'; what of the answer reaches the agent is
 * {@link ResponseRelay}'s.
 */
public final class ModelProxy {

    static final int PORT = 8080;

    /** What forwarding needs, fixed at start. */
    private record Settings(Forwarder forwarder, ResponseRelay relay, Limits limits, PrintStream log) {
    }

    private final Settings settings;
    private final Semaphore admission;
    private final HttpServer server;
    private final Runnable stopping;

    private ModelProxy(Settings settings, HttpServer server, Runnable stopping) {
        this.settings = settings;
        this.admission = new Semaphore(settings.limits().maxConcurrentRequests());
        this.server = server;
        this.stopping = stopping;
    }

    public static void main(String[] args) {
        ServerLimits.apply();
        try {
            start(ProxyStart.of(System.getenv(), new InetSocketAddress(PORT), System.out));
        } catch (IllegalArgumentException invalid) {
            // Every such message is this module's own and names a variable, never a value.
            System.err.println("spire-model: " + invalid.getMessage());
            System.exit(2);
        } catch (IOException cannotListen) {
            System.err.println("spire-model: cannot listen on port " + PORT);
            System.exit(2);
        }
    }

    static ModelProxy start(ProxyStart start) throws IOException {
        Forwarder forwarder = Forwarder.fromEnvironment(start.env(), start.limits());
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("spire-model-deadlines").factory());
        ResponseRelay relay = new ResponseRelay(forwarder.credential(), start.limits(), timer);
        HttpServer server = HttpServer.create(start.address(), 0);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        Runnable stopping = () -> {
            executor.shutdownNow();
            timer.shutdownNow();
        };
        ModelProxy proxy = new ModelProxy(new Settings(forwarder, relay, start.limits(), start.log()), server, stopping);
        server.createContext("/", proxy::handle);
        server.start();
        return proxy;
    }

    int port() {
        return server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
        stopping.run();
    }

    /**
     * One request: refused at once when {@link Limits#maxConcurrentRequests()} are already in flight, otherwise
     * served. The permit is returned before the exchange is closed, since closing may wait on the rest of a body the
     * proxy refused to read, and that wait is the JDK server's to bound, not a request's.
     */
    private void handle(HttpExchange exchange) {
        RequestLine line = new RequestLine(exchange.getRequestMethod(), settings.log());
        if (!admission.tryAcquire()) {
            refuse(exchange, line, Replies.Answer.BUSY);
            exchange.close();
            return;
        }
        try {
            serve(exchange, line);
        } finally {
            admission.release();
        }
        exchange.close();
    }

    /** Serves one admitted request; throws only to drop a response whose headers were sent. */
    private void serve(HttpExchange exchange, RequestLine line) {
        try {
            forwardOrRefuse(exchange, line);
        } catch (IOException | RuntimeException failed) {
            // Deliberately unlogged: an exception message can carry the URI, and the query with it.
            if (exchange.getResponseCode() != -1) {
                throw Replies.abort(line);
            }
            Replies.badGateway(exchange, line);
        }
    }

    private void forwardOrRefuse(HttpExchange exchange, RequestLine line) throws IOException {
        OptionalInt refusal = refusal(exchange);
        Optional<String> path = refusal.isPresent()
                ? Optional.empty()
                : PathGuard.allowed(exchange.getRequestURI().getRawPath(), settings.forwarder().route());
        if (path.isEmpty()) {
            refuse(exchange, line, Replies.Answer.refused(refusal.orElse(403)));
            return;
        }
        String allowed = path.orElseThrow();
        line.route(PathGuard.logged(allowed, settings.forwarder().route()));
        Optional<byte[]> body = readBody(exchange, line);
        if (body.isPresent()) {
            settings.relay().relay(settings.forwarder().send(exchange, allowed, body.orElseThrow()), exchange, line);
        }
    }

    /**
     * The agent's body, or empty once this request is answered: 413 for a body over the cap, nothing for a connection
     * that failed while the body arrived (the JDK server closes one whose body is late), which is logged as aborted.
     */
    private Optional<byte[]> readBody(HttpExchange exchange, RequestLine line) throws IOException {
        Optional<byte[]> body;
        try {
            body = RequestBody.read(exchange.getRequestHeaders(), exchange.getRequestBody(),
                    settings.limits().maxRequestBodyBytes());
        } catch (IOException agentGone) {
            line.write(Replies.ABORTED);
            return Optional.empty();
        }
        if (body.isEmpty()) {
            refuse(exchange, line, Replies.Answer.TOO_LARGE);
        }
        return body;
    }

    private static void refuse(HttpExchange exchange, RequestLine line, Replies.Answer answer) {
        line.write(answer.outcome());
        try {
            Replies.reply(exchange, answer.status(), answer.body());
        } catch (IOException agentGone) {
            // The agent hung up; the line already says what it was answered.
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
}
