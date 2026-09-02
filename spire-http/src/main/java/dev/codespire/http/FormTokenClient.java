package dev.codespire.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The OAuth authorization-code exchange, once, for every SCM adapter that needs it.
 *
 * <p>Three platforms do the same dance with different field spellings, so the transport lives here
 * and each adapter supplies only its URLs and form fields. That keeps the security-relevant parts —
 * https only, redirects refused, a bounded body, and a client secret that never reaches a log or an
 * exception message — in one file rather than in three that drift.
 *
 * <p>Redirects are <b>refused</b>, not followed. A token endpoint has no legitimate reason to
 * redirect, and following one would carry the client secret to whatever host answered.
 */
public final class FormTokenClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    /** A token response is a handful of fields; anything larger is not one. */
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final HttpClient http;
    private final ObjectMapper mapper;

    public FormTokenClient(ObjectMapper mapper) {
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(TIMEOUT)
                .build();
        this.mapper = mapper;
    }

    /**
     * Posts {@code form} to {@code tokenUrl} and returns the {@code access_token} it answers.
     *
     * @param basicAuth {@code clientId:clientSecret} to send as HTTP Basic, or null when the
     *                  platform expects those in the form body instead
     * @param failures  the calling adapter's exception factory, so a caller still catches its own type
     */
    public String accessToken(String tokenUrl, Map<String, String> form, String basicAuth,
                              HttpFailures failures) {
        URI uri = URI.create(tokenUrl);
        if (!isTransportSafe(uri)) {
            // A client secret in a form body over plaintext is the secret published.
            throw failures.create(0, "POST", tokenUrl, "an OAuth token endpoint must be https");
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                // Without this GitHub answers form-encoded; the other two answer JSON regardless.
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", "code-spire")
                .POST(HttpRequest.BodyPublishers.ofString(encode(form)));
        if (basicAuth != null) {
            request.header("Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(basicAuth.getBytes(StandardCharsets.UTF_8)));
        }

        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw failures.create(0, "POST", tokenUrl, "token endpoint unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failures.create(0, "POST", tokenUrl, "interrupted");
        }

        // Never echo the body: an OAuth error response quotes back the parameters it rejected, and
        // on the paths that matter one of those parameters IS the client secret.
        if (response.statusCode() / 100 != 2) {
            throw failures.create(response.statusCode(), "POST", tokenUrl, null);
        }
        String body = response.body();
        if (body == null || body.length() > MAX_BODY_BYTES) {
            throw failures.create(response.statusCode(), "POST", tokenUrl, "token response was not a token");
        }
        return readToken(body, tokenUrl, response.statusCode(), failures);
    }

    /**
     * A 200 carrying {@code error} rather than {@code access_token}.
     *
     * <p>OAuth providers answer a rejected code with HTTP 200 and an error field, so a status check
     * alone passes and the caller then signs somebody in as nobody. The error CODE is safe to
     * report — it is a fixed vocabulary — while the description echoes back what was sent.
     */
    private String readToken(String body, String tokenUrl, int status, HttpFailures failures) {
        JsonNode json;
        try {
            json = mapper.readTree(body);
        } catch (IOException e) {
            throw failures.create(status, "POST", tokenUrl, "token response was not JSON");
        }
        JsonNode token = json.get("access_token");
        if (token == null || !token.isTextual() || token.asText().isBlank()) {
            JsonNode error = json.get("error");
            String reason = error != null && error.isTextual() ? error.asText() : "no access_token";
            throw failures.create(status, "POST", tokenUrl, reason);
        }
        return token.asText();
    }

    /**
     * https, or a loopback address.
     *
     * <p>The loopback exemption is not a relaxation of the rule: a request to 127.0.0.1 never leaves
     * the machine, so there is no wire for the secret to be read from. It is also what makes this
     * checkable at all — the adapters' tests answer over plain http on localhost, and a
     * configuration flag to switch the guard off in tests would be a flag that could be switched off
     * anywhere. RFC 8252 draws the same line for the same reason.
     */
    private static boolean isTransportSafe(URI uri) {
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return true;
        }
        String host = uri.getHost();
        return host != null
                && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host)
                        || "::1".equals(host));
    }

    private static String encode(Map<String, String> form) {
        Map<String, String> ordered = new LinkedHashMap<>(form);
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> field : ordered.entrySet()) {
            if (!body.isEmpty()) {
                body.append('&');
            }
            body.append(URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8));
        }
        return body.toString();
    }
}
