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

    private static final HttpClient HTTP = HttpClient.newBuilder()
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

    public static String uiBaseUrl() {
        return env("SPIRE_E2E_BASE_URL", "http://localhost:34700");
    }

    public static String keycloakBaseUrl() {
        return env("SPIRE_E2E_KEYCLOAK_URL", "http://localhost:34767");
    }

    public static String gitlabBaseUrl() {
        return env("SPIRE_E2E_GITLAB_URL", "http://localhost:34780");
    }

    public static String llmMockAdminUrl() {
        return env("SPIRE_E2E_LLM_MOCK_URL", "http://localhost:34781") + "/__admin";
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
        probes.put("gitlab (deploy/compose.e2e.yml)", gitlabBaseUrl() + "/-/readiness");
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
