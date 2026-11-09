package dev.codespire.harness.codex;

import dev.codespire.harness.CredentialRoute;
import dev.codespire.harness.EnvironmentPolicy;
import dev.codespire.harness.HarnessInvocation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What Codex needs to run behind the unit's model proxy (harness reviews step 3): the provider settings that point it
 * at the proxy, the environment it gets in place of the credential, and the route the proxy forwards by. Measured
 * against codex-cli 0.156.1 in the 2026-11-07 spike, every request through the proxy with a placeholder only.
 */
final class CodexModelProxy {

    /**
     * The variable a proxied key run's provider reads its key from. It holds {@link CodexAdapter#PLACEHOLDER}; the
     * model proxy drops the header the agent sends and injects the real key.
     */
    static final String PLACEHOLDER_VARIABLE = "SPIRE_MODEL_PLACEHOLDER";

    /** The model provider a proxied run defines for itself, so Codex's own {@code openai} provider is never used. */
    static final String PROVIDER_ID = "spire";

    /** A seat's model call, named exactly in its route as well as under its prefix. */
    private static final String SEAT_MODEL_CALL = "/backend-api/codex/responses";

    private CodexModelProxy() {
    }

    /**
     * Nothing for a run that talks to the vendor itself; for a proxied one, a provider of its own pointed at the model
     * proxy. A key run needs the provider's base URL {@code /v1}, its key variable and the responses wire API; a seat
     * run the base URL {@code /backend-api/codex}, {@code requires_openai_auth} (so the sign-in file is read) and the
     * top-level {@code chatgpt_base_url}. The string values are TOML, hence the double quotes inside the single ones,
     * as for the thinking level.
     */
    static String settings(HarnessInvocation invocation) {
        if (!invocation.credentialProxied()) {
            return "";
        }
        String provider = "model_providers." + PROVIDER_ID + ".";
        String origin = CredentialRoute.AGENT_FACING_ORIGIN;
        String common = setting("model_provider", PROVIDER_ID)
                + setting(provider + "name", PROVIDER_ID)
                + setting(provider + "wire_api", "responses");
        if (CodexAdapter.isSeat(invocation)) {
            return common
                    + setting(provider + "base_url", origin + "/backend-api/codex")
                    + " -c " + provider + "requires_openai_auth=true"
                    + setting("chatgpt_base_url", origin + "/backend-api/");
        }
        return common
                + setting(provider + "base_url", origin + "/v1")
                + setting(provider + "env_key", PLACEHOLDER_VARIABLE);
    }

    /**
     * A proxied run's environment, built without reading the model credential's value: the worker hands placeholders
     * under the neutral keys, and neither is forwarded. A seat gets {@link PlaceholderSignIn}'s file; a key run gets
     * {@link CodexAdapter#PLACEHOLDER} under the variable its provider reads.
     */
    static Map<String, String> environment(HarnessInvocation invocation) {
        Map<String, String> credentials = new LinkedHashMap<>(invocation.credentials());
        credentials.remove(HarnessInvocation.CREDENTIAL);
        credentials.remove(HarnessInvocation.SIGN_IN);
        Map<String, String> reserved = new LinkedHashMap<>(CodexAdapter.OWN_SETTINGS);
        if (CodexAdapter.isSeat(invocation)) {
            reserved.put(CodexAdapter.SIGN_IN_VARIABLE, PlaceholderSignIn.file());
        } else {
            reserved.put(PLACEHOLDER_VARIABLE, CodexAdapter.PLACEHOLDER);
        }
        return EnvironmentPolicy.merge(credentials, reserved);
    }

    /**
     * The model proxy's route for a proxied run, per payment:
     *
     * <ul>
     *   <li>a key run called {@code POST /v1/responses} only; {@code /v1/models} is the model list the same provider
     *       may read, so both match exactly and nothing else does;</li>
     *   <li>a seat run called {@code GET /backend-api/codex/models}, {@code POST /backend-api/codex/responses}
     *       (compaction too), {@code GET /backend-api/wham/accounts/check} and {@code GET
     *       /backend-api/wham/settings/user}: the two prefixes, and no redirect. The model call is named exactly
     *       as well, so the model proxy logs it by name and a 401 on it marks the seat; the proxy logs every other
     *       seat path by its prefix only.</li>
     * </ul>
     */
    static Optional<CredentialRoute> route(HarnessInvocation invocation) {
        if (!invocation.credentialProxied()) {
            return Optional.empty();
        }
        if (CodexAdapter.isSeat(invocation)) {
            return Optional.of(new CredentialRoute("https://chatgpt.com", CredentialRoute.Injection.CHATGPT_SEAT,
                    Set.of(SEAT_MODEL_CALL), List.of("/backend-api/codex/", "/backend-api/wham/")));
        }
        return Optional.of(new CredentialRoute("https://api.openai.com", CredentialRoute.Injection.BEARER,
                Set.of("/v1/responses", "/v1/models"), List.of()));
    }

    /** One {@code -c key="value"} override, quoted for the shell line. */
    private static String setting(String key, String value) {
        return " -c " + CodexAdapter.quoted(key + "=\"" + value + "\"", key);
    }
}
