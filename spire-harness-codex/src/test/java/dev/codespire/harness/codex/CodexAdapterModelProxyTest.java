package dev.codespire.harness.codex;

import dev.codespire.harness.CredentialRoute;
import dev.codespire.harness.HarnessInvocation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Codex behind the unit's model proxy (harness reviews step 3): its command, its environment and its route. */
class CodexAdapterModelProxyTest {

    private final CodexAdapter adapter = new CodexAdapter();

    private static HarnessInvocation proxied(String credentialKey) {
        return new HarnessInvocation("run_abc", "review the change", "/workspace", "gpt-5.6",
                Map.of(credentialKey, CodexAdapter.PLACEHOLDER), Duration.ofMinutes(30)).proxied();
    }

    /**
     * A key run talks to the model proxy through a provider of its own whose key is a placeholder variable, measured
     * against codex-cli 0.156.1 in the 2026-11-07 spike: no login is needed, so none is run.
     */
    @Test
    void aProxiedApiKeyRunTalksToTheModelProxy() {
        String script = script(adapter.command(proxied(HarnessInvocation.CREDENTIAL)));

        assertTrue(script.contains(" -c 'model_provider=\"spire\"'"), script);
        assertTrue(script.contains(" -c 'model_providers.spire.base_url=\"http://spire-model:8080/v1\"'"), script);
        assertTrue(script.contains(" -c 'model_providers.spire.env_key=\"SPIRE_MODEL_PLACEHOLDER\"'"), script);
        assertTrue(script.contains(" -c 'model_providers.spire.wire_api=\"responses\"'"), script);
        assertTrue(script.contains(" -c 'model_providers.spire.name=\"spire\"'"), script);
        assertTrue(script.startsWith("exec codex exec "), script);
        assertFalse(script.contains("codex login"), script);
        assertFalse(script.contains("requires_openai_auth"), script);
        assertFalse(script.contains("chatgpt_base_url"), script);
        assertTrue(script.contains(" -c features.plugins=false -c analytics.enabled=false"), script);
        assertTrue(script.indexOf("model_provider=") < script.indexOf(" -C "), script);
    }

    /** A seat run signs in with a placeholder file and reaches the ChatGPT backend through the model proxy. */
    @Test
    void aProxiedSeatRunUsesAPlaceholderSignIn() {
        HarnessInvocation seat = proxied(HarnessInvocation.SIGN_IN);
        String script = script(adapter.command(seat));

        assertTrue(script.contains(" -c 'chatgpt_base_url=\"http://spire-model:8080/backend-api/\"'"), script);
        assertTrue(script.contains(" -c 'model_provider=\"spire\"'"), script);
        assertTrue(script.contains(
                " -c 'model_providers.spire.base_url=\"http://spire-model:8080/backend-api/codex\"'"), script);
        assertTrue(script.contains(" -c model_providers.spire.requires_openai_auth=true"), script);
        assertTrue(script.contains(" -c 'model_providers.spire.wire_api=\"responses\"'"), script);
        assertTrue(script.contains(" -c features.apps=false"), script);
        assertTrue(script.contains("umask 077 && printenv CODEX_SIGN_IN_FILE > \"$HOME/.codex/auth.json\""), script);
        assertTrue(script.contains("unset CODEX_SIGN_IN_FILE; exec codex exec"), script);
        assertFalse(script.contains("env_key"), script);
        assertFalse(script.contains("--with-api-key"), script);
        assertEquals(PlaceholderSignIn.file(), adapter.environment(seat).get("CODEX_SIGN_IN_FILE"));
    }

    /** Placeholders in, placeholders and fixed settings out: nothing in the environment is a credential. */
    @Test
    void aProxiedRunsEnvironmentHoldsOnlyPlaceholders() {
        assertEquals(Map.of("CODEX_QUIET_MODE", "1", "SPIRE_MODEL_PLACEHOLDER", "TEST-PLACEHOLDER"),
                adapter.environment(proxied(HarnessInvocation.CREDENTIAL)));
        assertEquals(Map.of("CODEX_QUIET_MODE", "1", "CODEX_SIGN_IN_FILE", PlaceholderSignIn.file()),
                adapter.environment(proxied(HarnessInvocation.SIGN_IN)));
    }

