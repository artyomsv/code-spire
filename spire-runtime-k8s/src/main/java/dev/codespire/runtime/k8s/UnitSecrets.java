package dev.codespire.runtime.k8s;

import dev.codespire.runtime.EgressPolicy;
import dev.codespire.runtime.RunUnitSpec;
import dev.codespire.runtime.TinyproxyConfig;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Secrets of one unit: one per container's environment, so no container can read another's values, plus one
 * for the inputs. Every Secret is immutable and owned by the unit's anchor, so deleting the anchor deletes them.
 */
final class UnitSecrets {

    static final String SECRET_ROLE = "secret";
    static final String EGRESS_CONF = "SPIRE_EGRESS_CONF";
    static final String EGRESS_FILTER = "SPIRE_EGRESS_FILTER";
    /** The egress proxy takes its one client, the model proxy in its own pod, over loopback. */
    static final String LOOPBACK = "127.0.0.1";

    private UnitSecrets() {
    }

    static List<Secret> of(RunUnitSpec spec, KubernetesRuntimeConfig config, OwnerReference anchor) {
        String runId = spec.runId();
        List<Secret> secrets = new ArrayList<>();
        secrets.add(secret(runId, config, anchor, UnitNames.environmentSecret(runId, "fetch"),
                spec.environmentBehindFetch(spec.init())));
        secrets.add(secret(runId, config, anchor, UnitNames.environmentSecret(runId, "agent"),
                spec.environmentFor(spec.agent())));
        secrets.add(secret(runId, config, anchor, UnitNames.environmentSecret(runId, "publisher"),
                spec.environmentFor(spec.publisher())));
        secrets.add(secret(runId, config, anchor, UnitNames.environmentSecret(runId, "model"),
                spec.environmentFor(spec.modelProxy())));

        // A proxied unit always enforces, whatever mode the spec carries.
        EgressPolicy own = new EgressPolicy(EgressPolicy.Mode.ENFORCE, spec.egress().hosts());
        secrets.add(secret(runId, config, anchor, UnitNames.environmentSecret(runId, "egress"),
                proxyEnvironment(own, spec, new TinyproxyConfig.Listener(EgressPolicy.PROXY_PORT, LOOPBACK))));
        EgressPolicy fetch = new EgressPolicy(EgressPolicy.Mode.ENFORCE, spec.fetchHosts());
        secrets.add(secret(runId, config, anchor, UnitNames.environmentSecret(runId, "fetchproxy"),
                proxyEnvironment(fetch, spec, new TinyproxyConfig.Listener(RunUnitSpec.FETCH_PROXY_PORT, null))));

        Map<String, String> files = spec.inputs() == null ? Map.of() : spec.inputs().files();
        secrets.add(secret(runId, config, anchor, UnitNames.inputsSecret(runId), files));
        return List.copyOf(secrets);
    }

    private static Map<String, String> proxyEnvironment(EgressPolicy policy, RunUnitSpec spec,
                                                        TinyproxyConfig.Listener listener) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put(EGRESS_CONF, TinyproxyConfig.configFor(policy, spec.enterprise(), listener));
        environment.put(EGRESS_FILTER, TinyproxyConfig.filterFor(policy));
        return environment;
    }

    private static Secret secret(String runId, KubernetesRuntimeConfig config, OwnerReference anchor, String name,
                                 Map<String, String> entries) {
        Map<String, String> data = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            data.put(entry.getKey(),
                    Base64.getEncoder().encodeToString(entry.getValue().getBytes(StandardCharsets.UTF_8)));
        }
        return new SecretBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(config.namespace())
                .withLabels(UnitNames.labels(runId, SECRET_ROLE))
                .withOwnerReferences(anchor)
                .endMetadata()
                .withType("Opaque")
                .withImmutable(true)
                .withData(data)
                .build();
    }
}
