package dev.codespire.runtime.k8s;

import dev.codespire.runtime.EgressPolicy;
import dev.codespire.runtime.RunUnitSpec;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Volume;
import java.util.List;
import java.util.Objects;

/**
 * Pod P of a unit: the credential-holding side. It runs the model proxy, the egress proxy behind it and the fetch
 * proxy init clones through. The unit pod reaches it and nothing else; it reaches the public internet and DNS.
 */
final class ProxyPod {

    static final String SEAL = "seal";
    static final String MODEL_PROXY = "model-proxy";
    static final String EGRESS_PROXY = "egress-proxy";
    static final String FETCH_PROXY = "fetch-proxy";

    static final String EGRESS_ALIAS = EgressPolicy.PROXY_ALIAS;
    static final String FETCH_PROXY_COMMAND = "spire-fetch-proxy";

    /** Slack after the init timeout and the wall clock: the pod is a backstop, the worker deletes it first. */
    private static final long BACKSTOP_SECONDS = 60 + 300;
    private static final long TERMINATION_GRACE_SECONDS = 30;
    private static final Quantity TMP_SIZE = new Quantity("16Mi");

    private static final int MODEL_STARTUP_PERIOD_SECONDS = 2;
    private static final int MODEL_STARTUP_FAILURES = 60;
    private static final int EGRESS_STARTUP_PERIOD_SECONDS = 1;
    private static final int EGRESS_STARTUP_FAILURES = 20;

    private ProxyPod() {
    }

    static Pod of(RunUnitSpec spec, KubernetesRuntimeConfig config, OwnerReference anchor) {
        Objects.requireNonNull(spec.modelProxy(), "a unit pod pair needs a model proxy");
        String runId = spec.runId();
        long deadline = spec.wallClock().toSeconds() + config.initTimeout().toSeconds() + BACKSTOP_SECONDS;
        return new PodBuilder()
                .withMetadata(PodSupport.metadata(UnitNames.proxyPod(runId), runId, UnitPolicies.PROXY_ROLE,
                        config, anchor))
                .withNewSpec()
                .withRestartPolicy("Never")
                .withAutomountServiceAccountToken(false)
                .withEnableServiceLinks(false)
                .withShareProcessNamespace(false)
                .withSecurityContext(PodSupport.podSecurity())
                .withActiveDeadlineSeconds(deadline)
                .withTerminationGracePeriodSeconds(TERMINATION_GRACE_SECONDS)
                .withHostAliases(PodSupport.alias(UnitSecrets.LOOPBACK, EGRESS_ALIAS))
                .withInitContainers(seal(config))
                .withContainers(modelProxy(spec), egressProxy(spec, config), fetchProxy(spec, config))
                .withVolumes(tmpVolumes())
                .endSpec()
                .build();
    }

    private static Container seal(KubernetesRuntimeConfig config) {
        // The proxy pod's control is cluster DNS; the API service address is allowed to it, so it is no canary here.
        List<String> arguments = PodSupport.sealArguments(config.sealCanaries(), config.dnsAddress(), config);
        return PodSupport.armOwned(SEAL, config.publisherImage(), PodSupport.SEAL_COMMAND, arguments,
                ContainerResources.seal()).build();
    }

    private static Container modelProxy(RunUnitSpec spec) {
        ContainerBuilder builder = PodSupport.fromSpec(MODEL_PROXY, spec.modelProxy(),
                ContainerResources.modelProxy());
        PodSupport.withEnvironmentSecret(builder, UnitNames.environmentSecret(spec.runId(), "model"));
        PodSupport.listening(builder, RunUnitSpec.MODEL_PROXY_PORT)
                .withSecurityContext(PodSupport.restricted(true))
                .withStartupProbe(PodSupport.tcpStartup(RunUnitSpec.MODEL_PROXY_PORT,
                        MODEL_STARTUP_PERIOD_SECONDS, MODEL_STARTUP_FAILURES))
                .addToVolumeMounts(PodSupport.mount("tmp-model", PodSupport.TMP_PATH, false));
        return builder.build();
    }

    private static Container egressProxy(RunUnitSpec spec, KubernetesRuntimeConfig config) {
        // The image's own command runs tinyproxy from the environment; the arm overrides nothing.
        ContainerBuilder builder = new ContainerBuilder()
                .withName(EGRESS_PROXY)
                .withImage(PodSupport.pinned(config.egressImage()))
                .withResources(ContainerResources.smallProxy())
                .withSecurityContext(PodSupport.restricted(true))
                .withStartupProbe(PodSupport.tcpStartup(EgressPolicy.PROXY_PORT,
                        EGRESS_STARTUP_PERIOD_SECONDS, EGRESS_STARTUP_FAILURES))
                .addToVolumeMounts(PodSupport.mount("tmp-egress", PodSupport.TMP_PATH, false));
        PodSupport.withEnvironmentSecret(builder, UnitNames.environmentSecret(spec.runId(), "egress"));
        return PodSupport.listening(builder, EgressPolicy.PROXY_PORT).build();
    }

    private static Container fetchProxy(RunUnitSpec spec, KubernetesRuntimeConfig config) {
        List<String> command = List.of(FETCH_PROXY_COMMAND, String.valueOf(RunUnitSpec.FETCH_CLOSE_PORT),
                String.valueOf(config.initTimeout().toSeconds()));
        ContainerBuilder builder = new ContainerBuilder()
                .withName(FETCH_PROXY)
                .withImage(PodSupport.pinned(config.egressImage()))
                .withCommand(command)
                .withResources(ContainerResources.smallProxy())
                .withSecurityContext(PodSupport.restricted(true))
                .addToVolumeMounts(PodSupport.mount("tmp-fetch", PodSupport.TMP_PATH, false));
        PodSupport.withEnvironmentSecret(builder, UnitNames.environmentSecret(spec.runId(), "fetchproxy"));
        return PodSupport.listening(builder, RunUnitSpec.FETCH_PROXY_PORT).build();
    }

    private static List<Volume> tmpVolumes() {
        return List.of(PodSupport.emptyDir("tmp-model", true, TMP_SIZE),
                PodSupport.emptyDir("tmp-egress", true, TMP_SIZE),
                PodSupport.emptyDir("tmp-fetch", true, TMP_SIZE));
    }
}
