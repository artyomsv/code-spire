package dev.codespire.modelproxy;

import dev.codespire.modelproxy.ProxyRoute.Injection;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The route is the proxy's whole configuration, read from its environment once at start. Every
 * refusal names the variable and never its value: the value of a neighbouring variable is the
 * credential, and a start failure is printed where {@code docker logs} shows it.
 */
class ProxyRouteTest {

    private static Map<String, String> apiKey() {
        Map<String, String> env = new HashMap<>();
        env.put("SPIRE_MODEL_UPSTREAM", "https://api.openai.com");
        env.put("SPIRE_MODEL_INJECTION", "BEARER");
        env.put("SPIRE_MODEL_PATHS_EXACT", "/v1/responses,/v1/models");
        env.put("SPIRE_MODEL_SECRET", "TEST-secret-key");
        return env;
    }

    private static Map<String, String> seat() {
        Map<String, String> env = new HashMap<>();
        env.put("SPIRE_MODEL_UPSTREAM", "https://chatgpt.com");
        env.put("SPIRE_MODEL_INJECTION", "CHATGPT_SEAT");
        env.put("SPIRE_MODEL_PATHS_PREFIX", "/backend-api/codex/, /backend-api/wham/");
        env.put("SPIRE_MODEL_SECRET", "TEST-access");
        env.put("SPIRE_MODEL_ACCOUNT", "TEST-account");
        return env;
    }

    private static void refusedNaming(Map<String, String> env, String variable, String... absent) {
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> ProxyRoute.fromEnvironment(env));
        assertTrue(refused.getMessage().contains(variable),
                "the refusal must name " + variable + ": " + refused.getMessage());
        for (String value : absent) {
            assertFalse(refused.getMessage().contains(value),
                    "the refusal must not print a value: " + refused.getMessage());
        }
        assertFalse(refused.getMessage().contains("TEST-secret-key"), refused.getMessage());
        assertFalse(refused.getMessage().contains("TEST-access"), refused.getMessage());
    }

    @Test
    void anApiKeyRouteIsRead() {
        ProxyRoute route = ProxyRoute.fromEnvironment(apiKey());

        assertEquals(URI.create("https://api.openai.com"), route.upstream());
        assertEquals(Injection.BEARER, route.injection());
        assertEquals(Set.of("/v1/responses", "/v1/models"), route.exactPaths());
        assertEquals(List.of(), route.pathPrefixes());
    }

    @Test
    void aSeatRouteIsRead() {
        ProxyRoute route = ProxyRoute.fromEnvironment(seat());

        assertEquals(URI.create("https://chatgpt.com"), route.upstream());
        assertEquals(Injection.CHATGPT_SEAT, route.injection());
        assertEquals(Set.of(), route.exactPaths());
        assertEquals(List.of("/backend-api/codex/", "/backend-api/wham/"), route.pathPrefixes());
    }

    @Test
    void eachMissingVariableIsNamed() {
        for (String variable : List.of("SPIRE_MODEL_UPSTREAM", "SPIRE_MODEL_INJECTION", "SPIRE_MODEL_SECRET")) {
            Map<String, String> env = apiKey();
            env.remove(variable);
            refusedNaming(env, variable);
        }
    }

    @Test
    void eachBlankVariableIsNamed() {
        for (String variable : List.of("SPIRE_MODEL_UPSTREAM", "SPIRE_MODEL_INJECTION", "SPIRE_MODEL_SECRET")) {
            Map<String, String> env = apiKey();
            env.put(variable, "  ");
            refusedNaming(env, variable);
        }
    }

    @Test
    void anUnknownInjectionIsNamedWithoutItsValue() {
        Map<String, String> env = apiKey();
        env.put("SPIRE_MODEL_INJECTION", "TEST-BOGUS");

        refusedNaming(env, "SPIRE_MODEL_INJECTION", "TEST-BOGUS");
    }

    @Test
    void aSeatNeedsItsAccount() {
        Map<String, String> missing = seat();
        missing.remove("SPIRE_MODEL_ACCOUNT");
        refusedNaming(missing, "SPIRE_MODEL_ACCOUNT");

        Map<String, String> blank = seat();
        blank.put("SPIRE_MODEL_ACCOUNT", "");
        refusedNaming(blank, "SPIRE_MODEL_ACCOUNT");
    }

    @Test
    void aPlaintextUpstreamNeedsTheTestFlag() {
        Map<String, String> env = apiKey();
        env.put("SPIRE_MODEL_UPSTREAM", "http://TEST-host.example");
        refusedNaming(env, "SPIRE_MODEL_UPSTREAM", "TEST-host");

        env.put("SPIRE_MODEL_ALLOW_PLAINTEXT_UPSTREAM", "false");
        refusedNaming(env, "SPIRE_MODEL_UPSTREAM", "TEST-host");

        env.put("SPIRE_MODEL_ALLOW_PLAINTEXT_UPSTREAM", "true");
        assertEquals(URI.create("http://TEST-host.example"), ProxyRoute.fromEnvironment(env).upstream());
    }

    @Test
    void anUpstreamIsAnOriginOnly() {
        for (String upstream : List.of("https://TEST-host.example/v1", "https://TEST-host.example?x=1",
                "https://TEST-user@TEST-host.example", "ftp://TEST-host.example", "TEST-host.example")) {
            Map<String, String> env = apiKey();
            env.put("SPIRE_MODEL_UPSTREAM", upstream);
            refusedNaming(env, "SPIRE_MODEL_UPSTREAM", "TEST-host", "TEST-user");
        }
    }

    @Test
    void emptyPathListsAreRefused() {
        Map<String, String> env = apiKey();
        env.put("SPIRE_MODEL_PATHS_EXACT", " , ");
        env.put("SPIRE_MODEL_PATHS_PREFIX", "");

        refusedNaming(env, "SPIRE_MODEL_PATHS_EXACT");
        refusedNaming(env, "SPIRE_MODEL_PATHS_PREFIX");

        env.remove("SPIRE_MODEL_PATHS_EXACT");
        env.remove("SPIRE_MODEL_PATHS_PREFIX");
        refusedNaming(env, "SPIRE_MODEL_PATHS_EXACT");
    }

    @Test
    void aPathThatIsNotAbsoluteIsRefused() {
        Map<String, String> env = apiKey();
        env.put("SPIRE_MODEL_PATHS_EXACT", "v1/responses");

        refusedNaming(env, "SPIRE_MODEL_PATHS_EXACT");
    }
}
