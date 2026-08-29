package dev.codespire.e2e.spire;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.e2e.support.Json;
import dev.codespire.e2e.support.Stack;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Our own REST surface, reached the way a browser reaches it: through the dashboard's nginx, on one
 * origin.
 *
 * <p>TWO tokens, not one. ADR-022 makes each service its own OIDC client so a session minted for one
 * cannot be replayed against another — deploy/e2e.sh asserts exactly that boundary holds — and the
 * orchestrator answers under {@code /api} while the gateway answers under {@code /gw}. A single token
 * would work for every call here except webhook registration, and would fail there with a 401 that
 * looks like a credential problem rather than a design one.
 */
public final class SpireDriver {

    /** The realm's own operator (deploy/keycloak/realm-spire.json). Holds spire-admin. */
    private static final String OPERATOR = "dev-operator";

    private final String orchestratorToken;

    private final String gatewayToken;

    public SpireDriver() {
        this.orchestratorToken = mintToken("spire-orchestrator", required("SPIRE_OIDC_ORCHESTRATOR_SECRET"));
        this.gatewayToken = mintToken("spire-gateway", required("SPIRE_OIDC_GATEWAY_SECRET"));
    }

    public String operatorToken() {
        return orchestratorToken;
    }

    private static String mintToken(String clientId, String clientSecret) {
        String form = "grant_type=password"
                + "&client_id=" + enc(clientId)
                + "&client_secret=" + enc(clientSecret)
                + "&username=" + enc(OPERATOR)
                + "&password=" + enc(required("DEV_OPERATOR_PASSWORD"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        Stack.keycloakBaseUrl() + "/realms/spire/protocol/openid-connect/token"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try {
            HttpResponse<String> response =
                    Stack.http().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Keycloak refused the password grant for " + clientId
                        + " (" + response.statusCode() + "): " + response.body());
            }
            return Json.read(response.body()).get("access_token").asText();
        } catch (IOException e) {
            throw new IllegalStateException("could not reach Keycloak at " + Stack.keycloakBaseUrl(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted minting a token", e);
        }
    }

    // --- registration ----------------------------------------------------------------------------

    public String registerScmProvider(String name, String baseUrl, String workspace, String apiToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("type", "gitlab");
        body.put("baseUrl", baseUrl);
        body.put("workspace", workspace);
        body.put("authKind", "bearer");
        body.put("secret", apiToken);
        body.put("enabled", true);
        // Empty means everyone. An allowlist step would otherwise have to name the fixture users, and
        // IntegrationSaga treats an empty list as "review every author".
        body.put("authors", List.of());
        return post("/api/providers", body).get("id").asText();
    }

    /**
     * Synchronously validates the key with {@code GET {baseUrl}/models} (LlmKeyValidator), so llm-mock
     * must already be stubbing {@code /v1/models} before this runs or the call answers 400 and the
     * whole setup phase dies here.
     */
    public String registerLlmProvider(String name, String baseUrl, String model) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("type", "openai");
        body.put("baseUrl", baseUrl);
        body.put("apiKey", "TEST-mock-key");
        body.put("model", model);
        body.put("enabled", true);
        body.put("isDefault", true);
        return post("/api/llm-providers", body).get("id").asText();
    }

    /**
     * UNMETERED, never a rate.
     *
     * <p>ADR-023's pre-spend guard refuses an unpriceable model, so the model must be catalogued
     * before any review can run. Inventing a price for a mock would put a fabricated number into the
     * one table this project keeps precisely so money is never guessed; UNMETERED says "this model is
     * free", which is true.
     */
    public String catalogueUnmeteredModel(String name, String label) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "openai");
        body.put("name", name);
        body.put("label", label);
        body.put("pricingMode", "UNMETERED");
        body.put("rates", Map.of());
        body.put("enabled", true);
        return post("/api/llm-models", body).get("id").asText();
    }

    /** The routing key and the one-time secret. Both are needed to create the hook in GitLab. */
    public record Webhook(String key, String secret) {
    }

    public Webhook registerWebhook(String providerType, String target) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("providerType", providerType);
        body.put("scope", "repo");
        body.put("target", target);
        body.put("enabled", true);

        // The gateway's own prefix and the gateway's own token.
        JsonNode created = send(request("/gw/webhook-repos", gatewayToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))));

        return new Webhook(
                created.get("repo").get("webhookKey").asText(),
                created.get("secret").asText());
    }

    public void setReviewMode(String mode) {
        put("/api/settings/review-mode", Map.of("mode", mode));
    }

    // --- reads -----------------------------------------------------------------------------------

    public JsonNode reviewSummary(String workspace, String slug, long pr) {
        return get("/api/reviews/" + enc(workspace) + "/" + enc(slug) + "/" + pr);
    }

    // --- transport -------------------------------------------------------------------------------

    public JsonNode get(String path) {
        return send(request(path, tokenFor(path)).GET());
    }

    private JsonNode post(String path, Object body) {
        return send(request(path, tokenFor(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))));
    }

    private JsonNode put(String path, Object body) {
        return send(request(path, tokenFor(path)).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(Json.write(body))));
    }

    /** The URL prefix decides the audience, because ADR-022 made the prefix the isolation boundary. */
    private String tokenFor(String path) {
        return path.startsWith("/gw/") ? gatewayToken : orchestratorToken;
    }

    private HttpRequest.Builder request(String path, String token) {
        return HttpRequest.newBuilder(URI.create(Stack.uiBaseUrl() + path))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token);
    }

    private JsonNode send(HttpRequest.Builder builder) {
        HttpRequest built = builder.build();
        try {
            HttpResponse<String> response =
                    Stack.http().send(built, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("spire " + response.statusCode() + " for "
                        + built.method() + " " + built.uri() + ": " + response.body());
            }
            return response.body().isBlank() ? Json.read("{}") : Json.read(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("spire request failed: " + built.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted calling spire", e);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is unset. Source deploy/.env before running: "
                    + "`set -a; . deploy/.env; set +a`");
        }
        return value;
    }
}
