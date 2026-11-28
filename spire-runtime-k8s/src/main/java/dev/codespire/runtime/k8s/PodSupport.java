package dev.codespire.runtime.k8s;

import dev.codespire.runtime.ContainerSpec;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.HostAlias;
import io.fabric8.kubernetes.api.model.HostAliasBuilder;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.PodSecurityContext;
import io.fabric8.kubernetes.api.model.PodSecurityContextBuilder;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.ProbeBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.SeccompProfileBuilder;
import io.fabric8.kubernetes.api.model.SecurityContext;
import io.fabric8.kubernetes.api.model.SecurityContextBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** What the proxy pod and the unit pod share: restricted hardening, metadata, probes, volumes and the seal. */
final class PodSupport {

    static final String SEAL_COMMAND = "spire-seal";
    static final String TMP_PATH = "/tmp";
    static final String RESTRICTED_SECCOMP = "RuntimeDefault";
    static final int SEAL_INTERVAL_MILLIS = 250;

    private PodSupport() {
    }

    static ObjectMeta metadata(String name, String runId, String role, KubernetesRuntimeConfig config,
                               OwnerReference anchor) {
        return new ObjectMetaBuilder()
                .withName(name)
                .withNamespace(config.namespace())
                .withLabels(UnitNames.labels(runId, role))
                .withAnnotations(Map.of(UnitNames.RUN_ID_ANNOTATION, runId))
                .withOwnerReferences(anchor)
                .build();
    }

    /** An image with no tag runs whatever is newest; a unit runs one exact build, by tag or by digest. */
    static String pinned(String image) {
        boolean hasDigest = image.contains("@sha256:");
        String lastSegment = image.substring(image.lastIndexOf('/') + 1);
        boolean hasTag = lastSegment.contains(":");
        if (!hasDigest && (!hasTag || lastSegment.endsWith(":latest"))) {
            throw new IllegalArgumentException("image " + image
                    + " is not pinned: name a tag other than latest, or a digest");
        }
        return image;
    }

    static PodSecurityContext podSecurity() {
        return new PodSecurityContextBuilder()
                .withRunAsNonRoot(true)
                .withSeccompProfile(new SeccompProfileBuilder().withType(RESTRICTED_SECCOMP).build())
                .build();
    }

    /** No privilege escalation, every capability dropped, the runtime's default seccomp profile. */
    static SecurityContext restricted(boolean readOnlyRoot) {
        return new SecurityContextBuilder()
                .withAllowPrivilegeEscalation(false)
                .withReadOnlyRootFilesystem(readOnlyRoot)
                .withNewCapabilities().withDrop("ALL").endCapabilities()
                .withSeccompProfile(new SeccompProfileBuilder().withType(RESTRICTED_SECCOMP).build())
                .build();
    }

    static List<String> sealArguments(List<String> canaries, String control, KubernetesRuntimeConfig config) {
        List<String> arguments = new ArrayList<>();
        for (String canary : canaries) {
            arguments.add("--canary");
            arguments.add(canary);
        }
        arguments.add("--control");
        arguments.add(control);
        arguments.add("--rounds");
        arguments.add(String.valueOf(config.sealRounds()));
        arguments.add("--timeout-ms");
        arguments.add(String.valueOf(config.sealTimeout().toMillis()));
        arguments.add("--interval-ms");
        arguments.add(String.valueOf(SEAL_INTERVAL_MILLIS));
        arguments.add("--deadline-s");
        arguments.add(String.valueOf(config.sealDeadline().toSeconds()));
        return List.copyOf(arguments);
    }

    /** An arm-owned container: the arm's own image and command, no environment, no mounts. */
    static ContainerBuilder armOwned(String name, String image, String command, List<String> arguments,
                                     ResourceRequirements resources) {
        return new ContainerBuilder()
                .withName(name)
                .withImage(pinned(image))
                .withCommand(command)
                .withArgs(arguments)
                .withResources(resources)
                .withSecurityContext(restricted(false));
    }

    /** A container from the spec: its argv is the args, its entrypoint (if any) the command. */
    static ContainerBuilder fromSpec(String name, ContainerSpec spec, ResourceRequirements resources) {
        ContainerBuilder builder = new ContainerBuilder()
                .withName(name)
                .withImage(pinned(spec.image()))
                .withArgs(spec.argv())
                .withResources(resources);
        if (spec.entrypoint() != null) {
            builder.withCommand(spec.entrypoint());
        }
        return builder;
    }

    static ContainerBuilder withEnvironmentSecret(ContainerBuilder builder, String secretName) {
        return builder.addNewEnvFrom().withNewSecretRef().withName(secretName).withOptional(false)
                .endSecretRef().endEnvFrom();
    }

    static Probe tcpStartup(int port, int periodSeconds, int failureThreshold) {
        return new ProbeBuilder()
                .withNewTcpSocket().withPort(new IntOrString(port)).endTcpSocket()
                .withPeriodSeconds(periodSeconds)
                .withFailureThreshold(failureThreshold)
                .build();
    }

    static ContainerBuilder listening(ContainerBuilder builder, int port) {
        return builder.withPorts(new ContainerPortBuilder().withContainerPort(port).withProtocol("TCP").build());
    }

    static VolumeMount mount(String volume, String path, boolean readOnly) {
        return new VolumeMountBuilder().withName(volume).withMountPath(path).withReadOnly(readOnly).build();
    }

    static Volume emptyDir(String name, boolean inMemory, Quantity sizeLimit) {
        VolumeBuilder builder = new VolumeBuilder().withName(name);
        if (inMemory) {
            builder.withNewEmptyDir().withMedium("Memory").withSizeLimit(sizeLimit).endEmptyDir();
        } else {
            builder.withNewEmptyDir().withSizeLimit(sizeLimit).endEmptyDir();
        }
        return builder.build();
    }

    static HostAlias alias(String address, String... names) {
        return new HostAliasBuilder().withIp(address).withHostnames(names).build();
    }
}
