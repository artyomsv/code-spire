package dev.codespire.runtime.k8s;

import dev.codespire.runtime.ContainerSpec;
import dev.codespire.runtime.Mount;
import dev.codespire.runtime.RunUnitSpec;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pod U of a unit: the side that runs untrusted model output. Its network policy lets it open TCP only to its proxy
 * pod, so its agent holds placeholders and reaches the model through the proxy pod alone. Init containers run in
 * order: the seal proves the policy is in force, fetch clones, fetch-closed proves the clone path is shut, and the
 * publisher stays as a native sidecar until the pod ends.
 */
final class UnitPod {

    static final String SEAL = "seal";
    static final String FETCH = "fetch";
    static final String FETCH_CLOSED = "fetch-closed";
    static final String PUBLISHER = "publisher";
    static final String AGENT = "agent";

    static final String FETCH_CLOSED_COMMAND = "spire-fetch-closed";
    static final String STAY_UNTIL_TERM = "--stay-until-term";
    static final String AGENT_TMP_VOLUME = "agent-tmp";
    static final String INIT_INPUTS_SUFFIX = "-init";

    private static final int INPUTS_FILE_MODE = 0444;
    private static final int FETCH_CLOSED_REFUSALS = 3;
    private static final int FETCH_CLOSED_DEADLINE_SECONDS = 30;
    private static final long TERMINATION_GRACE_SECONDS = 30;

    private UnitPod() {
    }

    static Pod of(RunUnitSpec spec, KubernetesRuntimeConfig config, OwnerReference anchor, String proxyPodIp) {
        String runId = spec.runId();
        long deadline = config.initTimeout().toSeconds() + spec.wallClock().toSeconds();
        return new PodBuilder()
                .withMetadata(PodSupport.metadata(UnitNames.unitPod(runId), runId, UnitPolicies.UNIT_ROLE,
                        config, anchor))
                .withNewSpec()
                .withRestartPolicy("Never")
                .withAutomountServiceAccountToken(false)
                .withEnableServiceLinks(false)
                .withShareProcessNamespace(false)
                .withSecurityContext(PodSupport.podSecurity())
                .withActiveDeadlineSeconds(deadline)
                .withTerminationGracePeriodSeconds(TERMINATION_GRACE_SECONDS)
                .withHostAliases(PodSupport.alias(proxyPodIp, RunUnitSpec.MODEL_PROXY_ALIAS,
                        RunUnitSpec.FETCH_PROXY_ALIAS))
                .withInitContainers(seal(config), fetch(spec), fetchClosed(config), publisher(spec))
                .withContainers(agent(spec, config))
                .withVolumes(volumes(spec))
                .endSpec()
                .build();
    }

    private static Container seal(KubernetesRuntimeConfig config) {
        List<String> canaries = new ArrayList<>(config.sealCanaries());
        if (config.apiCanary() != null && !config.apiCanary().isBlank()) {
            canaries.add(config.apiCanary());
        }
        String control = RunUnitSpec.MODEL_PROXY_ALIAS + ":" + RunUnitSpec.MODEL_PROXY_PORT;
        return PodSupport.armOwned(SEAL, config.publisherImage(), PodSupport.SEAL_COMMAND,
                PodSupport.sealArguments(canaries, control, config), ContainerResources.seal()).build();
    }

    private static Container fetch(RunUnitSpec spec) {
        ContainerBuilder builder = PodSupport.fromSpec(FETCH, spec.init(), ContainerResources.fetch())
                .withSecurityContext(PodSupport.restricted(false))
                .withVolumeMounts(mountsOf(spec, spec.init(), true));
        return PodSupport.withEnvironmentSecret(builder, UnitNames.environmentSecret(spec.runId(), "fetch")).build();
    }

