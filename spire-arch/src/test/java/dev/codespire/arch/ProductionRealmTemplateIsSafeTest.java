package dev.codespire.arch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
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
 *
 * <p>Read with {@link MiniJson}, because this module depends on nothing but JUnit.
 */
class ProductionRealmTemplateIsSafeTest {

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
        Map<String, Object> dev = realm("realm-spire.json");
        Map<String, Object> production = realm("realm-spire-production.json");

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
        Map<String, Object> production = realm("realm-spire-production.json");

        assertFalse(production.containsKey("users"), "the template must hold no users");
        for (Map<String, Object> client : clients(production)) {
            assertFalse(client.containsKey("secret"),
                    client.get("clientId") + " carries a secret. Keycloak makes one; the operator copies it"
                            + " into the main Secret after import.");
        }
    }

    @Test
    void usesPlaceholdersAndNothingFromDev() throws IOException {
        Map<String, Object> production = realm("realm-spire-production.json");

        assertEquals(REALM, production.get("realm"), "the realm name must be the placeholder " + REALM);
        assertEquals("external", production.get("sslRequired"), "sslRequired must be external, never none");
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
        for (Map<String, Object> client : clients(realm("realm-spire-production.json"))) {
            Object id = client.get("clientId");
            assertEquals(Boolean.FALSE, client.get("publicClient"), id + " must be confidential");
            assertEquals(Boolean.TRUE, client.get("standardFlowEnabled"), id + " needs the code flow");
            assertEquals(Boolean.FALSE, client.get("directAccessGrantsEnabled"),
                    id + " must state directAccessGrantsEnabled false: the password grant is for deploy/e2e.sh"
                            + " against the dev realm only");
        }
    }

    @Test
    void redirectAndLogoutUrisAreExactHttpsPlaceholders() throws IOException {
        boolean anyPostLogout = false;
        for (Map<String, Object> client : clients(realm("realm-spire-production.json"))) {
            String id = (String) client.get("clientId");
            assertEquals(List.of("https://" + HOST + "/" + CALLBACK_PREFIX.get(id) + "/auth/callback"),
                    client.get("redirectUris"), id + " must have exactly its own https callback, no wildcard");
            assertEquals(List.of("https://" + HOST), client.get("webOrigins"),
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
        for (Map<String, Object> client : clients(realm("realm-spire-production.json"))) {
            Object id = client.get("clientId");
            assertTrue(hasOwnAudienceMapper(client), id + " needs an audience mapper naming itself on the access"
                    + " token, or login fails with \"No Audience (aud) claim present\"");
            assertTrue(asList(client.get("defaultClientScopes")).contains("roles"),
                    id + " needs the roles scope, or the operator has no roles on the access token");
        }
    }

    private static boolean hasOwnAudienceMapper(Map<String, Object> client) {
        for (Object item : asList(client.get("protocolMappers"))) {
            Map<String, Object> mapper = asMap(item);
            Map<String, Object> config = asMap(mapper.get("config"));
            if ("oidc-audience-mapper".equals(mapper.get("protocolMapper"))
                    && client.get("clientId").equals(config.get("included.client.audience"))
                    && "true".equals(config.get("access.token.claim"))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> postLogoutUris(Map<String, Object> client) {
        Object attributes = client.get("attributes");
        if (attributes == null) {
            return List.of();
        }
        Object value = asMap(attributes).get(POST_LOGOUT);
        return value == null ? List.of() : List.of(((String) value).split("##"));
    }

    private static Map<String, Object> realm(String file) throws IOException {
        Path path = RootBuild.repoRoot().resolve("deploy/keycloak").resolve(file);
        return asMap(MiniJson.parse(Files.readString(path)));
    }

    private static List<Map<String, Object>> clients(Map<String, Object> realm) {
        List<Map<String, Object>> clients = new ArrayList<>();
        for (Object client : asList(realm.get("clients"))) {
            clients.add(asMap(client));
        }
        assertFalse(clients.isEmpty(), "the realm has no clients, so every per-client check passes vacuously");
        return clients;
    }

    private static Set<String> clientIds(Map<String, Object> realm) {
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> client : clients(realm)) {
            ids.add((String) client.get("clientId"));
        }
        return ids;
    }

    private static Set<String> roleNames(Map<String, Object> realm) {
        Set<String> names = new LinkedHashSet<>();
        for (Object role : asList(asMap(realm.get("roles")).get("realm"))) {
            names.add((String) asMap(role).get("name"));
        }
        assertFalse(names.isEmpty(), "the realm has no realm roles");
        return names;
    }

    /** Every string value anywhere in the document. */
    private static List<String> strings(Object node) {
        List<String> found = new ArrayList<>();
        collectStrings(node, found);
        return found;
    }

    private static void collectStrings(Object node, List<String> found) {
        if (node instanceof String text) {
            found.add(text);
        } else if (node instanceof Map<?, ?> map) {
            map.values().forEach(value -> collectStrings(value, found));
        } else if (node instanceof List<?> list) {
            list.forEach(value -> collectStrings(value, found));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node) {
        assertTrue(node instanceof Map, "expected a JSON object, got " + node);
        return (Map<String, Object>) node;
    }

    private static List<?> asList(Object node) {
        assertTrue(node instanceof List, "expected a JSON array, got " + node);
        return (List<?>) node;
    }
}
