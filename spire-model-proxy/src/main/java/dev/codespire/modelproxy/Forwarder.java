package dev.codespire.modelproxy;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Sends an allowed request to the vendor with the run's credential, through the unit's egress proxy, and returns the
 * response as soon as its headers arrive. Redirects are never followed, so the credential goes to the route's origin
 * alone.
 */
final class Forwarder {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** Where requests go and what they carry. */
    private record Destination(ProxyRoute route, Credential credential) {
    }

    private final ProxyRoute route;
    private final Credential credential;
    private final HttpClient client;
    private final Duration headersTimeout;

    private Forwarder(Destination destination, HttpClient client, Duration headersTimeout) {
        this.route = destination.route();
        this.credential = destination.credential();
        this.client = client;
        this.headersTimeout = headersTimeout;
    }

    /** The forwarder the environment describes; every refusal names a variable, never a value. */
    static Forwarder fromEnvironment(Map<String, String> env, Limits limits) {
        ProxyRoute route = ProxyRoute.fromEnvironment(env);
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(CONNECT_TIMEOUT)
                .proxy(EgressProxy.selector(env))
                .sslContext(TrustBundle.sslContext(env))
                .build();
        return new Forwarder(new Destination(route, Credential.fromEnvironment(env, route)), client,
                limits.responseHeadersTimeout());
    }

    ProxyRoute route() {
        return route;
    }

    Credential credential() {
        return credential;
    }

    /** The vendor's response to {@code path} with {@code body}; its headers must arrive within the limit. */
    HttpResponse<InputStream> send(HttpExchange exchange, String path, byte[] body) throws IOException {
        try {
            return client.send(request(exchange, path, body), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException();
        }
    }

    private HttpRequest request(HttpExchange exchange, String path, byte[] body) {
        String query = exchange.getRequestURI().getRawQuery();
        URI target = URI.create(route.upstream() + path + (query == null ? "" : "?" + query));
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(headersTimeout)
                .method(exchange.getRequestMethod(),
                        body.length == 0 ? BodyPublishers.noBody() : BodyPublishers.ofByteArray(body));
        Injector.outgoing(exchange.getRequestHeaders(), credential)
                .forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        return builder.build();
    }

    /** Never prints the credential. */
    @Override
    public String toString() {
        return "Forwarder[route=" + route + ", credential=" + credential + "]";
    }
}