    private static Container fetchClosed(KubernetesRuntimeConfig config) {
        String proxy = RunUnitSpec.FETCH_PROXY_ALIAS;
        List<String> arguments = List.of(
                "--close", proxy + ":" + RunUnitSpec.FETCH_CLOSE_PORT,
                "--target", proxy + ":" + RunUnitSpec.FETCH_PROXY_PORT,
                "--refusals", String.valueOf(FETCH_CLOSED_REFUSALS),
                "--deadline-s", String.valueOf(FETCH_CLOSED_DEADLINE_SECONDS));
        return PodSupport.armOwned(FETCH_CLOSED, config.publisherImage(), FETCH_CLOSED_COMMAND, arguments,
                ContainerResources.seal()).build();
    }

    /** A native sidecar: an init container that restarts always and so runs beside the agent until the pod ends. */
    private static Container publisher(RunUnitSpec spec) {
        ContainerSpec publisher = spec.publisher();
        List<String> arguments = new ArrayList<>(publisher.argv());
        arguments.add(STAY_UNTIL_TERM);
        ContainerBuilder builder = PodSupport.fromSpec(PUBLISHER, publisher, ContainerResources.publisher())
                .withArgs(arguments)
                .withRestartPolicy("Always")
                .withSecurityContext(PodSupport.restricted(false))
                .withVolumeMounts(mountsOf(spec, publisher, false));
        return PodSupport.withEnvironmentSecret(builder,
                UnitNames.environmentSecret(spec.runId(), "publisher")).build();
    }

    private static Container agent(RunUnitSpec spec, KubernetesRuntimeConfig config) {
        List<VolumeMount> mounts = new ArrayList<>(mountsOf(spec, spec.agent(), false));
        mounts.add(PodSupport.mount(AGENT_TMP_VOLUME, PodSupport.TMP_PATH, false));
        ContainerBuilder builder = PodSupport.fromSpec(AGENT, spec.agent(), ContainerResources.agent(spec, config))
                .withSecurityContext(PodSupport.restricted(false))
                .withVolumeMounts(mounts);
        return PodSupport.withEnvironmentSecret(builder, UnitNames.environmentSecret(spec.runId(), "agent")).build();
    }

    /**
     * Init's inputs mount is an empty directory: the clone never reads inputs, and the arm delivers the files as a
     * Secret the agent alone mounts.
     */
    private static List<VolumeMount> mountsOf(RunUnitSpec spec, ContainerSpec container, boolean isInit) {
        List<VolumeMount> mounts = new ArrayList<>();
        for (Mount mount : container.mounts()) {
            String volume = mount.volume();
            if (isInputs(spec, volume) && isInit) {
                volume = volume + INIT_INPUTS_SUFFIX;
            }
            mounts.add(PodSupport.mount(volume, mount.path(), mount.readOnly()));
        }
        return mounts;
    }

    private static boolean isInputs(RunUnitSpec spec, String volume) {
        return spec.inputs() != null && spec.inputs().volume().equals(volume);
    }

    private static List<Volume> volumes(RunUnitSpec spec) {
        Quantity size = new Quantity(String.valueOf(spec.diskBytes()));
        List<Volume> volumes = new ArrayList<>();
        for (String name : sharedVolumeNames(spec)) {
            volumes.add(PodSupport.emptyDir(name, false, size));
        }
        if (spec.inputs() != null) {
            volumes.add(PodSupport.emptyDir(spec.inputs().volume() + INIT_INPUTS_SUFFIX, false, size));
            volumes.add(inputsSecretVolume(spec));
        }
        volumes.add(PodSupport.emptyDir(AGENT_TMP_VOLUME, true, size));
        return volumes;
    }

    private static Set<String> sharedVolumeNames(RunUnitSpec spec) {
        Set<String> names = new LinkedHashSet<>();
        for (ContainerSpec container : List.of(spec.init(), spec.agent(), spec.publisher())) {
            for (Mount mount : container.mounts()) {
                if (!isInputs(spec, mount.volume())) {
                    names.add(mount.volume());
                }
            }
        }
        return names;
    }

    private static Volume inputsSecretVolume(RunUnitSpec spec) {
        return new VolumeBuilder()
                .withName(spec.inputs().volume())
                .withNewSecret()
                .withSecretName(UnitNames.inputsSecret(spec.runId()))
                .withDefaultMode(INPUTS_FILE_MODE)
                .endSecret()
                .build();
    }
}
