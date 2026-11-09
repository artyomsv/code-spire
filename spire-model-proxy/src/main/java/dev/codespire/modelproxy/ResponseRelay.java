package dev.codespire.modelproxy;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Hands the vendor's response to the agent without the credential, failing closed:
 *
 * <ul>
 *   <li>a 401 or 403 gets a fixed body, since OpenAI's 401 quotes part of the key it refused;</li>
 *   <li>a body the vendor encoded (anything but {@code identity}, which the proxy asked for) is refused with a 502,
 *       because it cannot be searched for an echo; a 401 or 403 is not, since its body is replaced anyway and its
 *       status is what marks a refused credential;</li>
 *   <li>only {@link ResponseHeaders}' headers pass, never {@code Location};</li>
 *   <li>every other body, whatever its status, streams through an {@link EchoMask}: one write and one flush per read,
 *       with only the possible start of an echo held back, and a {@link StallGuard} on the vendor's side.</li>
 * </ul>
 */
final class ResponseRelay {

    private static final int CHUNK_BYTES = 8 * 1024;

    /** Replaces a 401/403 body: OpenAI's 401 echoes part of the key it refused. */
    private static final byte[] REFUSED_CREDENTIAL_BODY =
            "{\"error\":{\"message\":\"spire-model: the vendor refused the credential\"}}"
                    .getBytes(StandardCharsets.UTF_8);

    private final Credential credential;
    private final Limits limits;
    private final ScheduledExecutorService timer;

    ResponseRelay(Credential credential, Limits limits, ScheduledExecutorService timer) {
        this.credential = credential;
        this.limits = limits;
        this.timer = timer;
    }

    void relay(HttpResponse<InputStream> response, HttpExchange exchange, RequestLine line) throws IOException {
        int status = response.statusCode();
        try (InputStream body = response.body()) {
            if (status == 401 || status == 403) {
                refusedCredential(response, exchange, line);
            } else if (!isUnencoded(response)) {
                Replies.badGateway(exchange, line);
            } else {
                ResponseHeaders.copy(response.headers().map(), exchange.getResponseHeaders(), credential);
                stream(new Outbound(exchange, status, body), line);
            }
        }
    }

    /** One response on its way: where it goes, its status, and the vendor's body. */
    private record Outbound(HttpExchange exchange, int status, InputStream body) {
    }

    private void refusedCredential(HttpResponse<?> response, HttpExchange exchange, RequestLine line)
            throws IOException {
        ResponseHeaders.copy(response.headers().map(), exchange.getResponseHeaders(), credential);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        line.write(String.valueOf(response.statusCode()));
        exchange.sendResponseHeaders(response.statusCode(), REFUSED_CREDENTIAL_BODY.length);
        exchange.getResponseBody().write(REFUSED_CREDENTIAL_BODY);
    }

    private static boolean isUnencoded(HttpResponse<?> response) {
        return response.headers().allValues("Content-Encoding").stream()
                .allMatch(encoding -> encoding.strip().equalsIgnoreCase(Injector.IDENTITY));
    }

    private void stream(Outbound response, RequestLine line) throws IOException {
        if (response.status() == 204 || response.status() == 304) {
            line.write(String.valueOf(response.status()));
            response.exchange().sendResponseHeaders(response.status(), -1);
            return;
        }
        response.exchange().sendResponseHeaders(response.status(), 0);
        OutputStream out = response.exchange().getResponseBody();
        EchoMask mask = new EchoMask(credential.echoes());
        byte[] chunk = new byte[CHUNK_BYTES];
        try (StallGuard guard = StallGuard.watch(response.body(), limits, timer)) {
            for (int read = response.body().read(chunk); read != -1; read = response.body().read(chunk)) {
                guard.progressed();
                send(out, mask.next(chunk, read));
            }
        }
        send(out, mask.finish());
        // Before the close that ends the chunked body, so the line precedes the response's end.
        line.write(String.valueOf(response.status()));
    }

    private static void send(OutputStream out, byte[] bytes) throws IOException {
        out.write(bytes);
        out.flush();
    }
}
