package dev.codespire.runtime;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One container in a run unit.
 *
 * <p>{@code environment} carries injected credentials and MUST NOT be logged. That is enforced by
 * the redacting {@code toString()} below rather than asserted in prose, because a record prints
 * every component and {@code log.info("creating {}", spec)} is the obvious line to write. The
 * docker arm additionally keeps credentials out of container labels, which {@code docker inspect}
 * prints.
 *
 * <p>{@code mounts} are typed {@link Mount}s rather than path strings carrying a {@code :ro}
 * suffix — see that class for why the difference is a security one.
 */
public record ContainerSpec(String image, List<String> argv, Map<String, String> environment,
                            List<Mount> mounts, List<String> entrypoint) {

    /** The image's own entrypoint, which every container before M4 used. */
    public ContainerSpec(String image, List<String> argv, Map<String, String> environment, List<Mount> mounts) {
        this(image, argv, environment, mounts, null);
    }

    public ContainerSpec {
        Objects.requireNonNull(image, "image");
        argv = List.copyOf(Objects.requireNonNull(argv, "argv"));
        environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        mounts = List.copyOf(Objects.requireNonNull(mounts, "mounts"));
        if (image.isBlank()) {
            throw new IllegalArgumentException("a container must name an image");
        }
        // A verify check overrides the agent image's entrypoint, which would otherwise wrap the command in
        // the agent's own script -- one that commits and bundles. Empty would mean "no program at all".
        if (entrypoint != null) {
            entrypoint = List.copyOf(entrypoint);
            if (entrypoint.isEmpty()) {
                throw new IllegalArgumentException("an entrypoint override names a program; null keeps the image's own");
            }
        }

        // One path, one mount. A List permits duplicates, which re-opens the hole the typed
        // read-only flag closed: appending a writable /handoff after the read-only one leaves an
        // arm that dedups last-wins with a writable /handoff and no error anywhere.
        Set<String> paths = new LinkedHashSet<>();
        for (Mount mount : mounts) {
            if (!paths.add(mount.path())) {
                throw new IllegalArgumentException("duplicate mount path: " + mount.path());
            }
        }
    }

    @Override
    public String toString() {
        return "ContainerSpec[image=" + image
                + ", argv=" + argv
                + ", environment=" + environment.keySet() + " (values redacted)"
                + ", mounts=" + mounts
                + ", entrypoint=" + entrypoint + "]";
    }
}
