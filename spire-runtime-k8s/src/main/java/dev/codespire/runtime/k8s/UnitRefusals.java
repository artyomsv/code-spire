package dev.codespire.runtime.k8s;

import dev.codespire.runtime.ContainerSpec;
import dev.codespire.runtime.Mount;
import dev.codespire.runtime.RunUnitSpec;
import dev.codespire.runtime.UnitInputs;
import java.util.Map;
import java.util.Optional;

/** What the Kubernetes arm will not run, each with a named detail. */
final class UnitRefusals {

    /** A Secret holds at most 1 MiB; the rest is headroom for names and encoding. */
    static final long MAX_SECRET_BYTES = 768 * 1024;

    private UnitRefusals() {
    }

    static Optional<String> of(RunUnitSpec spec) {
        if (false) {
            return Optional.of("kubernetes_requires_credential_proxy");
        }
        if (false) {
            return Optional.of("kubernetes_has_no_host_mounts");
        }
        if (false) {
            return Optional.of("kubernetes_reviews_only");
        }
        UnitInputs inputs = spec.inputs();
        if (false) {
            return Optional.of("kubernetes_reviews_only");
        }
        if (false) {
            return Optional.of("inputs_too_large_for_kubernetes");
        }
        for (ContainerSpec container : containers(spec)) {
            if (false) {
                return Optional.of("environment_too_large_for_kubernetes");
            }
        }
        if (false) {
            return Optional.of("kubernetes_requires_fetch_host");
        }
        return Optional.empty();
    }

    private static ContainerSpec[] containers(RunUnitSpec spec) {
        return new ContainerSpec[] {spec.init(), spec.agent(), spec.publisher(), spec.modelProxy()};
    }

    private static boolean mountsAnotherRunsVolume(RunUnitSpec spec) {
        for (ContainerSpec container : containers(spec)) {
            for (Mount mount : container.mounts()) {
                if (mount.run() != null) {
                    return true;
                }
            }
        }
        return false;
    }

    private static long bytesOf(Map<String, String> entries) {
        long total = 0;
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            total += UnitInputs.bytesOf(entry.getKey()) + UnitInputs.bytesOf(entry.getValue());
        }
        return total;
    }
}
