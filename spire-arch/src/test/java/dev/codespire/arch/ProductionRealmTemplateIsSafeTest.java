package dev.codespire.arch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The production realm template must be the dev realm's clients and roles with every dev-only
 * convenience taken out.
 *
 * <p>{@code deploy/keycloak/realm-spire.json} is a working dev realm: two users whose passwords are
 * their names, client secrets in the file, the password grant on (only {@code deploy/e2e.sh} needs it),
 * plaintext and {@code localhost} redirect URIs, wildcard post-logout URIs, {@code sslRequired: none}.
 * An operator who copies it to a real identity provider gets all of that. The template
 * {@code realm-spire-production.json} is the file to copy instead, and this check holds it to the list
 * in docs/superpowers/specs/2026-11-15-k8s-any-cluster-design.md, section 6.
 *
 * <p>The client and role lists are compared with the dev realm rather than written here, so a client
 * the dev realm gains (and the services then need) fails this check until the template has it too. The
 * one exception is {@link #RUN_WORKER}: the template is for a reviewer-only install, and M5 adds the
 * run worker to Kubernetes.
 */
class ProductionRealmTemplateIsSafeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RUN_WORKER = "spire-run-worker";
    private static final String HOST = "SPIRE_HOST";
    private static final String REALM = "SPIRE_REALM";
    private static final String POST_LOGOUT = "post.logout.redirect.uris";

    /** Each client's callback prefix: one service, one URL prefix, one origin (ADR-022). */
    private static final Map<String, String> CALLBACK_PREFIX = Map.of(
            "spire-orchestrator", "api",
            "spire-gateway", "gw",
            "spire-review-worker", "wk");

    @Test
    void clientsAndRolesAreTheDevRealmsWithoutTheRunWorker() throws IOException {
        JsonNode dev = realm("realm-spire.json");
        JsonNode production = realm("realm-spire-production.json");

        Set<String> devClients = clientIds(dev);
        assertTrue(devClients.contains(RUN_WORKER),
                "the dev realm no longer has " + RUN_WORKER + "; drop the exception in this test");
        devClients.remove(RUN_WORKER);

        assertEquals(devClients, clientIds(production),
                "the template's clients must be the dev realm's minus " + RUN_WORKER);
        assertEquals(CALLBACK_PREFIX.keySet(), clientIds(production),
                "every template client needs a callback prefix in this test, and only those");
        assertEquals(roleNames(dev), roleNames(production), "the template's realm roles must be the dev realm's");
    }

    @Test
    void holdsNoUsersAndNoSecrets() throws IOException {
        JsonNode production = realm("realm-spire-production.json");

        assertFalse(production.has("users"), "the template must hold no users");
        for (JsonNode client : clients(production)) {
            assertFalse(client.has("secret"),
                    client.path("clientId").asText() + " carries a secret. Keycloak makes one; the operator"
                            + " copies it into the main Secret after import.");
        }
    }

    @Test
    void usesPlaceholdersAndNothingFromDev() throws IOException {
        JsonNode production = realm("realm-spire-production.json");

        assertEquals(REALM, production.path("realm").asText(), "the realm name must be the placeholder " + REALM);
        assertEquals("external", production.path("sslRequired").asText(), "sslRequired must be external, never none");
        for (String text : strings(production)) {
            String lower = text.toLowerCase(Locale.ROOT);
            assertFalse(lower.contains("http://"), "plaintext URL in the template: " + text);
            assertFalse(lower.contains("localhost"), "localhost in the template: " + text);
            assertFalse(lower.contains("(dev)"), "dev display text in the template: " + text);
        }
    }

    /** The checks above must be able to fail: the same walk finds the dev realm's dev-only values. */
    @Test
    void theStringWalkSeesTheDevRealmsPlaintextUrls() throws IOException {
        List<String> devStrings = strings(realm("realm-spire.json"));

        assertTrue(devStrings.stream().anyMatch(text -> text.contains("http://localhost")),
                "the walk found no http://localhost in the dev realm, so it reads nothing");
        assertTrue(devStrings.stream().anyMatch(text -> text.contains("(dev)")),
                "the walk found no (dev) display name in the dev realm, so it reads nothing");
    }

    @Test
    void everyClientIsConfidentialWithNoPasswordGrant() throws IOException {
        for (JsonNode client : clients(realm("realm-spire-production.json"))) {
            String id = client.path("clientId").asText();
            assertTrue(client.path("publicClient").isBoolean() && !client.path("publicClient").asBoolean(),
                    id + " must state publicClient false");
            assertTrue(client.path("standardFlowEnabled").asBoolean(), id + " needs the code flow");
            assertTrue(client.path("directAccessGrantsEnabled").isBoolean()
                            && !client.path("directAccessGrantsEnabled").asBoolean(),
                    id + " must state directAccessGrantsEnabled false: the password grant is for deploy/e2e.sh"
                            + " against the dev realm only");
        }
    }

    @Test
    void redirectAndLogoutUrisAreExactHttpsPlaceholders() throws IOException {
        boolean anyPostLogout = false;
        for (JsonNode client : clients(realm("realm-spire-production.json"))) {
            String id = client.path("clientId").asText();
            assertEquals(List.of("https://" + HOST + "/" + CALLBACK_PREFIX.get(id) + "/auth/callback"),
                    texts(client.path("redirectUris")), id + " must have exactly its own https callback, no wildcard");
            assertEquals(List.of("https://" + HOST), texts(client.path("webOrigins")),
                    id + " web origins must be the one https origin");
            for (String uri : postLogoutUris(client)) {
                anyPostLogout = true;
                assertFalse(uri.contains("*"), id + " has a wildcard post-logout URI: " + uri);
                assertTrue(uri.startsWith("https://" + HOST + "/"), id + " post-logout URI is not the origin: " + uri);
            }
        }
        assertTrue(anyPostLogout, "the orchestrator does RP-initiated logout and needs its post-logout URI");
    }

    @Test
    void everyClientPutsItsOwnAudienceAndTheRolesOnTheAccessToken() throws IOException {
        for (JsonNode client : clients(realm("realm-spire-production.json"))) {
            String id = client.path("clientId").asText();
            assertTrue(hasOwnAudienceMapper(client), id + " needs an audience mapper naming itself on the access"
                    + " token, or login fails with \"No Audience (aud) claim present\"");
            assertTrue(texts(client.path("defaultClientScopes")).contains("roles"),
                    id + " needs the roles scope, or the operator has no roles on the access token");
        }
    }

    private static boolean hasOwnAudienceMapper(JsonNode client) {
        for (JsonNode mapper : client.path("protocolMappers")) {
            JsonNode config = mapper.path("config");
            if ("oidc-audience-mapper".equals(mapper.path("protocolMapper").asText())
                    && client.path("clientId").asText().equals(config.path("included.client.audience").asText())
                    && "true".equals(config.path("access.token.claim").asText())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> postLogoutUris(JsonNode client) {
        JsonNode value = client.path("attributes").path(POST_LOGOUT);
        return value.isTextual() ? List.of(value.asText().split("##")) : List.of();
    }

    private static JsonNode realm(String file) throws IOException {
        Path path = RootBuild.repoRoot().resolve("deploy/keycloak").resolve(file);
        JsonNode realm = JSON.readTree(path.toFile());
        assertTrue(realm.isObject(), file + " is not a JSON object");
        return realm;
    }

    private static List<JsonNode> clients(JsonNode realm) {
        List<JsonNode> clients = new ArrayList<>();
        realm.path("clients").forEach(clients::add);
        assertFalse(clients.isEmpty(), "the realm has no clients, so every per-client check passes vacuously");
        return clients;
    }

    private static Set<String> clientIds(JsonNode realm) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode client : clients(realm)) {
            ids.add(client.path("clientId").asText());
        }
        return ids;
    }

    private static Set<String> roleNames(JsonNode realm) {
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode role : realm.path("roles").path("realm")) {
            names.add(role.path("name").asText());
        }
        assertFalse(names.isEmpty(), "the realm has no realm roles");
        return names;
    }

    private static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(item -> texts.add(item.asText()));
        return texts;
    }

    /** Every string value anywhere in the document. */
    private static List<String> strings(JsonNode node) {
        List<String> found = new ArrayList<>();
        collectStrings(node, found);
        return found;
    }

    private static void collectStrings(JsonNode node, List<String> found) {
        if (node.isTextual()) {
            found.add(node.asText());
            return;
        }
        node.forEach(child -> collectStrings(child, found));
    }
}