    /**
     * The paths the 2026-11-07 spike saw, and no others; an unproxied run declares no route. A seat's model call is
     * also an exact path, so the model proxy logs it by name and a 401 on it marks the seat; every other seat path is
     * logged by its prefix only.
     */
    @Test
    void theRoutePerPayment() {
        assertEquals(java.util.Optional.of(new CredentialRoute("https://api.openai.com", CredentialRoute.Injection.BEARER,
                        Set.of("/v1/responses", "/v1/models"), List.of())),
                adapter.credentialRoute(proxied(HarnessInvocation.CREDENTIAL)));
        assertEquals(java.util.Optional.of(new CredentialRoute("https://chatgpt.com",
                        CredentialRoute.Injection.CHATGPT_SEAT, Set.of("/backend-api/codex/responses"),
                        List.of("/backend-api/codex/", "/backend-api/wham/"))),
                adapter.credentialRoute(proxied(HarnessInvocation.SIGN_IN)));
        assertTrue(adapter.credentialRoute(invocation()).isEmpty());
        assertTrue(adapter.credentialRoute(signedIn()).isEmpty());
        assertEquals("http://spire-model:8080", CredentialRoute.AGENT_FACING_ORIGIN);
    }

    /** Today's argv and environment, byte for byte, for a run that is not proxied. */
    @Test
    void anUnproxiedRunIsUnchanged() {
        HarnessInvocation key = new HarnessInvocation("run_abc", "fix the bug", "/workspace", "gpt-5.6",
                Map.of(HarnessInvocation.CREDENTIAL, "TEST-key"), Duration.ofMinutes(30));
        HarnessInvocation seat = new HarnessInvocation("run_abc", "fix the bug", "/workspace", "gpt-5.6",
                Map.of(HarnessInvocation.SIGN_IN, "TEST-sign-in"), Duration.ofMinutes(30));
        String tail = "exec codex exec --json --sandbox danger-full-access --skip-git-repo-check --model 'gpt-5.6'"
                + " -c features.plugins=false -c analytics.enabled=false";

        for (HarnessInvocation plain : List.of(key, key.direct())) {
            assertEquals(List.of("sh", "-c", "printenv OPENAI_API_KEY | codex login --with-api-key >/dev/null"
                    + " || exit 120; " + tail + " -C '/workspace' -"), adapter.command(plain));
            assertEquals(Map.of("OPENAI_API_KEY", "TEST-key", "CODEX_QUIET_MODE", "1"), adapter.environment(plain));
        }
        for (HarnessInvocation plain : List.of(seat, seat.direct())) {
            assertEquals(List.of("sh", "-c", "mkdir -p \"$HOME/.codex\" && (umask 077 && printenv CODEX_SIGN_IN_FILE"
                    + " > \"$HOME/.codex/auth.json\") || exit 120; unset CODEX_SIGN_IN_FILE; " + tail
                    + " -c features.apps=false -C '/workspace' -"), adapter.command(plain));
            assertEquals(Map.of("CODEX_SIGN_IN_FILE", "TEST-sign-in", "CODEX_QUIET_MODE", "1"),
                    adapter.environment(plain));
        }
    }

    private static HarnessInvocation invocation() {
        return new HarnessInvocation("run_abc", "fix the bug", "/workspace", "gpt-5.6",
                Map.of(HarnessInvocation.CREDENTIAL, "TEST-key"), Duration.ofMinutes(30));
    }

    private static HarnessInvocation signedIn() {
        return new HarnessInvocation("run_abc", "fix the bug", "/workspace", "gpt-5.6",
                Map.of(HarnessInvocation.SIGN_IN, "{\"auth_mode\":\"TEST\"}"), Duration.ofMinutes(30));
    }

    /** The argv is a shell and its one line. */
    private static String script(List<String> argv) {
        assertEquals(List.of("sh", "-c"), argv.subList(0, 2), "the login step needs a pipe, so the argv is a shell");
        assertEquals(3, argv.size(), "one line, so there is one place to read and one to review");
        return argv.get(2);
    }
}
