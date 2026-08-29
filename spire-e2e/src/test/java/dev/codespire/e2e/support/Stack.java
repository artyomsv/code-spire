package dev.codespire.e2e.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where the running stack is, and whether it is actually there.
 *
 * <p>This module starts nothing. GitLab CE takes around five minutes to boot, so a harness that owned
 * the lifecycle would charge that to every local iteration; the stack is brought up once by hand or by
 * CI and re-run against many times. The cost of that choice is exactly this class: the failure when it
 * is NOT up has to be unmistakable, or the first symptom is a scenario timing out for the wrong reason.
 */
public final class Stack {

    /**
     * Pinned to HTTP/1.1 on purpose.
     *
     * <p>Java's HttpClient defaults to HTTP/2 and, on a plaintext origin, opens with an h2c upgrade.
     * The dashboard's nginx does not answer one, and the request then hangs until its own timeout
     * rather than failing — so every call through the proxy took exactly 60 seconds and surfaced as a
     * generic I/O error, while the identical request from curl returned in 60 milliseconds. WireMock
     * (Jetty) negotiates h2c happily, which is why only the proxied calls were affected and the mock's
     * own tests passed throughout.
     */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final String START_COMMAND =
            "docker compose -f deploy/compose.yml -f deploy/compose.e2e.yml "
                    + "--env-file deploy/.env up -d --build";

    private Stack() {
    }

    public static HttpClient http() {
        return HTTP;
    }

    /**
     * The 348xx block, not the packaged stack's 347xx.
     *
     * <p>deploy/compose.e2e.yml declares its own compose project so it never adopts a packaged stack a
     * developer already has running — and two projects cannot bind the same host port, so the whole
     * block moves with the name. Pointing this suite at 34700 would run it against that other stack,
     * which is exactly the confusion the separate project exists to prevent.
     */
    public static String uiBaseUrl() {
        return env("SPIRE_E2E_BASE_URL", "http://localhost:34800");
    }

    public static String keycloakBaseUrl() {
        return env("SPIRE_E2E_KEYCLOAK_URL", "http://localhost:34867");
    }

    public static String gitlabBaseUrl() {
        return env("SPIRE_E2E_GITLAB_URL", "http://localhost:34880");
    }

    public static String llmMockAdminUrl() {
        return env("SPIRE_E2E_LLM_MOCK_URL", "http://localhost:34881") + "/__admin";
    }

    /**
     * Probes each service's cheapest public endpoint.
     *
     * <p>Names every one that is down, not just the first: a half-started stack is the common case,
     * and reporting one at a time turns one fix into four runs.
     */
    public static void requireUp() {
        Map<String, String> probes = new LinkedHashMap<>();
        probes.put("dashboard (deploy/compose.yml, service 'ui')", uiBaseUrl() + "/healthz");
        // /api/me is one of ADR-022's three explicitly public paths, so this proves the orchestrator
        // answers THROUGH nginx without needing a token — which is the thing that actually breaks.
        probes.put("orchestrator API", uiBaseUrl() + "/api/me");
        probes.put("keycloak", keycloakBaseUrl() + "/realms/spire/.well-known/openid-configuration");
        // NOT /-/readiness. GitLab restricts its health endpoints to monitoring_whitelist, which
        // defaults to 127.0.0.0/8 — so from the host they answer 404 whether the app is up or not,
        // and a probe that cannot distinguish those two states is worse than no probe. The compose
        // healthcheck still uses /-/readiness because it runs INSIDE the container, where it is 200.
        // /users/sign_in returning 200 means Rails is serving requests, which is what the harness
        // actually needs to know.
        probes.put("gitlab (deploy/compose.e2e.yml)", gitlabBaseUrl() + "/users/sign_in");
        probes.put("llm-mock (deploy/compose.e2e.yml)", llmMockAdminUrl() + "/mappings");

        StringBuilder down = new StringBuilder();
        for (Map.Entry<String, String> probe : probes.entrySet()) {
            String failure = check(probe.getValue());
            if (failure != null) {
                down.append("\n  - ").append(probe.getKey()).append(" — ").append(failure);
            }
        }
        if (!down.isEmpty()) {
            throw new IllegalStateException("The e2e stack is not up." + down
                    + "\n\nStart it with:\n  " + START_COMMAND
                    + "\n\nGitLab takes around five minutes to become ready after that returns.");
        }
    }

    /** @return null when the endpoint answered, else a short description of what went wrong. */
    private static String check(String url) {
        try {
            HttpResponse<Void> response = HTTP.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return response.statusCode() < 500 ? null : "HTTP " + response.statusCode() + " from " + url;
        } catch (IOException e) {
            return "unreachable at " + url + " (" + e.getMessage() + ")";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted probing " + url, e);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
